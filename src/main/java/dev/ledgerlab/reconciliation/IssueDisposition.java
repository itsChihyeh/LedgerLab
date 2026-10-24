package dev.ledgerlab.reconciliation;

/** Operator dispositions never change the ledger or previous run reports. */
public interface IssueDisposition {
    void accept(String issueId, String reason);

    void resolve(String issueId, String note);
}
