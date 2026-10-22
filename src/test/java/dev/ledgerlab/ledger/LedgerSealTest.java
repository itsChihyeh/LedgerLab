package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.only;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The database seals entries on its own (ADR-0003): these tests write SQL directly as {@code app}, bypassing the
 * posting interface, and the commit must still fail. LL002 / LL003 are the SQLSTATEs raised by the V1 triggers.
 */
class LedgerSealTest {

    private static final String SEAL_VIOLATION = "LL003";
    private static final String APPEND_TO_SEALED = "LL002";

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
    void ac_1_5_databaseRejectsUnbalancedEntry() {
        Sql.assertRejected(
                SEAL_VIOLATION,
                fixture.db().app(),
                entry("je_00000000000000000000000001", 2, 10000),
                posting("pst_00000000000000000000000001", "je_00000000000000000000000001", 1, 1101, "D", 10000),
                posting("pst_00000000000000000000000002", "je_00000000000000000000000001", 2, 2101, "C", 9999));

        assertNothingRecorded();
    }

    @Test
    void ac_1_6_appendingBalancedPairToCommittedEntryIsRejected() throws SQLException {
        PostedEntry e = fixture.s1();

        Sql.assertRejected(
                APPEND_TO_SEALED,
                fixture.db().app(),
                posting("pst_00000000000000000000000003", e.entryId(), 3, 1101, "D", 1),
                posting("pst_00000000000000000000000004", e.entryId(), 4, 2101, "C", 1));

        assertThat(Sql.queryLong(
                        fixture.db().app(),
                        "SELECT count(*) FROM ledger.posting WHERE entry_id = '" + e.entryId() + "'"))
                .isEqualTo(2);
        assertThat(fixture.balances())
                .isEqualTo(only(Account.PROCESSOR_RECEIVABLE, 10000, Account.MERCHANT_PENDING, 10000));
        assertThat(fixture.audit().check()).isEmpty();
    }

    @Test
    void ac_1_6_appendingInTheCreatingTransactionFailsAtCommit() {
        // Same transaction, so the txid check passes; the header still says 2 postings.
        Sql.assertRejected(
                SEAL_VIOLATION,
                fixture.db().app(),
                entry("je_00000000000000000000000002", 2, 10000),
                posting("pst_00000000000000000000000005", "je_00000000000000000000000002", 1, 1101, "D", 10000),
                posting("pst_00000000000000000000000006", "je_00000000000000000000000002", 2, 2101, "C", 10000),
                posting("pst_00000000000000000000000007", "je_00000000000000000000000002", 3, 1101, "D", 1),
                posting("pst_00000000000000000000000008", "je_00000000000000000000000002", 4, 2101, "C", 1));

        assertNothingRecorded();
    }

    @Test
    void ac_1_6_immediateConstraintsRejectTheExtraPairAtStatementEnd() {
        // data-dictionary section 1, seal rule 3: SET CONSTRAINTS ALL IMMEDIATE checks every later insert.
        Sql.assertRejected(
                SEAL_VIOLATION,
                fixture.db().app(),
                entry("je_00000000000000000000000003", 2, 10000),
                posting("pst_00000000000000000000000009", "je_00000000000000000000000003", 1, 1101, "D", 10000),
                posting("pst_00000000000000000000000010", "je_00000000000000000000000003", 2, 2101, "C", 10000),
                "SET CONSTRAINTS ALL IMMEDIATE",
                posting("pst_00000000000000000000000011", "je_00000000000000000000000003", 3, 1101, "D", 1));

        assertNothingRecorded();
    }

    @Test
    void ac_1_7_entryWithoutPostingsFailsAtCommit() {
        Sql.assertRejected(SEAL_VIOLATION, fixture.db().app(), entry("je_00000000000000000000000004", 2, 10000));

        assertNothingRecorded();
    }

    @Test
    void ac_1_7_singleSidedEntryFailsAtCommit() {
        Sql.assertRejected(
                SEAL_VIOLATION,
                fixture.db().app(),
                entry("je_00000000000000000000000005", 2, 10000),
                posting("pst_00000000000000000000000012", "je_00000000000000000000000005", 1, 1101, "D", 10000));

        assertNothingRecorded();
    }

    @Test
    void ac_1_7_headerCountThatDoesNotMatchFailsAtCommit() {
        Sql.assertRejected(
                SEAL_VIOLATION,
                fixture.db().app(),
                entry("je_00000000000000000000000006", 3, 10000),
                posting("pst_00000000000000000000000013", "je_00000000000000000000000006", 1, 1101, "D", 10000),
                posting("pst_00000000000000000000000014", "je_00000000000000000000000006", 2, 2101, "C", 10000));

        assertNothingRecorded();
    }

    @Test
    void ac_1_7_headerWithFewerThanTwoPostingsIsRejectedImmediately() {
        Sql.assertRejected("23514", fixture.db().app(), entry("je_00000000000000000000000007", 1, 10000));

        assertNothingRecorded();
    }

    @Test
    void ac_1_7_zeroAmountPostingIsRejected() {
        Sql.assertRejected(
                "23514",
                fixture.db().app(),
                entry("je_00000000000000000000000008", 2, 10000),
                posting("pst_00000000000000000000000015", "je_00000000000000000000000008", 1, 1101, "D", 10000),
                posting("pst_00000000000000000000000016", "je_00000000000000000000000008", 2, 2101, "C", 0));

        assertNothingRecorded();
    }

    @Test
    void createdTxidPassedByTheCallerIsOverwritten() throws SQLException {
        // A caller cannot forge the creating transaction to unlock a later append.
        Sql.execute(
                fixture.db().app(),
                """
                INSERT INTO ledger.journal_entry (id, entry_type, source_key, posting_count, total_amount, created_txid,
                    occurred_at, recorded_at, subject_type, subject_id)
                VALUES ('je_00000000000000000000000009', 'PAYMENT_CAPTURED', 'payment:ch_forged:captured', 2, 1,
                    '1', now(), now(), 'payment', 'pay_X')
                """,
                posting("pst_00000000000000000000000017", "je_00000000000000000000000009", 1, 1101, "D", 1),
                posting("pst_00000000000000000000000018", "je_00000000000000000000000009", 2, 2101, "C", 1));

        assertThat(Sql.queryLong(
                        fixture.db().app(),
                        "SELECT created_txid::text::bigint FROM ledger.journal_entry"
                                + " WHERE id = 'je_00000000000000000000000009'"))
                .isGreaterThan(1);
    }

    private void assertNothingRecorded() {
        assertThat(fixture.count("ledger.journal_entry")).isZero();
        assertThat(fixture.count("ledger.posting")).isZero();
    }

    private static String entry(String id, int postingCount, long total) {
        return """
                INSERT INTO ledger.journal_entry (id, entry_type, source_key, posting_count, total_amount,
                    occurred_at, recorded_at, subject_type, subject_id)
                VALUES ('%s', 'PAYMENT_CAPTURED', 'payment:ch_%s:captured', %d, %d, now(), now(), 'payment', 'pay_X')
                """.formatted(id, id, postingCount, total);
    }

    private static String posting(String id, String entryId, int lineNo, int account, String side, long amount) {
        return """
                INSERT INTO ledger.posting (id, entry_id, line_no, account_code, side, amount, account_version,
                    balance_after, recorded_at)
                VALUES ('%s', '%s', %d, %d, '%s', %d, 1, %d, now())
                """.formatted(id, entryId, lineNo, account, side, amount, amount);
    }
}
