package dev.ledgerlab.ledger;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Immutable journal evidence, including individual postings rather than only their totals. */
public record CorrectionEntry(
        String entryId,
        long seq,
        EntryType type,
        String sourceKey,
        Instant occurredAt,
        Instant recordedAt,
        SubjectType subjectType,
        String subjectId,
        @Nullable String reversesEntryId,
        @Nullable String correctionId,
        List<Posting> postings) {
    public CorrectionEntry {
        postings = List.copyOf(postings);
    }

    public record Posting(
            String postingId,
            int lineNo,
            Account account,
            Side side,
            long amount,
            long accountVersion,
            long balanceAfter,
            Instant recordedAt) {}
}
