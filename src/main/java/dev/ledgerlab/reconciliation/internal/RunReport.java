package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record RunReport(
        @Nullable String id,
        RunRequest.Kind kind,
        String status,
        boolean dryRun,
        Instant cutoffAt,
        Instant evidenceAsOf,
        String ruleVersion,
        Map<String, String> inputHashes,
        Map<String, Object> summary,
        List<ObservedFinding> findings) {
    public RunReport {
        inputHashes = Map.copyOf(inputHashes);
        summary = Map.copyOf(summary);
        findings = List.copyOf(findings);
    }

    public record ObservedFinding(Finding finding, String fingerprint) {}

    public List<String> getLimitations() {
        if (kind == RunRequest.Kind.PROCESSOR && ruleVersion.equals("processor-v1"))
            return List.of(
                    "P2 ignores refunded payments and REFUND lines; mixed refund batch totals require P3.",
                    "Expired missing payments are checked by AGING, not this processor run.");
        return kind == RunRequest.Kind.PROCESSOR
                ? List.of("Expired missing payments are checked by AGING, not this processor run.")
                : List.of();
    }
}
