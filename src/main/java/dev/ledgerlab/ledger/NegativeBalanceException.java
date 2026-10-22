package dev.ledgerlab.ledger;

/** The entry would take an account that may not go negative below zero (INV-5); nothing was recorded. */
public class NegativeBalanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Account account;
    private final long resultingBalance;

    public NegativeBalanceException(Account account, long resultingBalance) {
        super("account " + account.code() + " (" + account + ") may not go negative: would become "
                + resultingBalance);
        this.account = account;
        this.resultingBalance = resultingBalance;
    }

    public Account account() {
        return account;
    }

    public long resultingBalance() {
        return resultingBalance;
    }
}
