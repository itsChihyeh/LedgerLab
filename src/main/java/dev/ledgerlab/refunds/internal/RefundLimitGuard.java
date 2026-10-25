package dev.ledgerlab.refunds.internal;

/** Package-local seam for the AC-3.13 lost-update mutant; no production switches. */
interface RefundLimitGuard {
    void reserve(String paymentId, long amount);
}
