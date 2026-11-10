package dev.hookrelay.server.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboundRateLimiterTest {

    private final Instant t0 = Instant.parse("2026-11-10T10:00:00Z");

    @Test
    void reservesEvenlySpacedSlotsWithinTheHorizon() {
        OutboundRateLimiter.Slot first = OutboundRateLimiter.take(null, 2, t0);
        assertThat(first.reserved()).isTrue();
        assertThat(first.waitFor()).isZero();
        assertThat(first.nextSendAt()).isEqualTo(t0.plusMillis(500));

        // arrives early: still gets the next slot and simply waits for it
        OutboundRateLimiter.Slot second = OutboundRateLimiter.take(first.nextSendAt(), 2, t0.plusMillis(100));
        assertThat(second.reserved()).isTrue();
        assertThat(second.waitFor()).isEqualTo(Duration.ofMillis(400));
        assertThat(second.nextSendAt()).isEqualTo(t0.plusMillis(1000));
    }

    @Test
    void slotsBeyondTheHorizonAreNotReserved() {
        Instant busyUntil = t0.plusSeconds(5);
        OutboundRateLimiter.Slot later = OutboundRateLimiter.take(busyUntil, 2, t0);
        assertThat(later.reserved()).isFalse();
        assertThat(later.waitFor()).isEqualTo(Duration.ofSeconds(5));
        assertThat(later.nextSendAt()).isEqualTo(busyUntil);
    }

    @Test
    void idleTimeDoesNotBuildUpABurst() {
        OutboundRateLimiter.Slot afterIdle = OutboundRateLimiter.take(t0, 2, t0.plusSeconds(60));
        assertThat(afterIdle.waitFor()).isZero();
        assertThat(afterIdle.nextSendAt()).isEqualTo(t0.plusSeconds(60).plusMillis(500));
    }

    @Test
    void deferralSpreadsABacklogOverItsDrainTime() {
        // 40 waiting at 10/s drain in ~4s: a deferred delivery comes back somewhere in that window, not all at once
        assertThat(OutboundRateLimiter.deferFor(Duration.ofMillis(1100), 40, 10, 0.0)).isEqualTo(Duration.ofMillis(1100));
        assertThat(OutboundRateLimiter.deferFor(Duration.ofMillis(1100), 40, 10, 0.5)).isEqualTo(Duration.ofMillis(2000));
        assertThat(OutboundRateLimiter.deferFor(Duration.ofMillis(1100), 40, 10, 0.99)).isEqualTo(Duration.ofMillis(3960));
    }
}
