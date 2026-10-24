package dev.ledgerlab.shared;

/** Pure contract fee calculation in integer cents (contracts section 3). */
public final class Fees {
    private Fees() {}

    /** Callers validate payment limits; overflow in any intermediate calculation is rejected. */
    public static long contractFee(long gross) {
        long roundedPercentage = Math.addExact(Math.multiplyExact(gross, 29), 500) / 1000;
        return Math.addExact(30, roundedPercentage);
    }
}
