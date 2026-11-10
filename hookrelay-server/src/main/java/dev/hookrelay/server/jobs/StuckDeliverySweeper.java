package dev.hookrelay.server.jobs;

import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.delivery.DeliveryRepository;
import dev.hookrelay.server.delivery.DeliveryStateService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net under the queue. Every enqueue sets next_attempt_at to when the message should
 * arrive, so a delivery 15 minutes past it with no outbox row waiting has lost its message
 * (purged queue, broker data loss, a crash mid-claim): queue it again. Also re-queues deliveries
 * still parked on an endpoint that is active (resume committed, re-queue step didn't run).
 * Duplicates are harmless: a stale dispatch token is dropped by the worker.
 */
@Component
public class StuckDeliverySweeper {

    static final Duration GRACE = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(StuckDeliverySweeper.class);

    private final DeliveryRepository deliveries;
    private final DeliveryStateService state;
    private final MeterRegistry meters;
    private final Clock clock;

    StuckDeliverySweeper(DeliveryRepository deliveries, DeliveryStateService state, MeterRegistry meters,
                         Clock clock) {
        this.deliveries = deliveries;
        this.state = state;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(cron = "${hookrelay.jobs.sweep-cron:0 * * * * *}")
    @SchedulerLock(name = "stuck-sweeper", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void run() {
        meters.counter("hookrelay.jobs.runs", "job", "stuck-sweeper").increment();
        Instant staleBefore = Times.now(clock).minus(GRACE);
        long recovered = Batches.forEachId(n -> deliveries.stuckIds(staleBefore, Limit.of(n)),
                id -> state.recover(id, staleBefore))
                + Batches.forEachId(n -> deliveries.parkedOnActiveIds(Limit.of(n)), id -> state.recover(id, staleBefore));
        if (recovered > 0) {
            meters.counter("hookrelay.deliveries.recovered").increment(recovered);
            log.warn("Re-queued {} deliveries that had lost their queue message", recovered);
        }
    }
}
