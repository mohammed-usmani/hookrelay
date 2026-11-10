package dev.hookrelay.server.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(List.of(Duration.ofSeconds(5), Duration.ofMinutes(5),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(5), Duration.ofHours(10),
            Duration.ofHours(10)));

    @Test
    void followsTheScheduleThenStops() {
        assertThat(policy.nextDelay(1)).contains(Duration.ofSeconds(5));
        assertThat(policy.nextDelay(2)).contains(Duration.ofMinutes(5));
        assertThat(policy.nextDelay(7)).contains(Duration.ofHours(10));
        assertThat(policy.nextDelay(8)).isEmpty();
    }

    @Test
    void maxAttemptsIsScheduleSizePlusOne() {
        assertThat(policy.maxAttempts()).isEqualTo(8);
    }
}
