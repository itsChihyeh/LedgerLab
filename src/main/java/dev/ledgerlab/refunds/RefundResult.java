package dev.ledgerlab.refunds;

public record RefundResult(String refundId, String status, long amount, long queueSeq) {}
