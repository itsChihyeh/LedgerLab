/** Durable command delivery; business modules implement its handler SPI. */
@NullMarked
@ApplicationModule(
        displayName = "Outbound",
        allowedDependencies = {})
package dev.ledgerlab.outbound;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
