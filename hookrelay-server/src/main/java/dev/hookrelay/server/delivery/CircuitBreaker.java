package dev.hookrelay.server.delivery;

import dev.hookrelay.server.endpoint.Circuit;
import dev.hookrelay.server.endpoint.CircuitState;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Stops hammering a receiver that keeps failing. CLOSED: send normally. After N consecutive
 * failures → OPEN: hold deliveries back for a cooldown. Cooldown over → HALF_OPEN: let exactly
 * one probe through. Probe succeeds → CLOSED; fails → OPEN again with a longer cooldown.
 * Pure functions over {@link Circuit}; the caller persists the result under row lock, so all
 * instances share one state.
 */
public class CircuitBreaker {

    public enum Admission { ALLOW, ALLOW_PROBE, DEFER }

    public record Decision(Admission admission, Duration delay, Circuit next) { }

    private final int threshold;
    private final List<Duration> cooldowns;
    private final Duration probeTimeout;

    public CircuitBreaker(int threshold, List<Duration> cooldowns, Duration probeTimeout) {
        this.threshold = threshold;
        this.cooldowns = List.copyOf(cooldowns);
        this.probeTimeout = probeTimeout;
    }

    public Decision admit(Circuit c, Instant now) {
        if (c.state() == CircuitState.CLOSED) {
            return new Decision(Admission.ALLOW, Duration.ZERO, c);
        }
        if (now.isBefore(c.openUntil())) {
            // OPEN and cooling down, or HALF_OPEN with a probe still in flight
            return new Decision(Admission.DEFER, Duration.between(now, c.openUntil()), c);
        }
        Circuit probing = new Circuit(CircuitState.HALF_OPEN, c.failures(), now.plus(probeTimeout), c.level(),
                c.lastSuccessAt(), c.firstFailureAt());
        return new Decision(Admission.ALLOW_PROBE, Duration.ZERO, probing);
    }

    public Circuit onSuccess(Circuit c, Instant now) {
        return new Circuit(CircuitState.CLOSED, 0, null, 0, now, null);
    }

    public Circuit onFailure(Circuit c, Instant now) {
        int failures = c.failures() + 1;
        Instant firstFailure = c.firstFailureAt() == null ? now : c.firstFailureAt();
        return switch (c.state()) {
            case HALF_OPEN -> {
                int level = Math.min(c.level() + 1, cooldowns.size() - 1);
                yield new Circuit(CircuitState.OPEN, failures, now.plus(cooldowns.get(level)), level,
                        c.lastSuccessAt(), firstFailure);
            }
            case OPEN -> new Circuit(CircuitState.OPEN, failures, c.openUntil(), c.level(), c.lastSuccessAt(),
                    firstFailure); // a late result from before the circuit opened
            case CLOSED -> failures >= threshold
                    ? new Circuit(CircuitState.OPEN, failures, now.plus(cooldowns.get(c.level())), c.level(),
                            c.lastSuccessAt(), firstFailure)
                    : new Circuit(CircuitState.CLOSED, failures, null, c.level(), c.lastSuccessAt(), firstFailure);
        };
    }
}
