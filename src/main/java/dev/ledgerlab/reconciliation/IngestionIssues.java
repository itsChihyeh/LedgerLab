package dev.ledgerlab.reconciliation;

/** Opens an INGESTION issue in the caller's transaction; an existing identity is left unchanged. */
public interface IngestionIssues {
    /** expectedJson/actualJson are JSON values, evidenceRefs is a JSON array of evidence references. */
    void open(
            String category,
            String subjectType,
            String subjectId,
            String expectedJson,
            String actualJson,
            String evidenceRefs);
}
