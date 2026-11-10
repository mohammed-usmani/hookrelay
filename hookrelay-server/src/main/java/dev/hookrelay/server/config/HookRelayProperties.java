package dev.hookrelay.server.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** All HookRelay settings, bound from {@code hookrelay.*} (env: {@code HOOKRELAY_*}). */
@Validated
@ConfigurationProperties("hookrelay")
public record HookRelayProperties(
        @NotBlank String adminToken,
        @NotBlank String encryptionKey,
        @DefaultValue Ssrf ssrf,
        @DefaultValue Delivery delivery,
        @DefaultValue Circuit circuit,
        @DefaultValue RateLimit rateLimit) {

    /**
     * Outbound URL safety. Both switches exist for local development and tests only and must
     * stay off in production.
     */
    public record Ssrf(
            @DefaultValue("false") boolean allowHttp,
            @DefaultValue List<String> allowedCidrs) { }

    /** Delivery behaviour. */
    public record Delivery(
            /* Hard deadline for each delivery, counted from creation (or replay). */
            @DefaultValue("3d") Duration window,
            /* Waits between attempts; attempts = schedule size + 1. */
            @DefaultValue("5s,5m,30m,2h,5h,10h,10h") List<Duration> retrySchedule,
            @DefaultValue("5s") Duration connectTimeout,
            /* Cap on one whole HTTP exchange. */
            @DefaultValue("15s") Duration sendTimeout,
            @DefaultValue("8") int consumerConcurrency,
            @DefaultValue("10") int prefetch) { }

    /** Per-endpoint circuit breaker. Cooldowns are used in order, the last one repeats. */
    public record Circuit(
            @DefaultValue("10") int failureThreshold,
            @DefaultValue("5m,30m,1h") List<Duration> cooldowns,
            /* An endpoint with no success for this long is disabled. */
            @DefaultValue("3d") Duration disableAfter) { }

    /** Inbound API limits, per API key and per instance. */
    public record RateLimit(
            @DefaultValue("100") int inboundPerSecond,
            @DefaultValue("200") int inboundBurst) { }
}
