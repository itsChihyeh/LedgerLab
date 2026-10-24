package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LedgerBalanceTest {
    private TestDatabase.Handle db;
    private Ledger ledger;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        db = TestDatabase.freshMigrated();
        var app = db.app();
        var clock = Clock.fixed(T0, ZoneOffset.UTC);
        ledger = new JdbcLedger(app, clock, IdGenerator.seeded(clock, 42));
        jdbc = JdbcClient.create(app);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(app));
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    @Test
    void ac_3_7_balanceOutsideTransactionIsRefused() {
        assertThatThrownBy(() -> ledger.balance(Account.MERCHANT_AVAILABLE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ac_3_7_balanceRequiresFundsLockInTheCurrentTransaction() {
        transactions.executeWithoutResult(status -> ledger.lockFunds());
        assertThatThrownBy(() -> transactions.execute(status -> ledger.balance(Account.MERCHANT_AVAILABLE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funds lock");
    }

    @Test
    void ac_3_6_lockedBalancesMatchPostingsIncludingUncommittedRefund() {
        transactions.executeWithoutResult(status -> {
            ledger.lockFunds();
            assertBalancesMatchPostings();
            ledger.post(new EntryRequest(
                    EntryType.PAYMENT_CAPTURED,
                    "payment:ch_balance:captured",
                    T0,
                    SubjectType.PAYMENT,
                    "pay_balance",
                    List.of(
                            PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 10000),
                            PostingLine.credit(Account.MERCHANT_PENDING, 10000))));
            assertThat(ledger.balance(Account.MERCHANT_PENDING)).isEqualTo(10000);
            assertBalancesMatchPostings();
            ledger.post(new EntryRequest(
                    EntryType.REFUND_ACCEPTED,
                    "refund:rfd_balance:accepted",
                    T0,
                    SubjectType.REFUND,
                    "rfd_balance",
                    List.of(
                            PostingLine.debit(Account.MERCHANT_AVAILABLE, 2000),
                            PostingLine.credit(Account.REFUND_PAYABLE, 2000))));
            assertThat(ledger.balance(Account.MERCHANT_AVAILABLE)).isEqualTo(-2000);
            assertBalancesMatchPostings();
        });
        transactions.executeWithoutResult(status -> {
            ledger.lockFunds();
            assertBalancesMatchPostings();
        });
    }

    @Test
    void ac_3_7_balanceEnforcesBothSafeIntegerBounds() {
        // Isolated fault fixture: bypass only the database bound to exercise the Java guard.
        JdbcClient.create(db.migrator())
                .sql("ALTER TABLE ledger.account DROP CONSTRAINT account_balance_bound")
                .update();
        transactions.executeWithoutResult(status -> {
            ledger.lockFunds();
            for (long valid : new long[] {-9007199254740991L, 0, 9007199254740991L}) {
                setAvailableBalance(valid);
                assertThat(ledger.balance(Account.MERCHANT_AVAILABLE)).isEqualTo(valid);
            }
            for (long invalid : new long[] {-9007199254740992L, 9007199254740992L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                setAvailableBalance(invalid);
                assertThatThrownBy(() -> ledger.balance(Account.MERCHANT_AVAILABLE))
                        .isInstanceOf(ArithmeticException.class);
            }
            status.setRollbackOnly();
        });
    }

    private void setAvailableBalance(long balance) {
        jdbc.sql("UPDATE ledger.account SET balance = :balance WHERE code = 2102")
                .param("balance", balance)
                .update();
    }

    private void assertBalancesMatchPostings() {
        for (Account account : Account.values()) {
            long recomputed = jdbc.sql("""
                            SELECT COALESCE(SUM(CASE WHEN side = :normal THEN amount ELSE -amount END), 0)
                            FROM ledger.posting WHERE account_code = :code
                            """)
                    .param("normal", account.normalSide().dbValue())
                    .param("code", account.code())
                    .query(Long.class)
                    .single();
            assertThat(ledger.balance(account)).as("balance of %s", account).isEqualTo(recomputed);
        }
    }
}
