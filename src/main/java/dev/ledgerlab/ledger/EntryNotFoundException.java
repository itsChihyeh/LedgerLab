package dev.ledgerlab.ledger;

public class EntryNotFoundException extends RuntimeException {
    private final String entryId;

    public EntryNotFoundException(String entryId) {
        super("Entry not found: " + entryId);
        this.entryId = entryId;
    }

    public String entryId() {
        return entryId;
    }
}
