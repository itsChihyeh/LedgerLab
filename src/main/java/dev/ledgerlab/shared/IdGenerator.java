package dev.ledgerlab.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Random;
import java.util.random.RandomGenerator;

/**
 * Platform identifiers: {@code prefix_} followed by a 26-character ULID (contracts section 2.1).
 *
 * <p>Business code never calls the system clock or a random source directly. Tests and the empty-database
 * rebuild use {@link #seeded}, so the same input sequence produces the same identifiers (AC-4.13).
 */
public final class IdGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final Clock clock;
    private final RandomGenerator random;

    private IdGenerator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    public static IdGenerator secure(Clock clock) {
        return new IdGenerator(clock, new SecureRandom());
    }

    public static IdGenerator seeded(Clock clock, long seed) {
        return new IdGenerator(clock, new Random(seed));
    }

    /** Returns {@code prefix + "_" + ULID}; the prefix names the object type, for example {@code je}. */
    public synchronized String next(String prefix) {
        long millis = clock.millis();
        long randomHigh = random.nextLong() & 0xFFFFL; // 16 bits
        long randomLow = random.nextLong(); // 64 bits
        char[] out = new char[26];
        // 48-bit timestamp in the first 10 characters (the top 2 bits of the first one are always 0).
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (millis & 31)];
            millis >>>= 5;
        }
        // 80 random bits in the last 16 characters: 16 high bits then 64 low bits, 5 bits per character.
        for (int i = 25; i >= 10; i--) {
            out[i] = CROCKFORD[(int) (randomLow & 31)];
            randomLow = (randomLow >>> 5) | ((randomHigh & 31) << 59);
            randomHigh >>>= 5;
        }
        return prefix + "_" + new String(out);
    }
}
