package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static dev.ledgerlab.testsupport.LedgerFixture.only;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The posting interface itself: transactions, the funds lock, posting details, duplicate source keys. */
class JdbcLedgerTest {

    private LedgerFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new LedgerFixture(TestDatabase.freshMigrated());
    }

    @AfterEach
    void tearDown() {
        fixture.db().drop();
    }

    @Test
    void postingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> fixture.ledger().post(captured("ch_1", 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller's transaction");
        assertThatThrownBy(() -> fixture.ledger().lockFunds())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller's transaction");
    }

    @Test
    void postingWithoutTheFundsLockIsRefused() {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(fixture.db().app()));

        assertThatThrownBy(() -> transaction.execute(status -> fixture.ledger().post(captured("ch_1", 100))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockFunds");
        assertThat(fixture.count("ledger.journal_entry")).isZero();
    }

    @Test
    void everyTransactionTakesTheFundsLockAgain() {
        fixture.post(captured("ch_1", 100));

        // The first transaction's binding must not leak: the second one has to lock the row itself.
        fixture.inMoneyTransaction(ledger -> {
            Sql.assertRejected(
                    "55P03", fixture.db().app(), "SELECT id FROM ledger.funds_lock WHERE id = 1 FOR UPDATE NOWAIT");
            return ledger.post(captured("ch_2", 200));
        });

        assertThat(fixture.balances())
                .isEqualTo(only(Account.PROCESSOR_RECEIVABLE, 300, Account.MERCHANT_PENDING, 300));
    }

    @Test
    void theFundsLockIsHeldUntilTheTransactionEnds() throws SQLException {
        fixture.inMoneyTransaction(ledger -> {
            Sql.assertRejected(
                    "55P03", fixture.db().app(), "SELECT id FROM ledger.funds_lock WHERE id = 1 FOR UPDATE NOWAIT");
            return null;
        });

        Sql.execute(fixture.db().app(), "SELECT id FROM ledger.funds_lock WHERE id = 1 FOR UPDATE NOWAIT");
    }

    @Test
    void postingRecordsHeaderPostingsVersionsAndBalancesAfter() {
        PostedEntry first = fixture.post(captured("ch_1", 10000));
        // Batch-like entry with two postings on the same account (2101), as BATCH_SETTLED has.
        PostedEntry second = fixture.post(new EntryRequest(
                EntryType.BATCH_SETTLED,
                "batch:po_1:settled",
                T0,
                SubjectType.BATCH,
                "po_1",
                List.of(
                        PostingLine.debit(Account.SETTLEMENT_IN_TRANSIT, 9680),
                        PostingLine.debit(Account.MERCHANT_PENDING, 320),
                        PostingLine.credit(Account.PROCESSOR_RECEIVABLE, 10000),
                        PostingLine.debit(Account.MERCHANT_PENDING, 9680),
                        PostingLine.credit(Account.MERCHANT_AVAILABLE, 9680))));

        assertThat(second.seq()).isGreaterThan(first.seq());
        assertThat(second.balancesAfter())
                .isEqualTo(Map.of(
                        Account.SETTLEMENT_IN_TRANSIT, 9680L,
                        Account.MERCHANT_PENDING, 0L,
                        Account.PROCESSOR_RECEIVABLE, 0L,
                        Account.MERCHANT_AVAILABLE, 9680L));
        // S2 of the acceptance criteria: in transit 9680, available 9680.
        assertThat(fixture.balances())
                .isEqualTo(only(Account.SETTLEMENT_IN_TRANSIT, 9680, Account.MERCHANT_AVAILABLE, 9680));

        var postings = JdbcClient.create(fixture.db().app())
                .sql("""
                        SELECT line_no || ' ' || account_code || ' ' || side || ' ' || amount || ' v'
                               || account_version || ' -> ' || balance_after
                        FROM ledger.posting WHERE entry_id = :id ORDER BY line_no
                        """)
                .param("id", second.entryId())
                .query(String.class)
                .list();
        assertThat(postings)
                .containsExactly(
                        "1 1102 D 9680 v1 -> 9680",
                        "2 2101 D 320 v2 -> 9680",
                        "3 1101 C 10000 v2 -> 0",
                        "4 2101 D 9680 v3 -> 0",
                        "5 2102 C 9680 v1 -> 9680");
        assertThat(JdbcClient.create(fixture.db().app())
                        .sql("SELECT posting_count || ' ' || total_amount FROM ledger.journal_entry WHERE id = :id")
                        .param("id", second.entryId())
                        .query(String.class)
                        .single())
                .isEqualTo("5 19680");
        assertThat(fixture.audit().check()).isEmpty();
    }

    @Test
    void sameSourceKeyIsRecordedOnlyOnce() {
        fixture.post(captured("ch_1", 10000));

        assertThatThrownBy(() -> fixture.post(captured("ch_1", 10000)))
                .isInstanceOf(DuplicateSourceKeyException.class)
                .hasMessageContaining("payment:ch_1:captured");
        assertThat(fixture.count("ledger.journal_entry")).isEqualTo(1);
        assertThat(fixture.balances())
                .isEqualTo(only(Account.PROCESSOR_RECEIVABLE, 10000, Account.MERCHANT_PENDING, 10000));
        assertThat(fixture.audit().check()).isEmpty();
    }

    @Test
    void entryIdsCarryTheirPrefix() {
        PostedEntry entry = fixture.post(captured("ch_1", 10000));

        assertThat(entry.entryId()).matches("je_[0-9A-HJKMNP-TV-Z]{26}");
    }

    private static EntryRequest captured(String charge, long amount) {
        return new EntryRequest(
                EntryType.PAYMENT_CAPTURED,
                "payment:" + charge + ":captured",
                T0,
                SubjectType.PAYMENT,
                "pay_" + charge,
                List.of(
                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, amount),
                        PostingLine.credit(Account.MERCHANT_PENDING, amount)));
    }
}
