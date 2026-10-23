package dev.ledgerlab.ingestion.internal;

/** Deliberately contains neither the supplied signature nor the secret. */
final class InvalidSignatureException extends RuntimeException {
    InvalidSignatureException() {
        super("Invalid Stripe signature");
    }
}
