package dev.hookrelay.server.stats;

import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.delivery.DeliveryRepository;
import dev.hookrelay.server.delivery.DeliveryStatus;
import dev.hookrelay.server.endpoint.CircuitState;
import dev.hookrelay.server.endpoint.Endpoint;
import dev.hookrelay.server.endpoint.EndpointService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Health of one endpoint over the last 24 hours, computed in the database. */
@Service
public class EndpointStatsService {

    public static final Duration WINDOW = Duration.ofHours(24);

    public record EndpointStats(String window, long attempts, long successes, Double successRate, Double p50Ms,
                                Double p95Ms, Map<DeliveryStatus, Long> deliveriesByStatus,
                                CircuitState circuitState, Instant lastSuccessAt) { }

    private final EndpointService endpoints;
    private final DeliveryRepository deliveries;
    private final EntityManager em;
    private final Clock clock;

    EndpointStatsService(EndpointService endpoints, DeliveryRepository deliveries, EntityManager em, Clock clock) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.em = em;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EndpointStats stats(UUID appId, UUID endpointId) {
        Endpoint e = endpoints.get(appId, endpointId);
        Instant since = Times.now(clock).minus(WINDOW);

        Object[] counts = (Object[]) em.createNativeQuery("""
                select count(*), coalesce(sum(outcome = 'SUCCESS'), 0)
                from attempts where endpoint_id = :id and started_at >= :since""")
                .setParameter("id", endpointId).setParameter("since", since).getSingleResult();
        long attempts = ((Number) counts[0]).longValue();
        long successes = ((Number) counts[1]).longValue();

        Double p50 = null;
        Double p95 = null;
        if (attempts > 0) {
            // ponytail: window percentile scans the endpoint's 24h attempts (indexed); pre-aggregate if that grows past ~1M rows
            Object[] p = (Object[]) em.createNativeQuery("""
                    select percentile_cont(0.5) within group (order by duration_ms) over (),
                           percentile_cont(0.95) within group (order by duration_ms) over ()
                    from attempts where endpoint_id = :id and started_at >= :since limit 1""")
                    .setParameter("id", endpointId).setParameter("since", since).getSingleResult();
            p50 = ((Number) p[0]).doubleValue();
            p95 = ((Number) p[1]).doubleValue();
        }

        Map<DeliveryStatus, Long> byStatus = new LinkedHashMap<>();
        for (DeliveryStatus s : DeliveryStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (Object[] row : deliveries.countByStatusSince(endpointId, since)) {
            byStatus.put((DeliveryStatus) row[0], (Long) row[1]);
        }

        return new EndpointStats("24h", attempts, successes, attempts == 0 ? null : (double) successes / attempts,
                p50, p95, byStatus, e.circuit().state(), e.circuit().lastSuccessAt());
    }
}
