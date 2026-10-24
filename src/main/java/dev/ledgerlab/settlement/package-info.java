/** Settlement admission, matching and atomic batch posting. */
@NullMarked
@ApplicationModule(
        displayName = "Settlement",
        allowedDependencies = {"payments", "ledger", "reconciliation", "shared"})
package dev.ledgerlab.settlement;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
