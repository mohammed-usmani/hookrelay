package dev.hookrelay.server.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Delivery-path meters. No per-app or per-endpoint tags: those would explode cardinality. */
@Component
class DeliveryMetrics {

    private final MeterRegistry meters;
    private final Timer attemptDuration;

    DeliveryMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.attemptDuration = Timer.builder("hookrelay.attempt.duration")
                .description("HTTP time of one delivery attempt")
                .publishPercentileHistogram()
                .register(meters);
    }

    void attempt(SendResult r) {
        meters.counter("hookrelay.attempts", "outcome", r.outcome().name()).increment();
        attemptDuration.record(Duration.ofMillis(r.durationMs()));
    }

    void completed(DeliveryStatus status) {
        meters.counter("hookrelay.deliveries.completed", "outcome",
                status == DeliveryStatus.SUCCEEDED ? "succeeded" : "dead").increment();
    }
}
