package dev.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The audit must report states the database normally refuses. To build those states the superuser switches the
 * guards off (triggers via session_replication_role, constraints by dropping them in this throwaway database);
 * that is outside the threat model (ADR-0014) and only proves that each check can fire.
 */
class LedgerAuditTest {

    private LedgerFixture fixture;

    @BeforeEach
    void givenS1() {
        fixture = new LedgerFixture(TestDatabase.freshMigrated());
        fixture.s1();
    }

    @AfterEach
    void tearDown() {
        fixture.db().drop();
    }

    @Test
    void reportsAnEntryWhosePostingsDoNotMatchItsHeader() throws SQLException {
        Sql.execute(
                fixture.db().superuser(),
                "SET session_replication_role = replica",
                """
                INSERT INTO ledger.journal_entry (id, entry_type, source_key, posting_count, total_amount,
                    created_txid, occurred_at, recorded_at, subject_type, subject_id)
                VALUES ('je_00000000000000000000000001', 'PAYMENT_CAPTURED', 'payment:ch_bad:captured', 2, 500,
                    pg_current_xact_id(), now(), now(), 'payment', 'pay_bad')
                """,
                """
                INSERT INTO ledger.posting (id, entry_id, line_no, account_code, side, amount, account_version,
                    balance_after, recorded_at)
                VALUES ('pst_00000000000000000000000001', 'je_00000000000000000000000001', 1, 1101, 'D', 500, 2,
                    10500, now())
                """,
                "UPDATE ledger.account SET balance = 10500, version = 2 WHERE code = 1101");

        assertThat(fixture.audit().check())
                .filteredOn(v -> v.invariant().equals("INV-1/INV-4"))
                .singleElement()
                .satisfies(v -> assertThat(v.detail())
                        .contains("je_00000000000000000000000001", "1 postings (header 2)", "credits 0"));
    }

    @Test
    void reportsANegativeBalanceOnAnAccountThatMayNotBeNegative() throws SQLException {
        Sql.execute(
                fixture.db().superuser(),
                "ALTER TABLE ledger.account DROP CONSTRAINT account_non_negative",
                "UPDATE ledger.account SET balance = -1 WHERE code = 1103");

        assertThat(fixture.audit().check())
                .filteredOn(v -> v.invariant().equals("INV-5"))
                .singleElement()
                .satisfies(v -> assertThat(v.detail()).isEqualTo("account 1103 balance -1"));
    }

    @Test
    void reportsASourceKeyRecordedTwice() throws SQLException {
        Sql.execute(
                fixture.db().superuser(),
                "ALTER TABLE ledger.journal_entry DROP CONSTRAINT journal_entry_source_key_key",
                "SET session_replication_role = replica",
                """
                INSERT INTO ledger.journal_entry (id, entry_type, source_key, posting_count, total_amount,
                    created_txid, occurred_at, recorded_at, subject_type, subject_id)
                VALUES ('je_00000000000000000000000002', 'PAYMENT_CAPTURED', 'payment:ch_p1:captured', 2, 1,
                    pg_current_xact_id(), now(), now(), 'payment', 'pay_P1')
                """,
                """
                INSERT INTO ledger.posting (id, entry_id, line_no, account_code, side, amount, account_version,
                    balance_after, recorded_at)
                VALUES ('pst_00000000000000000000000002', 'je_00000000000000000000000002', 1, 1101, 'D', 1, 2,
                           10001, now()),
                       ('pst_00000000000000000000000003', 'je_00000000000000000000000002', 2, 2101, 'C', 1, 2,
                           10001, now())
                """,
                "UPDATE ledger.account SET balance = 10001, version = 2 WHERE code IN (1101, 2101)");

        assertThat(fixture.audit().check()).singleElement().satisfies(v -> {
            assertThat(v.invariant()).isEqualTo("INV-8");
            assertThat(v.detail()).isEqualTo("source key payment:ch_p1:captured has 2 entries");
        });
    }
}
