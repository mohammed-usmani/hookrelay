package dev.hookrelay.server.common;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Times {

    private Times() { }

    /** Now, truncated to the database's microsecond precision so values round-trip exactly. */
    public static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
