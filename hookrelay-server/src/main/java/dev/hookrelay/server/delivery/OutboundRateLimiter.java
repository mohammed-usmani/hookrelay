package dev.hookrelay.server.delivery;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-endpoint send pacing, exact across every instance: the next free send slot lives on the
 * endpoint row, which the claim step already holds locked. At N per second, slots are 1/N s
 * apart and idle time does not bank a burst.
 *
 * A claim reserves the next slot if it is within {@link #HORIZON}; the worker then waits for it
 * (at most a second) and sends. Slots further out are not reserved: the delivery is deferred
 * through a delay queue, spread over the time the current backlog needs to drain, so a backlog
 * neither hammers the queue nor comes back all at once.
 */
final class OutboundRateLimiter {

    static final Duration HORIZON = Duration.ofSeconds(1);

    /** {@code reserved}: send after {@code waitFor}. Otherwise defer. {@code nextSendAt}: value to store. */
    record Slot(boolean reserved, Duration waitFor, Instant nextSendAt) { }

    private OutboundRateLimiter() { }

    static Slot take(Instant nextSendAt, int perSecond, Instant now) {
        Instant slot = nextSendAt == null || nextSendAt.isBefore(now) ? now : nextSendAt;
        Duration wait = Duration.between(now, slot);
        if (wait.compareTo(HORIZON) > 0) {
            return new Slot(false, wait, nextSendAt);
        }
        return new Slot(true, wait, slot.plus(interval(perSecond)));
    }

    /**
     * How long to defer a delivery that found no slot: at least until the free slots run out,
     * plus a random share ({@code random} in [0,1)) of the time {@code backlog} waiting deliveries need.
     */
    static Duration deferFor(Duration minimum, long backlog, int perSecond, double random) {
        Duration spread = interval(perSecond).multipliedBy(backlog);
        Duration pick = Duration.ofNanos((long) (spread.toNanos() * random));
        return pick.compareTo(minimum) > 0 ? pick : minimum;
    }

    private static Duration interval(int perSecond) {
        return Duration.ofNanos(1_000_000_000L / perSecond);
    }
}
