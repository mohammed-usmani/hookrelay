package dev.hookrelay.server.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorTest {

    @Test
    void roundTrips() {
        Instant t = Instant.parse("2026-11-10T10:15:30.123456Z");
        UUID id = Ids.newId();
        Cursor.Position p = Cursor.decode(Cursor.encode(t, id));
        assertThat(p.createdAt()).isEqualTo(t);
        assertThat(p.id()).isEqualTo(id);
    }

    @Test
    void garbageIsRejectedAsBadRequest() {
        assertThatThrownBy(() -> Cursor.decode("garbage"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(400);
    }
}
