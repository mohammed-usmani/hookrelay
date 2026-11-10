package dev.hookrelay.server.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hookrelay.server.endpoint.Circuit;
import dev.hookrelay.server.endpoint.CircuitState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    private final CircuitBreaker breaker = new CircuitBreaker(10,
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(1)), Duration.ofSeconds(16));
    private final Instant t0 = Instant.parse("2026-11-10T10:00:00Z");

    private Circuit failTimes(Circuit c, int n) {
        for (int i = 0; i < n; i++) {
            c = breaker.onFailure(c, t0);
        }
        return c;
    }

    @Test
    void opensOnTheTenthConsecutiveFailure() {
        Circuit nine = failTimes(Circuit.CLOSED, 9);
        assertThat(nine.state()).isEqualTo(CircuitState.CLOSED);
        Circuit ten = breaker.onFailure(nine, t0);
        assertThat(ten.state()).isEqualTo(CircuitState.OPEN);
        assertThat(ten.openUntil()).isEqualTo(t0.plus(Duration.ofMinutes(5)));
        assertThat(ten.firstFailureAt()).isEqualTo(t0);
    }

    @Test
    void openCircuitDefersUntilCooldownThenAllowsOneProbe() {
        Circuit open = failTimes(Circuit.CLOSED, 10);
        CircuitBreaker.Decision before = breaker.admit(open, t0.plus(Duration.ofMinutes(1)));
        assertThat(before.admission()).isEqualTo(CircuitBreaker.Admission.DEFER);
        assertThat(before.delay()).isEqualTo(Duration.ofMinutes(4));

        CircuitBreaker.Decision probe = breaker.admit(open, t0.plus(Duration.ofMinutes(6)));
        assertThat(probe.admission()).isEqualTo(CircuitBreaker.Admission.ALLOW_PROBE);
        assertThat(probe.next().state()).isEqualTo(CircuitState.HALF_OPEN);

        CircuitBreaker.Decision second = breaker.admit(probe.next(), t0.plus(Duration.ofMinutes(6)));
        assertThat(second.admission()).isEqualTo(CircuitBreaker.Admission.DEFER);
    }

    @Test
    void successfulProbeClosesTheCircuit() {
        Circuit probing = breaker.admit(failTimes(Circuit.CLOSED, 10), t0.plus(Duration.ofMinutes(6))).next();
        Circuit closed = breaker.onSuccess(probing, t0.plus(Duration.ofMinutes(6)));
        assertThat(closed.state()).isEqualTo(CircuitState.CLOSED);
        assertThat(closed.failures()).isZero();
        assertThat(closed.level()).isZero();
        assertThat(closed.firstFailureAt()).isNull();
        assertThat(closed.lastSuccessAt()).isEqualTo(t0.plus(Duration.ofMinutes(6)));
    }

    @Test
    void failedProbesBackOff30mThen1hThen1h() {
        Circuit c = failTimes(Circuit.CLOSED, 10);
        Instant now = t0;
        Duration[] expected = {Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(1)};
        for (Duration cooldown : expected) {
            now = c.openUntil().plusSeconds(1);
            c = breaker.admit(c, now).next();
            c = breaker.onFailure(c, now);
            assertThat(c.state()).isEqualTo(CircuitState.OPEN);
            assertThat(c.openUntil()).isEqualTo(now.plus(cooldown));
        }
    }

    @Test
    void aProbeThatNeverReportsBackIsReplacedAfterTheProbeTimeout() {
        Circuit probing = breaker.admit(failTimes(Circuit.CLOSED, 10), t0.plus(Duration.ofMinutes(6))).next();
        CircuitBreaker.Decision later = breaker.admit(probing, t0.plus(Duration.ofMinutes(6)).plusSeconds(17));
        assertThat(later.admission()).isEqualTo(CircuitBreaker.Admission.ALLOW_PROBE);
    }

    @Test
    void successResetsTheFailureCount() {
        Circuit c = breaker.onSuccess(failTimes(Circuit.CLOSED, 9), t0);
        assertThat(breaker.onFailure(c, t0).state()).isEqualTo(CircuitState.CLOSED);
    }
}
