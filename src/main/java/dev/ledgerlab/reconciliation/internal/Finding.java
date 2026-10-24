package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** JSON values use integer numbers only; null evidence values are represented by JSON null. */
public record Finding(
        String category,
        String subjectType,
        String subjectId,
        String expected,
        String actual,
        String evidenceRefs,
        @Nullable Instant dueAt) {
    public Finding {
        if (category.isBlank() || subjectType.isBlank() || subjectId.isBlank()) {
            throw new IllegalArgumentException("Finding identity must not be blank");
        }
        if (category.equals("TIMING_DIFFERENCE") != (dueAt != null)) {
            throw new IllegalArgumentException("Only timing differences require dueAt");
        }
    }

    public Key key() {
        return new Key(category, subjectType, subjectId);
    }

    public record Key(String category, String subjectType, String subjectId) {}
}
