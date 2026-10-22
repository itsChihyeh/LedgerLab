package dev.ledgerlab.ledger;

/** Debit or credit; stored as {@code D} / {@code C}. */
public enum Side {
    DEBIT("D"),
    CREDIT("C");

    private final String dbValue;

    Side(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static Side ofDbValue(String value) {
        return switch (value) {
            case "D" -> DEBIT;
            case "C" -> CREDIT;
            default -> throw new IllegalArgumentException("unknown side " + value);
        };
    }
}
