package dev.hookrelay.server.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48-bit Unix-ms timestamp, then random bits. IDs created in the same
 * millisecond use a 12-bit counter so they still sort in creation order.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastMillis = -1;
    private static int counter;

    private Ids() { }

    public static synchronized UUID newId() {
        long now = System.currentTimeMillis();
        if (now > lastMillis) {
            lastMillis = now;
            counter = RANDOM.nextInt(1 << 11); // leave headroom so the counter rarely overflows
        } else if (++counter > 0xFFF) {
            lastMillis++; // counter exhausted: borrow the next millisecond to stay monotonic
            counter = 0;
        }
        long msb = (lastMillis << 16) | (0x7L << 12) | counter;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
