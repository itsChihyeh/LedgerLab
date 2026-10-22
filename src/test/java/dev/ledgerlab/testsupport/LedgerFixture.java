package dev.ledgerlab.testsupport;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.ledger.PostedEntry;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.ledger.internal.JdbcLedgerAudit;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The ledger wired by hand against one test database, connecting as {@code app} like the application does. */
public final class LedgerFixture {

    public static final Instant T0 = Instant.parse("2026-10-21T14:00:00Z");

    private final TestDatabase.Handle db;
    private final Ledger ledger;
    private final LedgerAudit audit;
    private final TransactionTemplate transactions;

    public LedgerFixture(TestDatabase.Handle db) {
        this.db = db;
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        // One DataSource instance for everything: Spring binds the transaction's connection to that instance.
        DataSource app = db.app();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(app);
        this.ledger = new JdbcLedger(app, clock, IdGenerator.seeded(clock, 42));
        this.audit = new JdbcLedgerAudit(app, transactionManager);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public TestDatabase.Handle db() {
        return db;
    }

    public Ledger ledger() {
        return ledger;
    }

    public LedgerAudit audit() {
        return audit;
    }

    /** Runs the work in one transaction that has taken the funds lock, as every money-moving caller does. */
    public <T> T inMoneyTransaction(Function<Ledger, T> work) {
        return transactions.execute(status -> {
            ledger.lockFunds();
            return work.apply(ledger);
        });
    }

    public PostedEntry post(EntryRequest request) {
        return inMoneyTransaction(l -> l.post(request));
    }

    /** S1 of the acceptance criteria: payment P1 of 10000 captured (receivable 10000, merchant pending 10000). */
    public PostedEntry s1() {
        return post(new EntryRequest(
                EntryType.PAYMENT_CAPTURED,
                "payment:ch_p1:captured",
                T0,
                SubjectType.PAYMENT,
                "pay_P1",
                List.of(
                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 10000),
                        PostingLine.credit(Account.MERCHANT_PENDING, 10000))));
    }

    /** All eight stored balances, read as {@code app}. */
    public Map<Account, Long> balances() {
        Map<Account, Long> balances = new EnumMap<>(Account.class);
        JdbcClient.create(db.app())
                .sql("SELECT code, balance FROM ledger.account")
                .query((rs, row) -> balances.put(Account.ofCode(rs.getInt("code")), rs.getLong("balance")))
                .list();
        return balances;
    }

    /** The expected balances: the given ones, every other account 0. */
    public static Map<Account, Long> only(Object... accountsAndAmounts) {
        Map<Account, Long> expected = new EnumMap<>(Account.class);
        for (Account account : Account.values()) {
            expected.put(account, 0L);
        }
        for (int i = 0; i < accountsAndAmounts.length; i += 2) {
            expected.put((Account) accountsAndAmounts[i], ((Number) accountsAndAmounts[i + 1]).longValue());
        }
        return expected;
    }

    public long count(String table) {
        return JdbcClient.create(db.app())
                .sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }
}
