package dev.hookrelay.server.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboundRateLimiterTest {

    private final Instant t0 = Instant.parse("2026-11-10T10:00:00Z");

    @Test
    void spacesSendsEvenlyAtTheConfiguredRate() {
        OutboundRateLimiter.Slot first = OutboundRateLimiter.take(null, 2, t0);
        assertThat(first.delay()).isEmpty();
        assertThat(first.nextSendAt()).isEqualTo(t0.plusMillis(500));

        OutboundRateLimiter.Slot tooSoon = OutboundRateLimiter.take(first.nextSendAt(), 2, t0.plusMillis(100));
        assertThat(tooSoon.delay()).contains(Duration.ofMillis(400));
        assertThat(tooSoon.nextSendAt()).isEqualTo(first.nextSendAt());

        OutboundRateLimiter.Slot onTime = OutboundRateLimiter.take(first.nextSendAt(), 2, t0.plusMillis(500));
        assertThat(onTime.delay()).isEmpty();
        assertThat(onTime.nextSendAt()).isEqualTo(t0.plusMillis(1000));
    }

    @Test
    void idleTimeDoesNotBuildUpABurst() {
        OutboundRateLimiter.Slot afterIdle = OutboundRateLimiter.take(t0, 2, t0.plusSeconds(60));
        assertThat(afterIdle.nextSendAt()).isEqualTo(t0.plusSeconds(60).plusMillis(500));
    }
}
