package com.nabd.hms.shared;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * OPS-006 (DP-23): time-ordered UUID v7 (RFC 9562) for append-heavy tables, so btree inserts append instead of scattering.
 * 48-bit unix-ms timestamp, version 7, a 12-bit counter and 62 random bits. Ids from one JVM are strictly increasing
 * (the database default uuid_v7() covers other writers).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static long lastMs;
    private static int counter;

    private UuidV7() {
    }

    /** Monotonic counter method (RFC 9562 6.2): the 12 bits after the version count ids within one millisecond. */
    public static synchronized UUID next() {
        long ms = System.currentTimeMillis();
        if (ms > lastMs) {
            lastMs = ms;
            counter = RANDOM.nextInt(1 << 11); // random start leaves room for 2048+ ids in this millisecond
        } else if (++counter > 0xFFF) {
            lastMs++; // more than 4096 ids in one millisecond: borrow the next one rather than reorder
            counter = 0;
        }
        long hi = (lastMs << 16) | 0x7000L | counter;
        long lo = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo);
    }
}
