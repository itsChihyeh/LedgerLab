package dev.ledgerlab.refunds;

public interface Refunds {
    /** Starts or joins a transaction. The caller supplies the canonical request fingerprint (T05). */
    RefundResult accept(String paymentId, String merchantRefundRef, long amount, String requestFingerprint);

    RefundResult cancel(String refundId, String reason);
}
