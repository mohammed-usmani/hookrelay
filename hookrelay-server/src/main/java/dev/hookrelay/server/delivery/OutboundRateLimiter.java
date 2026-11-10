package dev.hookrelay.server.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Per-endpoint send pacing, exact across every instance: the "next allowed send" time lives on
 * the endpoint row, which the claim step already holds locked. At N per second, sends are
 * spaced 1/N s apart; idle time does not bank a burst.
 */
final class OutboundRateLimiter {

    /** {@code delay} is empty if the send may go now; {@code nextSendAt} is the value to store. */
    record Slot(Optional<Duration> delay, Instant nextSendAt) { }

    private OutboundRateLimiter() { }

    static Slot take(Instant nextSendAt, int perSecond, Instant now) {
        Duration interval = Duration.ofNanos(1_000_000_000L / perSecond);
        if (nextSendAt != null && now.isBefore(nextSendAt)) {
            return new Slot(Optional.of(Duration.between(now, nextSendAt)), nextSendAt);
        }
        return new Slot(Optional.empty(), now.plus(interval));
    }
}
