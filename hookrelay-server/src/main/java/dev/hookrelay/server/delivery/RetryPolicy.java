package dev.hookrelay.server.delivery;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** How long to wait before the next attempt, given how many attempts have been made. */
public class RetryPolicy {

    private final List<Duration> schedule;

    public RetryPolicy(List<Duration> schedule) {
        this.schedule = List.copyOf(schedule);
    }

    public Optional<Duration> nextDelay(int attemptsSoFar) {
        int i = attemptsSoFar - 1;
        return i >= 0 && i < schedule.size() ? Optional.of(schedule.get(i)) : Optional.empty();
    }

    public int maxAttempts() {
        return schedule.size() + 1;
    }
}
