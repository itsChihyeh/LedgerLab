package dev.ledgerlab;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Module boundaries (ADR-0013). A failure here is a violation to fix in the code, never a reason to loosen the
 * rules (AGENTS.md).
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(LedgerLabApplication.class);

    @Test
    void moduleBoundariesHold() {
        modules.verify();
    }
}
