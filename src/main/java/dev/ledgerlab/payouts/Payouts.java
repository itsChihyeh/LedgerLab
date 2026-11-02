package dev.ledgerlab.payouts;

public interface Payouts {
    /** Starts or joins a transaction; replays the permanent initial result for the same business ref. */
    PayoutResult reserve(long amount, String merchantPayoutRef, String fingerprint);
}
