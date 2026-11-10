package dev.hookrelay.server.event;

import dev.hookrelay.server.common.ApiException;
import dev.hookrelay.server.common.Ids;
import dev.hookrelay.server.common.Secrets;
import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.config.HookRelayProperties;
import dev.hookrelay.server.delivery.Delivery;
import dev.hookrelay.server.delivery.DeliveryDispatcher;
import dev.hookrelay.server.delivery.DeliveryRepository;
import dev.hookrelay.server.endpoint.Endpoint;
import dev.hookrelay.server.endpoint.EndpointService;
import dev.hookrelay.server.eventtype.EventType;
import dev.hookrelay.server.eventtype.EventTypeService;
import dev.hookrelay.server.eventtype.SchemaValidator;
import jakarta.persistence.EntityManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts an event: validates it, then in ONE transaction stores the event, one delivery per
 * subscribed endpoint, and their outbox messages. Nothing is sent here; the outbox relay
 * publishes after commit, so an accepted event is never lost.
 */
@Service
public class IngestService {

    static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    public record IngestResult(UUID eventId, long deliveryCount, boolean replayed) { }

    private final EventRequestParser parser;
    private final EventTypeService eventTypes;
    private final SchemaValidator schemas;
    private final EndpointService endpoints;
    private final DeliveryDispatcher dispatcher;
    private final DeliveryRepository deliveries;
    private final IdempotencyRepository idempotency;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final HookRelayProperties props;
    private final Counter ingested;
    private final Clock clock;

    IngestService(EventRequestParser parser, EventTypeService eventTypes, SchemaValidator schemas,
                  EndpointService endpoints, DeliveryDispatcher dispatcher, DeliveryRepository deliveries,
                  IdempotencyRepository idempotency, EntityManager em, TransactionTemplate tx,
                  HookRelayProperties props,
                  MeterRegistry meters, Clock clock) {
        this.parser = parser;
        this.eventTypes = eventTypes;
        this.schemas = schemas;
        this.endpoints = endpoints;
        this.dispatcher = dispatcher;
        this.deliveries = deliveries;
        this.idempotency = idempotency;
        this.em = em;
        this.tx = tx;
        this.props = props;
        this.ingested = meters.counter("hookrelay.events.ingested");
        this.clock = clock;
    }

    public IngestResult ingest(UUID appId, byte[] body, String idemKey) {
        if (idemKey != null && (idemKey.isBlank() || idemKey.length() > 255)) {
            throw ApiException.badRequest("invalid-idempotency-key", "Idempotency-Key must be 1-255 characters");
        }
        EventRequestParser.Parsed parsed = parser.parse(body);
        byte[] bodyHash = Secrets.sha256(body);
        try {
            return tx.execute(s -> doIngest(appId, parsed, idemKey, bodyHash));
        } catch (RuntimeException e) {
            // Two requests with the same key raced; the other one won. Answer as it did.
            if (idemKey == null || !isDuplicateKey(e)) {
                throw e;
            }
            return tx.execute(s -> replay(appId, idemKey, bodyHash)).orElseThrow(() -> e);
        }
    }

    /** The JPA flush surfaces Hibernate's exception, not Spring's translated one. */
    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof DataIntegrityViolationException
                    || t instanceof org.hibernate.exception.ConstraintViolationException
                    || t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    private IngestResult doIngest(UUID appId, EventRequestParser.Parsed parsed, String idemKey, byte[] bodyHash) {
        if (idemKey != null) {
            Optional<IngestResult> previous = replay(appId, idemKey, bodyHash);
            if (previous.isPresent()) {
                return previous.get();
            }
        }
        EventType type = eventTypes.requirePostable(appId, parsed.eventType());
        if (type.getJsonSchema() != null) {
            List<String> errors = schemas.validate(type.getJsonSchema(), parsed.payload());
            if (!errors.isEmpty()) {
                throw ApiException.unprocessable("schema-mismatch", "payload does not match the event type's schema")
                        .with("errors", errors);
            }
        }
        UUID eventId = Ids.newId();
        Instant now = Times.now(clock);
        if (idemKey != null) {
            em.persist(new IdempotencyRecord(new IdempotencyRecord.Key(appId, idemKey), bodyHash, eventId, now));
            em.flush(); // surface a duplicate key now, before doing the rest of the work
        }
        int count = store(appId, type.getName(), parsed.payload(), eventId, now);
        return new IngestResult(eventId, count, false);
    }

    /**
     * An event HookRelay emits itself (reserved {@code hookrelay.*} types). The idempotency key
     * makes repeated triggers (e.g. a duplicate DLQ message) produce one event.
     */
    @Transactional
    public UUID ingestSystem(UUID appId, String type, String payload, String idemKey) {
        IdempotencyRecord.Key key = new IdempotencyRecord.Key(appId, idemKey);
        Optional<IdempotencyRecord> seen = idempotency.findById(key);
        if (seen.isPresent()) {
            return seen.get().getEventId();
        }
        UUID eventId = Ids.newId();
        Instant now = Times.now(clock);
        em.persist(new IdempotencyRecord(key, Secrets.sha256(payload.getBytes(StandardCharsets.UTF_8)), eventId, now));
        store(appId, type, payload, eventId, now);
        return eventId;
    }

    public record Targeted(UUID eventId, UUID deliveryId) { }

    /** An event delivered to one chosen endpoint only (the "send test event" button). */
    @Transactional
    public Targeted ingestTargeted(UUID appId, String type, String payload, Endpoint target) {
        UUID eventId = Ids.newId();
        Instant now = Times.now(clock);
        em.persist(new Event(eventId, appId, type, payload, now));
        ingested.increment();
        Delivery d = new Delivery(Ids.newId(), appId, eventId, target.getId(), now,
                now.plus(props.delivery().window()));
        em.persist(d);
        dispatcher.enqueue(d, null);
        return new Targeted(eventId, d.getId());
    }

    /** Persists the event and fans it out to every subscribed endpoint. */
    int store(UUID appId, String type, String payload, UUID eventId, Instant now) {
        em.persist(new Event(eventId, appId, type, payload, now));
        ingested.increment();
        List<Endpoint> targets = endpoints.subscribers(appId, type);
        for (Endpoint target : targets) {
            Delivery d = new Delivery(Ids.newId(), appId, eventId, target.getId(), now,
                    now.plus(props.delivery().window()));
            em.persist(d);
            dispatcher.enqueue(d, null);
        }
        return targets.size();
    }

    private Optional<IngestResult> replay(UUID appId, String idemKey, byte[] bodyHash) {
        IdempotencyRecord.Key key = new IdempotencyRecord.Key(appId, idemKey);
        Optional<IdempotencyRecord> found = idempotency.findById(key);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord rec = found.get();
        if (rec.getCreatedAt().isBefore(Times.now(clock).minus(IDEMPOTENCY_TTL))) {
            idempotency.delete(rec); // expired but not yet pruned: treat the key as new
            em.flush();
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(rec.getBodySha256(), bodyHash)) {
            throw ApiException.unprocessable("idempotency-key-reused",
                    "this Idempotency-Key was already used with a different request body");
        }
        return Optional.of(new IngestResult(rec.getEventId(), deliveries.countByEventId(rec.getEventId()), true));
    }
}
