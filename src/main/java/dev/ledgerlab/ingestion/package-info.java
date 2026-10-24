/** Raw webhook authentication and durable inbox intake; business processing is separate. */
@NullMarked
@ApplicationModule(
        displayName = "Ingestion",
        allowedDependencies = {"payments", "settlement", "reconciliation", "shared"})
package dev.ledgerlab.ingestion;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
