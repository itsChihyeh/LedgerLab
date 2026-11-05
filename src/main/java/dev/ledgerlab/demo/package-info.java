/**
 * The public break-it demo (ADR-0017): a fixed whitelist of scenario actions, live evidence over SSE and
 * per-day aggregate counts. Every bean is registered only under the {@code demo} profile, and no other module
 * may depend on this one.
 */
@NullMarked
@ApplicationModule(
        displayName = "Demo",
        allowedDependencies = {"outbound", "shared"})
package dev.ledgerlab.demo;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
