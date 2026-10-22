package dev.ledgerlab.payments;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Parsed Charge evidence; malformed or absent monetary fields are represented by null. */
public record ChargeEvidence(
        String eventId,
        String chargeId,
        @Nullable String paymentIntentId,
        @Nullable Long amount,
        @Nullable Long amountCaptured,
        @Nullable String currency,
        @Nullable Boolean captured,
        @Nullable String status,
        boolean livemode,
        Instant created) {}
