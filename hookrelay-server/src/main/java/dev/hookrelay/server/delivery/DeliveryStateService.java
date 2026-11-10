package dev.hookrelay.server.delivery;

import dev.hookrelay.server.common.Ids;
import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.config.HookRelayProperties;
import dev.hookrelay.server.endpoint.Endpoint;
import dev.hookrelay.server.endpoint.EndpointRepository;
import dev.hookrelay.server.endpoint.EndpointService;
import dev.hookrelay.server.endpoint.EndpointStatus;
import dev.hookrelay.server.event.Event;
import dev.hookrelay.server.event.EventRepository;
import dev.hookrelay.server.outbox.OutboxMessage;
import dev.hookrelay.server.outbox.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every delivery state change, each in its own short transaction with the delivery row locked.
 * The HTTP call happens between {@link #claim} and {@link #recordResult}, outside any
 * transaction, so a slow receiver never holds a database lock.
 */
@Service
public class DeliveryStateService {

    private final DeliveryRepository deliveries;
    private final EndpointRepository endpoints;
    private final EndpointService endpointService;
    private final EventRepository events;
    private final AttemptRepository attempts;
    private final OutboxRepository outbox;
    private final DeliveryDispatcher dispatcher;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final HookRelayProperties props;
    private final Clock clock;

    DeliveryStateService(DeliveryRepository deliveries, EndpointRepository endpoints, EndpointService endpointService,
                         EventRepository events, AttemptRepository attempts, OutboxRepository outbox,
                         DeliveryDispatcher dispatcher, RetryPolicy retryPolicy, CircuitBreaker circuitBreaker,
                         HookRelayProperties props, Clock clock) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.endpointService = endpointService;
        this.events = events;
        this.attempts = attempts;
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Decides what to do with a work message. Returns a claim only when an HTTP attempt should
     * be made now; every other outcome (stale message, expired, parked, deferred) is settled here.
     */
    @Transactional
    public Optional<Claim> claim(UUID deliveryId, UUID token) {
        Optional<Delivery> found = deliveries.findForUpdate(deliveryId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Delivery d = found.get();
        if (!d.getDispatchToken().equals(token) || d.getStatus().isFinal()) {
            return Optional.empty(); // stale or duplicate message
        }
        Instant now = Times.now(clock);
        Endpoint e = endpoints.findForUpdate(d.getEndpointId()).orElseThrow();
        if (e.isDeleted()) {
            markDead(d, "endpoint_deleted");
            return Optional.empty();
        }
        if (now.isAfter(d.getDeadlineAt())) {
            markDead(d, "expired");
            return Optional.empty();
        }
        if (e.getStatus() != EndpointStatus.ACTIVE) {
            d.park(now);
            return Optional.empty();
        }
        CircuitBreaker.Decision admission = circuitBreaker.admit(e.circuit(), now);
        if (admission.admission() == CircuitBreaker.Admission.DEFER) {
            dispatcher.enqueue(d, DelayTier.atLeast(admission.delay())); // not an attempt
            return Optional.empty();
        }
        if (e.getRateLimitPerSec() != null) {
            OutboundRateLimiter.Slot slot = OutboundRateLimiter.take(e.getRateNextSendAt(), e.getRateLimitPerSec(), now);
            if (slot.delay().isPresent()) {
                dispatcher.enqueue(d, DelayTier.atLeast(slot.delay().get())); // not an attempt
                return Optional.empty();
            }
            e.setRateNextSendAt(slot.nextSendAt());
        }
        e.setCircuit(admission.next());
        Event event = events.findById(d.getEventId()).orElseThrow();
        if (event.isPayloadPurged()) {
            markDead(d, "payload_purged");
            return Optional.empty();
        }
        Duration grace = props.delivery().sendTimeout().plus(Duration.ofMinutes(1));
        UUID claimToken = d.rotateToken(now.plus(grace), now);
        return Optional.of(new Claim(d.getId(), claimToken, d.getAttemptCount() + 1, d.getApplicationId(),
                event.getId(), event.getEventType(), e.getId(), e.getUrl(), event.getPayload(),
                endpointService.activeSecrets(e.getId(), now)));
    }

    /** Stores the attempt and moves the delivery on: done, retry later, or dead. */
    @Transactional
    public void recordResult(Claim c, Instant startedAt, SendResult r) {
        attempts.save(new Attempt(Ids.newId(), c.deliveryId(), c.endpointId(), c.attemptNo(), startedAt, r));
        Delivery d = deliveries.findForUpdate(c.deliveryId()).orElseThrow();
        if (!d.getDispatchToken().equals(c.token()) || d.getStatus().isFinal()) {
            return; // someone else (replay, deletion, sweeper) took over meanwhile
        }
        Instant now = Times.now(clock);
        Endpoint e = endpoints.findForUpdate(c.endpointId()).orElseThrow();
        if (r.succeeded()) {
            e.setCircuit(circuitBreaker.onSuccess(e.circuit(), now));
            d.recordAttempt(DeliveryStatus.SUCCEEDED, null, now);
            return;
        }
        e.setCircuit(circuitBreaker.onFailure(e.circuit(), now));
        d.recordAttempt(DeliveryStatus.FAILED_RETRYING, Attempt.truncate(describe(r), 500), now);
        Optional<Duration> delay = retryPolicy.nextDelay(d.getAttemptCount());
        if (delay.isPresent()) {
            dispatcher.enqueue(d, DelayTier.atLeast(delay.get()));
        } else {
            markDead(d, "retries_exhausted");
        }
    }

    /** Final failure: DB status for the API, plus a DLQ message for side effects. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDead(Delivery d, String reason) {
        Instant now = Times.now(clock);
        d.markDead(reason, now);
        outbox.save(new OutboxMessage(d.getId(), d.getDispatchToken(), OutboxMessage.Target.DEAD, null, now));
    }

    private static String describe(SendResult r) {
        return r.status() != null ? "HTTP " + r.status() : r.outcome() + ": " + r.error();
    }
}
