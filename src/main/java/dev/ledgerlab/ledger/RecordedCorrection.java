package dev.ledgerlab.ledger;

import java.util.List;

/** Identifiers and balances of the entries generated in the caller's transaction. */
public record RecordedCorrection(String correctionId, List<PostedEntry> entries) {
    public RecordedCorrection {
        entries = List.copyOf(entries);
    }
}
