package dev.hookrelay.server.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects the API from one noisy key. In memory, per instance: it only needs to be roughly
 * right, so the effective limit is the configured one times the number of instances.
 * ponytail: map grows with the number of distinct keys seen; fine for thousands of keys.
 */
class InboundRateLimiter extends OncePerRequestFilter {

    private final int perSecond;
    private final int burst;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    InboundRateLimiter(int perSecond, int burst) {
        this.perSecond = perSecond;
        this.burst = burst;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppPrincipal p) {
            Bucket bucket = buckets.computeIfAbsent(p.apiKeyId(), k -> Bucket.builder()
                    .addLimit(Bandwidth.builder().capacity(burst).refillGreedy(perSecond, Duration.ofSeconds(1)).build())
                    .build());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long seconds = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
                response.setHeader("Retry-After", Long.toString(seconds));
                ProblemWriter.write(response, 429, "rate-limited", "Too Many Requests",
                        "Rate limit exceeded for this API key");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
