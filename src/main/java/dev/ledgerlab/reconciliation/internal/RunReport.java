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
        Map<String, Long> summary,
        List<ObservedFinding> findings) {
    public RunReport {
        inputHashes = Map.copyOf(inputHashes);
        summary = Map.copyOf(summary);
        findings = List.copyOf(findings);
    }

    public record ObservedFinding(Finding finding, String fingerprint) {}
}
