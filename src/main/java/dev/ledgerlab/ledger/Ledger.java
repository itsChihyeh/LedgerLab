package dev.ledgerlab.ledger;

/**
 * The posting interface. Both methods join the caller's transaction and fail if there is none, so an entry and
 * the business state change it belongs to commit or roll back together.
 */
public interface Ledger {

    /**
     * Locks the single {@code ledger.funds_lock} row. Every money-moving transaction calls this first, before it
     * locks business rows or accounts, which fixes the lock order for the whole transaction (ADR-0004). Calling
     * it again in the same transaction is a no-op.
     */
    void lockFunds();

    /**
     * Records one complete entry: locks the touched accounts in ascending code order, checks that no account
     * that must stay non-negative goes below zero, writes the entry and its postings, and updates the balances.
     *
     * @throws IllegalStateException if there is no transaction or {@link #lockFunds()} was not called in it
     * @throws NegativeBalanceException if an account that may not go negative would; nothing is written
     * @throws DuplicateSourceKeyException if the source key is already recorded
     */
    PostedEntry post(EntryRequest request);
}
