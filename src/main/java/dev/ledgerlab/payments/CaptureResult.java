package dev.ledgerlab.payments;

/** Names match the inbox result vocabulary; the caller owns inbox updates. */
public enum CaptureResult {
    APPLIED,
    DUPLICATE_FACT,
    BUSINESS_CONFLICT,
    INVALID_AMOUNT,
    UNSUPPORTED_CURRENCY,
    UNSUPPORTED_CAPTURE,
    LIVEMODE
}
