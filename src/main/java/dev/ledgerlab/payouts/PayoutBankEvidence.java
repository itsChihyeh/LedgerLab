package dev.ledgerlab.payouts;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Applies BOOKED/CAD evidence in the caller's transaction, with its funds lock already held.
 * The bank importer owns line identity/version deduplication and persists the result atomically.
 */
public interface PayoutBankEvidence {
    Result applyDebit(Line line);

    Result applyCredit(Line line);

    record Line(String bankTxnId, String reference, long amount, LocalDate bookingDate, String memo) {}

    record Candidate(String id, String status, long amount) {}

    /** UNAPPLIED requires the importer to post unapplied cash; other results need no further posting. */
    record Result(
            String matchStatus,
            @Nullable Candidate candidate,
            @Nullable String issueCategory) {}
}
