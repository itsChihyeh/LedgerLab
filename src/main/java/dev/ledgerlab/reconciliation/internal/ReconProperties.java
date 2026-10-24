package dev.ledgerlab.reconciliation.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerlab.recon")
public record ReconProperties(
        @DefaultValue("60s") Duration evidenceGrace,
        @DefaultValue("p3-v1") String ruleVersion) {
    public ReconProperties {
        if (evidenceGrace.isNegative() || evidenceGrace.isZero() || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("Reconciliation requires positive evidence grace and a rule version");
        }
    }
}
