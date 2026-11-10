package dev.hookrelay.server.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DelayTierTest {

    @Test
    void picksTheSmallestTierThatIsLongEnough() {
        assertThat(DelayTier.atLeast(Duration.ZERO)).isEqualTo(DelayTier.S1);
        assertThat(DelayTier.atLeast(Duration.ofSeconds(3))).isEqualTo(DelayTier.S5);
        assertThat(DelayTier.atLeast(Duration.ofMinutes(5))).isEqualTo(DelayTier.M5);
        assertThat(DelayTier.atLeast(Duration.ofMinutes(6))).isEqualTo(DelayTier.M30);
        assertThat(DelayTier.atLeast(Duration.ofHours(20))).isEqualTo(DelayTier.H10);
    }

    @Test
    void queueNamesAreStable() {
        assertThat(DelayTier.S5.queueName()).isEqualTo("deliveries.delay.5s");
        assertThat(DelayTier.H10.queueName()).isEqualTo("deliveries.delay.10h");
        assertThat(DelayTier.M30.routingKey()).isEqualTo("30m");
    }
}
