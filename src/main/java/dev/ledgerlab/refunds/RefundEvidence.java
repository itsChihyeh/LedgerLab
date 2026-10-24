package dev.ledgerlab.refunds;

/** Joins the caller's transaction. All processor evidence enters here. */
public interface RefundEvidence {
    Result apply(ProcessorRefundFact fact);

    enum Result {
        APPLIED,
        DUPLICATE_FACT,
        CONFLICT
    }
}
