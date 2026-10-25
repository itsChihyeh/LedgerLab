package dev.ledgerlab.ledger;

public class CannotReverseReversalException extends RuntimeException {
    private final String entryId;

    public CannotReverseReversalException(String entryId) {
        super("Cannot reverse reversal: " + entryId);
        this.entryId = entryId;
    }

    public String entryId() {
        return entryId;
    }
}
