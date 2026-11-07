package dev.ledgerlab.ledger;

import dev.ledgerlab.ledger.internal.JdbcLedgerAudit;
import java.util.List;
import javax.sql.DataSource;

/**
 * Recomputes the books from the postings and compares them with what is stored (INV-1 to INV-5, INV-8).
 * Reads everything in one {@code REPEATABLE READ} snapshot, so concurrent posting cannot produce false alarms.
 * Used after every test scenario and, later, by a scheduled job.
 */
public interface LedgerAudit {

    /** Runs the same checks through existing views using the caller's readonly credentials. */
    static LedgerAudit readonly(DataSource dataSource) {
        return JdbcLedgerAudit.readonly(dataSource);
    }

    /** Returns every violation found; an empty list means the books are consistent. */
    List<InvariantViolation> check();

    /** One broken invariant, for example {@code INV-2} with the account and the two balances. */
    record InvariantViolation(String invariant, String detail) {}
}
