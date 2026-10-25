package dev.ledgerlab.ledger;
/** API callers map this to ALREADY_REVERSED and return the existing correctionId. */
public class AlreadyCorrectedException extends RuntimeException {
    private final String entryId;
    private final String correctionId;

    public AlreadyCorrectedException(String entryId, String correctionId) {
        super("Entry " + entryId + " is already a target of " + correctionId);
        this.entryId = entryId;
        this.correctionId = correctionId;
    }

    public String entryId() {
        return entryId;
    }

    public String correctionId() {
        return correctionId;
    }
}
