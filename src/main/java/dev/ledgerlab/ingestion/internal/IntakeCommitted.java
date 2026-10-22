package dev.ledgerlab.ingestion.internal;

/** AC-4.4 injection point, called after the receiving transaction commits and before responding. */
@FunctionalInterface
public interface IntakeCommitted {
    void afterCommit();
}
