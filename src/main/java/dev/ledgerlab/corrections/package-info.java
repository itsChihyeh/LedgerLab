/** Restricted operator corrections, committed atomically with business state and issue dispositions. */
@NullMarked
@ApplicationModule(
        displayName = "Corrections",
        allowedDependencies = {"ledger", "payments", "settlement", "reconciliation", "shared"})
package dev.ledgerlab.corrections;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
