package dev.ledgerlab.sim.processor;

import java.util.HexFormat;
import java.util.Random;

/** Reproducible external identifiers; deliberately not a shared application generator. */
public final class SeededIds {
    private final Random random;

    public SeededIds(long seed) {
        random = new Random(seed);
    }

    public synchronized String next(String prefix) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return prefix + "_" + HexFormat.of().formatHex(bytes);
    }
}
