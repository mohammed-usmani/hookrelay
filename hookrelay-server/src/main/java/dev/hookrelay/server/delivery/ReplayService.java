package dev.hookrelay.server.delivery;

import dev.hookrelay.server.common.ApiException;
import dev.hookrelay.server.common.ConflictException;
import dev.hookrelay.server.common.NotFoundException;
import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.config.HookRelayProperties;
import dev.hookrelay.server.endpoint.EndpointService;
import dev.hookrelay.server.event.Event;
import dev.hookrelay.server.event.EventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Sends finished deliveries again: one at a time, or everything that died on an endpoint. */
@Service
public class ReplayService {

    public record BulkReplayResult(int replayed, int skippedPurged) { }

    private static final int BATCH = 500;

    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final EndpointService endpoints;
    private final DeliveryDispatcher dispatcher;
    private final TransactionTemplate tx;
    private final HookRelayProperties props;
    private final Clock clock;

    ReplayService(DeliveryRepository deliveries, EventRepository events, EndpointService endpoints,
                  DeliveryDispatcher dispatcher, TransactionTemplate tx, HookRelayProperties props, Clock clock) {
        this.deliveries = deliveries;
        this.events = events;
        this.endpoints = endpoints;
        this.dispatcher = dispatcher;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public Delivery replay(UUID appId, UUID deliveryId) {
        Delivery d = deliveries.findForUpdate(deliveryId)
                .filter(x -> x.getApplicationId().equals(appId))
                .orElseThrow(() -> new NotFoundException("delivery"));
        if (!d.getStatus().isFinal()) {
            throw new ConflictException("not-final", "Delivery is still in progress (" + d.getStatus() + ")");
        }
        endpoints.get(appId, d.getEndpointId()); // 404 if the endpoint was deleted
        if (purged(d)) {
            throw new ApiException(410, "payload-purged", "The event payload was removed by retention");
        }
        restart(d);
        return d;
    }

    /** Replays DEAD deliveries created at or after {@code since}, {@value #BATCH} per transaction. */
    public BulkReplayResult replayEndpoint(UUID appId, UUID endpointId, Instant since) {
        endpoints.get(appId, endpointId);
        int replayed = 0;
        int skipped = 0;
        UUID after = new UUID(0, 0);
        while (true) {
            List<UUID> ids = deliveries.deadSince(appId, endpointId, since, after, Limit.of(BATCH));
            if (ids.isEmpty()) {
                return new BulkReplayResult(replayed, skipped);
            }
            int[] counts = tx.execute(s -> {
                int ok = 0;
                int purged = 0;
                for (UUID id : ids) {
                    Delivery d = deliveries.findForUpdate(id).orElseThrow();
                    if (d.getStatus() != DeliveryStatus.DEAD) {
                        continue; // replayed concurrently
                    }
                    if (purged(d)) {
                        purged++;
                    } else {
                        restart(d);
                        ok++;
                    }
                }
                return new int[] {ok, purged};
            });
            replayed += counts[0];
            skipped += counts[1];
            after = ids.getLast();
        }
    }

    private boolean purged(Delivery d) {
        return events.findById(d.getEventId()).map(Event::isPayloadPurged).orElse(true);
    }

    private void restart(Delivery d) {
        Instant now = Times.now(clock);
        d.resetForReplay(now, now.plus(props.delivery().window()));
        dispatcher.enqueue(d, null);
    }
}
