package dev.ledgerlab.ledger;

import java.time.Instant;
import java.util.List;

public record CorrectionDetails(
        String correctionId,
        CorrectionType type,
        CorrectionReason reasonCode,
        String reasonNote,
        String evidenceRefs,
        String params,
        SubjectType subjectType,
        String subjectId,
        String requestedBy,
        Instant recordedAt,
        List<CorrectionTarget> targets,
        List<CorrectionEntry> entries) {
    public CorrectionDetails {
        targets = List.copyOf(targets);
        entries = List.copyOf(entries);
    }
}
