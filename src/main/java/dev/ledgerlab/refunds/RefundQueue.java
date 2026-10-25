package dev.ledgerlab.refunds;

public interface RefundQueue {
    /** Releases the longest covered FIFO prefix. Requires the caller's transaction and funds lock. */
    void release();
}
