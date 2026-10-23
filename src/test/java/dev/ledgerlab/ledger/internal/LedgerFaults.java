package dev.ledgerlab.ledger.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import javax.sql.DataSource;

/** Test-only access to the package-local posting fault injection constructor. */
public final class LedgerFaults {
    private LedgerFaults() {}

    public static Ledger afterFirstPosting(DataSource source, Clock clock, IdGenerator ids, Runnable hook) {
        return new JdbcLedger(source, clock, ids, hook);
    }
}
