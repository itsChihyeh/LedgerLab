package dev.ledgerlab.payouts;

import java.time.Instant;

public record PayoutResult(
        String payoutId,
        String status,
        long amount,
        String merchantPayoutRef,
        Instant reservedAt,
        String bankRequestId) {}
