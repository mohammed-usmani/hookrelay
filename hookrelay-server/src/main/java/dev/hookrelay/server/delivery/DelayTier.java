package dev.hookrelay.server.delivery;

import java.time.Duration;

/**
 * Fixed delays, one RabbitMQ queue each (per-queue TTL, so a long delay never blocks a short
 * one behind it). Retries and deferrals round up to the nearest tier.
 */
public enum DelayTier {
    S1("1s", Duration.ofSeconds(1)),
    S5("5s", Duration.ofSeconds(5)),
    S30("30s", Duration.ofSeconds(30)),
    M5("5m", Duration.ofMinutes(5)),
    M30("30m", Duration.ofMinutes(30)),
    H1("1h", Duration.ofHours(1)),
    H2("2h", Duration.ofHours(2)),
    H5("5h", Duration.ofHours(5)),
    H10("10h", Duration.ofHours(10));

    private final String key;
    private final Duration duration;

    DelayTier(String key, Duration duration) {
        this.key = key;
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    public String routingKey() {
        return key;
    }

    public String queueName() {
        return "deliveries.delay." + key;
    }

    /** Smallest tier at least {@code wait} long; waits beyond 10h use the 10h tier. */
    public static DelayTier atLeast(Duration wait) {
        for (DelayTier t : values()) {
            if (t.duration.compareTo(wait) >= 0) {
                return t;
            }
        }
        return H10;
    }

    public static DelayTier fromKey(String key) {
        for (DelayTier t : values()) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown delay tier: " + key);
    }
}
