package dev.ledgerlab.ledger;

/** One requested posting: an account, a side and a positive amount in cents. */
public record PostingLine(Account account, Side side, long amount) {

    public PostingLine {
        if (amount <= 0) {
            throw new IllegalArgumentException("posting amount must be a positive number of cents: " + amount);
        }
    }

    public static PostingLine debit(Account account, long amount) {
        return new PostingLine(account, Side.DEBIT, amount);
    }

    public static PostingLine credit(Account account, long amount) {
        return new PostingLine(account, Side.CREDIT, amount);
    }

    /** The effect on the account balance, which is kept in the account's normal direction. */
    public long balanceDelta() {
        return side == account.normalSide() ? amount : -amount;
    }
}
