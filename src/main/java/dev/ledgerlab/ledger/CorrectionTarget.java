package dev.ledgerlab.ledger;

import java.time.Instant;

public record CorrectionTarget(String correctionId, String entryId, Role role, Instant recordedAt) {
    public enum Role {
        REVERSED,
        APPLIED
    }
}
