@NullMarked
@ApplicationModule(
        displayName = "Payouts",
        allowedDependencies = {"ledger", "shared", "outbound", "refunds", "reconciliation"})
package dev.ledgerlab.payouts;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
