/** Capture admission and payment facts, recorded atomically with the ledger. */
@NullMarked
@ApplicationModule(
        displayName = "Payments",
        allowedDependencies = {"ledger", "reconciliation", "shared"})
package dev.ledgerlab.payments;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
