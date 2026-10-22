package dev.ledgerlab.ledger;

import java.util.List;

/**
 * Recomputes the books from the postings and compares them with what is stored (INV-1 to INV-5, INV-8).
 * Reads everything in one {@code REPEATABLE READ} snapshot, so concurrent posting cannot produce false alarms.
 * Used after every test scenario and, later, by a scheduled job.
 */
public interface LedgerAudit {

    /** Returns every violation found; an empty list means the books are consistent. */
    List<InvariantViolation> check();

    /** One broken invariant, for example {@code INV-2} with the account and the two balances. */
    record InvariantViolation(String invariant, String detail) {}
}
