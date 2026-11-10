package dev.hookrelay.server.jobs;

import dev.hookrelay.server.common.Times;
import dev.hookrelay.server.delivery.DeliveryRepository;
import dev.hookrelay.server.delivery.DeliveryStateService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Parked deliveries (endpoint paused or disabled) never reach a worker, so nothing else would
 * notice their delivery window closing. This does.
 */
@Component
public class ExpirySweeper {

    private final DeliveryRepository deliveries;
    private final DeliveryStateService state;
    private final MeterRegistry meters;
    private final Clock clock;

    ExpirySweeper(DeliveryRepository deliveries, DeliveryStateService state, MeterRegistry meters, Clock clock) {
        this.deliveries = deliveries;
        this.state = state;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(cron = "${hookrelay.jobs.sweep-cron:0 * * * * *}")
    @SchedulerLock(name = "expiry-sweeper", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void run() {
        meters.counter("hookrelay.jobs.runs", "job", "expiry-sweeper").increment();
        Batches.forEachId(n -> deliveries.expiredParkedIds(Times.now(clock), Limit.of(n)), state::expireParked);
    }
}
