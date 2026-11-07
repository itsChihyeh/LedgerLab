package dev.ledgerlab.testsupport;

import dev.ledgerlab.ledger.internal.LedgerInvariantCheck;
import dev.ledgerlab.payments.internal.PaymentInvariantCheck;
import dev.ledgerlab.payouts.internal.PayoutInvariantCheck;
import dev.ledgerlab.reconciliation.internal.BankInvariantCheck;
import dev.ledgerlab.refunds.internal.RefundInvariantCheck;
import dev.ledgerlab.settlement.internal.SettlementInvariantCheck;
import dev.ledgerlab.shared.InvariantCheck;
import java.util.List;

/** The checks every module contributes, assembled by hand for tests that do not start a Spring context. */
public final class InvariantChecks {
    private InvariantChecks() {}

    public static List<InvariantCheck> all() {
        return List.of(
                new LedgerInvariantCheck(),
                new PaymentInvariantCheck(),
                new RefundInvariantCheck(),
                new PayoutInvariantCheck(),
                new SettlementInvariantCheck(),
                new BankInvariantCheck());
    }
}
