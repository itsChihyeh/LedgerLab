package dev.ledgerlab.ledger;

import java.util.Locale;

/** The kind of business object an entry belongs to; stored in lower case. */
public enum SubjectType {
    PAYMENT,
    REFUND,
    PAYOUT,
    BATCH,
    BANK_TXN;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
