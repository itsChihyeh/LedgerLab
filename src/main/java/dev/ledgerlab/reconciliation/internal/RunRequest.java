package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RunRequest(
        Kind kind, Instant cutoffAt, Instant evidenceAsOf, String ruleVersion, List<String> inputIds, boolean dryRun) {
    public enum Kind {
        PROCESSOR,
        BANK,
        AGING
    }

    public RunRequest {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(cutoffAt);
        Objects.requireNonNull(evidenceAsOf);
        if (ruleVersion.isBlank()) throw new IllegalArgumentException("ruleVersion must not be blank");
        inputIds = List.copyOf(inputIds);
        if (inputIds.stream().anyMatch(String::isBlank)
                || inputIds.stream().distinct().count() != inputIds.size()) {
            throw new IllegalArgumentException("inputIds must be nonblank and unique");
        }
    }
}
