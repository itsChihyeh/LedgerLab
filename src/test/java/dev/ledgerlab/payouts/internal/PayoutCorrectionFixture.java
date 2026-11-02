package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.payouts.Payouts;
import dev.ledgerlab.refunds.internal.CorrectionFixture;

/** Reuse the real payout service without exposing its internal implementation. */
public final class PayoutCorrectionFixture {
    private PayoutCorrectionFixture() {}

    public static Payouts payouts(CorrectionFixture f) {
        return new JdbcPayouts(f.jdbc, f.ledger, f.clock, f.ids, f.mapper, f.manager);
    }
}
