package dev.ledgerlab.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.LedgerLabApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** AC-9.3: nothing else may call a demo action (the investigation agent in P8 included). */
class DemoModuleTest {
    private final ApplicationModules modules = ApplicationModules.of(LedgerLabApplication.class);

    @Test
    void ac_9_3_noModuleDependsOnDemo() {
        ApplicationModule demo = modules.getModuleByName("demo").orElseThrow();
        var dependents = modules.stream()
                .filter(module -> !module.getIdentifier().toString().equals("demo"))
                .filter(module -> module.getDirectDependencies(modules).contains(demo))
                .map(module -> module.getIdentifier().toString())
                .toList();
        assertThat(dependents).isEmpty();
    }

    @Test
    void ac_9_3_demoUsesOnlyTheOutboundHookAndTheSharedKernel() {
        ApplicationModule demo = modules.getModuleByName("demo").orElseThrow();
        var used = demo.getDirectDependencies(modules).stream()
                .map(dependency -> dependency.getTargetModule().getIdentifier().toString())
                .distinct()
                .sorted()
                .toList();
        assertThat(used).isSubsetOf("outbound", "shared");
    }
}
