package com.erp.platform.common.domain;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Minimal Crockford-base32 ULID generator (ADR-0001 D-G). 26 chars: 48-bit timestamp + 80-bit
 * randomness, lexicographically sortable by creation time. Used for every externally exposed
 * entity's {@code uid}.
 *
 * <p>Self-contained (no external ULID dependency) to keep the platform spine light. If we later
 * need monotonic-within-millisecond ordering, swap in a library implementation behind this API.
 */
public final class Ulid {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    /** A new ULID stamped with the current time. */
    public static String next() {
        return next(Instant.now().toEpochMilli());
    }

    /** A new ULID for an explicit epoch-millis timestamp (testable). */
    public static String next(long epochMilli) {
        char[] out = new char[26];

        // 48-bit timestamp → first 10 chars (10 * 5 = 50 bits, top 2 unused).
        long ts = epochMilli;
        for (int i = 9; i >= 0; i--) {
            out[i] = ENCODING[(int) (ts & 0x1F)];
            ts >>>= 5;
        }

        // 80 bits of randomness → last 16 chars.
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        int bitBuffer = 0;
        int bitCount = 0;
        int idx = 10;
        for (byte b : rnd) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                bitCount -= 5;
                out[idx++] = ENCODING[(bitBuffer >>> bitCount) & 0x1F];
            }
        }
        return new String(out);
    }
}
