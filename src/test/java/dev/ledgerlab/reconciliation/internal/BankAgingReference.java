package dev.ledgerlab.reconciliation.internal;

import java.util.HashSet;
import java.util.Set;

/** Independent fixed-calendar scenario: Nov 11 is a holiday, Nov 14 midnight is the due time. */
final class BankAgingReference {
    enum Operation {
        CONFIRM,
        IMPORT,
        ADVANCE_TO_DUE,
        EXPIRE_WAITING,
        REPLAY
    }

    boolean confirmed;
    boolean imported;
    boolean expired;
    boolean matched;
    boolean unapplied;

    void apply(Operation operation) {
        switch (operation) {
            case CONFIRM -> {
                confirmed = true;
                if (imported && !unapplied) matched = true;
            }
            case IMPORT -> {
                if (!imported) {
                    imported = true;
                    matched = confirmed;
                    unapplied = !confirmed && expired;
                }
            }
            case ADVANCE_TO_DUE -> expired = true;
            case EXPIRE_WAITING -> {
                if (expired && imported && !matched) unapplied = true;
            }
            case REPLAY -> {}
        }
    }

    Set<String> bank() {
        var findings = new HashSet<String>();
        if (confirmed && !matched && !expired) findings.add("TIMING_DIFFERENCE:batch:po_model");
        if (imported && !matched && !unapplied)
            findings.add("TIMING_DIFFERENCE:bank_statement:SIM-CAD-001:2026-11-10:2026-11-10");
        if (unapplied) {
            findings.add("UNAPPLIED_CASH_OPEN:bank_txn:bt_model");
            findings.add("UNMATCHED_BANK_LINE:bank_txn:bt_model");
        }
        return findings;
    }

    Set<String> aging(String paymentId) {
        var findings = new HashSet<String>();
        if (expired && !confirmed) findings.add("PAYMENT_NOT_SETTLED:payment:" + paymentId);
        if (expired && confirmed && !matched) findings.add("DEPOSIT_NOT_RECEIVED:batch:po_model");
        return findings;
    }
}
