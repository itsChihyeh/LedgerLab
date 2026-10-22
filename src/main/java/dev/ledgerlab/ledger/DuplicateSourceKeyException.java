package dev.ledgerlab.ledger;

/**
 * An entry with this source key already exists (INV-8). The database rejected the insert, so the surrounding
 * transaction is aborted and must roll back; callers that expect duplicates check first.
 */
public class DuplicateSourceKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateSourceKeyException(String sourceKey, Throwable cause) {
        super("an entry with source key " + sourceKey + " already exists", cause);
    }
}
