package dev.hookrelay.server.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void isVersion7WithRfcVariant() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsSortInCreationOrderEvenWithinOneMillisecond() {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            created.add(Ids.newId());
        }
        assertThat(created).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void embedsTheTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long ts = id.getMostSignificantBits() >>> 16;
        assertThat(ts).isBetween(before, System.currentTimeMillis() + 1);
    }
}
