package dev.ledgerlab.ledger;

/**
 * The chart of accounts (money-model section 2). The code is also the lock order: a transaction that locks
 * several accounts locks them in ascending code order (ADR-0004).
 */
public enum Account {
    PROCESSOR_RECEIVABLE(1101, Side.DEBIT, true),
    SETTLEMENT_IN_TRANSIT(1102, Side.DEBIT, false),
    BANK_CASH(1103, Side.DEBIT, false),
    MERCHANT_PENDING(2101, Side.CREDIT, false),
    MERCHANT_AVAILABLE(2102, Side.CREDIT, true),
    REFUND_PAYABLE(2103, Side.CREDIT, false),
    PAYOUT_IN_TRANSIT(2104, Side.CREDIT, false),
    UNAPPLIED_CASH(2105, Side.CREDIT, false);

    private final short code;
    private final Side normalSide;
    private final boolean allowNegative;

    Account(int code, Side normalSide, boolean allowNegative) {
        this.code = (short) code;
        this.normalSide = normalSide;
        this.allowNegative = allowNegative;
    }

    public short code() {
        return code;
    }

    public Side normalSide() {
        return normalSide;
    }

    public boolean allowNegative() {
        return allowNegative;
    }

    public static Account ofCode(int code) {
        for (Account account : values()) {
            if (account.code == code) {
                return account;
            }
        }
        throw new IllegalArgumentException("unknown account code " + code);
    }
}
