@NullMarked
@ApplicationModule(
        displayName = "Refunds",
        allowedDependencies = {"ledger", "payments", "shared", "reconciliation", "outbound"})
package dev.ledgerlab.refunds;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
