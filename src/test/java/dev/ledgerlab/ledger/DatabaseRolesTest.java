package dev.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * AC-1.13: the three roles (ADR-0014), plus the direct-SQL checks of data-dictionary section 0.1 for the P1a
 * tables. Tables of later phases bring their own rows in their own migrations and tests.
 */
class DatabaseRolesTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private LedgerFixture fixture;

    @BeforeEach
    void givenS1AndProbeTablesInHiddenSchemas() throws SQLException {
        fixture = new LedgerFixture(TestDatabase.freshMigrated());
        fixture.s1();
        // Hidden schemas get a table each, so a refusal means "no access", not "no such table".
        Sql.execute(fixture.db().migrator(), "CREATE TABLE io.probe (x int)", "INSERT INTO io.probe VALUES (1)");
        for (String schema : new String[] {"sim_processor", "sim_bank", "sim_chaos", "sim_truth"}) {
            Sql.execute(
                    fixture.db().simulator(),
                    "CREATE TABLE " + schema + ".probe (x int)",
                    "INSERT INTO " + schema + ".probe VALUES (1)");
        }
    }

    @AfterEach
    void tearDown() {
        fixture.db().drop();
    }

    @Test
    void ac_1_13_readonlyReadsTheViews() throws SQLException {
        assertThat(Sql.queryLong(fixture.db().readonly(), "SELECT count(*) FROM ledger.v_account"))
                .isEqualTo(8);
        assertThat(Sql.queryLong(fixture.db().readonly(), "SELECT count(*) FROM ledger.v_journal_entry"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(fixture.db().readonly(), "SELECT sum(amount) FROM ledger.v_posting"))
                .isEqualTo(20000);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM ledger.account",
                "SELECT * FROM ledger.journal_entry",
                "SELECT * FROM ledger.posting",
                "SELECT * FROM ledger.funds_lock",
                "SELECT * FROM io.probe",
                "SELECT * FROM sim_processor.probe",
                "SELECT * FROM sim_bank.probe",
                "SELECT * FROM sim_chaos.probe",
                "SELECT * FROM sim_truth.probe",
                "SELECT * FROM flyway.flyway_schema_history"
            })
    void ac_1_13_readonlyCannotReadBaseTablesOrHiddenSchemas(String query) {
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().readonly(), query);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSERT INTO ledger.v_account (code, name, kind, normal_side, allow_negative, balance, version)"
                        + " VALUES (1999, 'x', 'ASSET', 'D', true, 0, 0)",
                "UPDATE ledger.v_account SET balance = 0",
                "DELETE FROM ledger.v_posting",
                "UPDATE ledger.account SET balance = 0",
                "INSERT INTO ledger.journal_entry (id) VALUES ('je_00000000000000000000000001')",
                "CREATE TABLE ledger.sneaky (x int)",
                "CREATE TABLE public.sneaky (x int)",
                "CREATE TEMP TABLE sneaky (x int)",
                "CREATE SCHEMA sneaky",
                "SELECT id FROM ledger.v_journal_entry FOR UPDATE"
            })
    void ac_1_13_readonlyCannotWriteAnything(String statement) {
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().readonly(), statement);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE TABLE ledger.sneaky (x int)",
                "CREATE TABLE core.sneaky (x int)",
                "CREATE TABLE io.sneaky (x int)",
                "CREATE TABLE recon.sneaky (x int)",
                "CREATE TABLE public.sneaky (x int)",
                "CREATE TEMP TABLE sneaky (x int)",
                "CREATE SCHEMA sneaky",
                "ALTER TABLE ledger.account ADD COLUMN sneaky int",
                "ALTER TABLE ledger.account DROP CONSTRAINT account_non_negative",
                "ALTER TABLE ledger.posting DISABLE TRIGGER posting_same_txn_as_entry",
                "DROP TRIGGER posting_same_txn_as_entry ON ledger.posting",
                "DROP TABLE ledger.posting CASCADE",
                "CREATE OR REPLACE FUNCTION ledger.check_entry_sealed() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$",
                "ALTER ROLE app SUPERUSER"
            })
    void ac_1_13_appCannotRunDdl(String statement) {
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().app(), statement);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM sim_processor.probe",
                "SELECT * FROM sim_bank.probe",
                "SELECT * FROM sim_chaos.probe",
                "SELECT * FROM sim_truth.probe",
                "SELECT * FROM flyway.flyway_schema_history"
            })
    void appCannotSeeSimulatorOrMigrationSchemas(String query) {
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().app(), query);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSERT INTO ledger.funds_lock (id, touched_at) VALUES (2, now())",
                "DELETE FROM ledger.funds_lock",
                "UPDATE ledger.funds_lock SET id = 2",
                "TRUNCATE ledger.funds_lock"
            })
    void fundsLockAcceptsOnlyTheLock(String statement) {
        // data-dictionary 0.1: app may only lock the single row (UPDATE of touched_at).
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().app(), statement);
    }

    @Test
    void appCanLockFundsAndTouchTheLockRow() throws SQLException {
        Sql.execute(
                fixture.db().app(),
                "SELECT id FROM ledger.funds_lock WHERE id = 1 FOR UPDATE",
                "UPDATE ledger.funds_lock SET touched_at = now() WHERE id = 1");
        assertThat(Sql.queryLong(fixture.db().app(), "SELECT count(*) FROM ledger.funds_lock"))
                .isEqualTo(1);
    }

    @Test
    void simulatorCannotSeeTheMainApplicationSchemas() {
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().simulator(), "SELECT * FROM ledger.v_account");
        Sql.assertRejected(INSUFFICIENT_PRIVILEGE, fixture.db().simulator(), "SELECT * FROM io.probe");
    }
}
