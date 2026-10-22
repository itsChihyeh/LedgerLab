package dev.ledgerlab.ledger;

import java.time.Instant;
import java.util.List;

/**
 * A complete journal entry to record. Corrections (REVERSAL, CORRECTION) arrive in P4 with their own request
 * shape; this one covers business entries only.
 *
 * @param sourceKey identifies the business fact; the same key is recorded at most once (INV-8)
 * @param occurredAt when the fact happened in the outside world (contracts section 1.1)
 */
public record EntryRequest(
        EntryType type,
        String sourceKey,
        Instant occurredAt,
        SubjectType subjectType,
        String subjectId,
        List<PostingLine> lines) {

    public EntryRequest {
        if (type == EntryType.REVERSAL || type == EntryType.CORRECTION) {
            throw new IllegalArgumentException(type + " entries are recorded through restricted correction (P4)");
        }
        if (sourceKey.isBlank()) {
            throw new IllegalArgumentException("source key is blank");
        }
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("an entry needs at least 2 postings, got " + lines.size());
        }
        long debits = 0;
        long credits = 0;
        for (PostingLine line : lines) {
            if (line.side() == Side.DEBIT) {
                debits = Math.addExact(debits, line.amount());
            } else {
                credits = Math.addExact(credits, line.amount());
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException("unbalanced entry: debits " + debits + ", credits " + credits);
        }
    }

    /** Σ debits, which equals Σ credits. */
    public long totalAmount() {
        return lines.stream()
                .filter(line -> line.side() == Side.DEBIT)
                .mapToLong(PostingLine::amount)
                .sum();
    }
}
