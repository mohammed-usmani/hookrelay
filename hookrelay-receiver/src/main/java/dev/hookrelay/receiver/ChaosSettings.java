package dev.hookrelay.receiver;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Runtime knobs for misbehaving on purpose, plus the signing secret to verify with. */
@Component
public class ChaosSettings {

    /** All optional: an empty body to {@code PUT /chaos} means "behave". */
    public record Chaos(Double failureRate, Integer forceStatus, Integer addedLatencyMs) {
        static final Chaos NONE = new Chaos(0.0, null, 0);

        public Chaos {
            failureRate = failureRate == null ? 0.0 : failureRate;
            addedLatencyMs = addedLatencyMs == null ? 0 : addedLatencyMs;
        }
    }

    private volatile Chaos chaos = Chaos.NONE;
    private volatile String secret;

    public ChaosSettings(@Value("${WEBHOOK_SECRET:}") String secret) {
        this.secret = secret == null || secret.isBlank() ? null : secret;
    }

    public Chaos chaos() {
        return chaos;
    }

    public void chaos(Chaos c) {
        if (c.failureRate() < 0 || c.failureRate() > 1) {
            throw new IllegalArgumentException("failureRate must be between 0 and 1");
        }
        this.chaos = c;
    }

    public void secret(String secret) {
        new WebhookVerifier(secret, Clock.systemUTC()); // fail fast on a malformed secret
        this.secret = secret;
    }

    /** Null until a secret is configured. */
    public WebhookVerifier verifier(Clock clock) {
        String s = secret;
        return s == null ? null : new WebhookVerifier(s, clock);
    }
}
