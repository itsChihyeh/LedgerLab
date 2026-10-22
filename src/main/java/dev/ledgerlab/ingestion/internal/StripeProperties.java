package dev.ledgerlab.ingestion.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Constructor validation fails startup without including credential values in diagnostics. */
@ConfigurationProperties("ledgerlab.stripe")
public record StripeProperties(
        String webhookSecret, @Nullable String apiKey) {
    public StripeProperties {
        if (webhookSecret == null || !webhookSecret.startsWith("whsec_")) {
            throw new IllegalArgumentException(
                    "ledgerlab.stripe.webhook-secret is required and must start with whsec_");
        }
        if (apiKey != null && !apiKey.isEmpty() && !apiKey.startsWith("sk_test_") && !apiKey.startsWith("rk_test_")) {
            throw new IllegalArgumentException(
                    "ledgerlab.stripe.api-key must be a test-mode key; live keys are forbidden");
        }
    }

    @Override
    public String toString() {
        return "StripeProperties[credentials redacted]";
    }
}
