package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.StripeSignatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class StripePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StripeProperties.class)
    static class Config {}

    @ParameterizedTest
    @ValueSource(strings = {"sk_live_fake_only", "rk_live_fake_only", "invalid_fake_only"})
    void ac_8_2_nonTestKeysPreventStartupWithoutLeakingValue(String key) {
        runner.withPropertyValues(
                        "ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET, "ledgerlab.stripe.api-key=" + key)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("test-mode")
                            .hasStackTraceContaining("ledgerlab.stripe.api-key");
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("IllegalArgumentException");
                    assertThat(stackTrace(context.getStartupFailure())).doesNotContain(key);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sk_test_fake_only", "rk_test_fake_only"})
    void ac_8_2_testKeysAndEmptyKeyStart(String key) {
        runner.withPropertyValues(
                        "ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET, "ledgerlab.stripe.api-key=" + key)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ac_8_2_apiKeyMayBeAbsent() {
        runner.withPropertyValues("ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ac_8_2_webhookSecretIsRequired() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid_secret_only"})
    void ac_8_2_invalidWebhookSecretPreventsStartup(String secret) {
        runner.withPropertyValues("ledgerlab.stripe.webhook-secret=" + secret).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("whsec_");
        });
    }

    private static String stackTrace(@org.jspecify.annotations.Nullable Throwable failure) {
        java.io.StringWriter writer = new java.io.StringWriter();
        java.util.Objects.requireNonNull(failure).printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
