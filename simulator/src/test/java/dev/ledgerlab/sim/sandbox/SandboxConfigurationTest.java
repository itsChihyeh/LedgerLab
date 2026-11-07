package dev.ledgerlab.sim.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SandboxConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SandboxStripe.class)
            .withPropertyValues(
                    "spring.profiles.active=sandbox",
                    "sim.sandbox.run-id=sbx_config",
                    "sim.sandbox.window-start=2026-11-02T00:00:00Z",
                    "sim.sandbox.stripe-api-key=" + "rk_test_" + "synthetic");

    @ParameterizedTest
    @ValueSource(strings = {"sk_live_", "rk_live_", "", "invalid_"})
    void ac_8_2_rejectsUnsafeKeysWithoutLeakingValues(String prefix) {
        String key = prefix + "privateSyntheticValue";
        context.withPropertyValues("sim.sandbox.stripe-api-key=" + key).run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseMessage("Sandbox requires a test-mode API key");
            var trace = new java.io.StringWriter();
            java.util.Objects.requireNonNull(c.getStartupFailure()).printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain(key);
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sim.sandbox.run-id=",
                "sim.sandbox.run-id=wrong",
                "sim.sandbox.window-start=",
                "sim.sandbox.window-start=bad"
            })
    void ac_8_2_missingRunAndWindowFailStartup(String property) {
        context.withPropertyValues(property).run(c -> assertThat(c).hasFailed());
    }

    @Test
    void ac_8_2_validSandboxConfigurationAndDefaultIsolation() {
        context.run(c -> assertThat(c).hasSingleBean(SandboxStripe.class));
        new ApplicationContextRunner()
                .withUserConfiguration(SandboxStripe.class)
                .run(c -> assertThat(c).doesNotHaveBean(SandboxStripe.class).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bad URL",
                "ftp://localhost",
                "http://user:pass@localhost",
                "http://localhost?secret=value",
                "http://localhost#fragment"
            })
    void ac_8_2_invalidBaseUrlFailsWithoutEchoingValue(String url) {
        context.withPropertyValues("sim.sandbox.stripe-base-url=" + url).run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).hasRootCauseMessage("Invalid sandbox Stripe base URL");
        });
    }
}
