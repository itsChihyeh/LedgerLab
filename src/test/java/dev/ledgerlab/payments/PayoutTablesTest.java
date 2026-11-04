package dev.ledgerlab.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** T02 database prerequisites for AC-7 and AC-6.15; business flows belong to later P5 tasks. */
class PayoutTablesTest {
    private static final String TIME = "'2026-11-02T12:00:00Z'";
    private static final String PAYOUT = "'mpo_01JB0000000000000000000001'";
    private static final String FILE = "'file_01JB0000000000000000000001'";
    private TestDatabase.Handle db;

    @BeforeEach
    void setup() {
        db = TestDatabase.freshMigrated();
    }

    @AfterEach
    void cleanup() {
        db.drop();
    }

    @Test
    void ac_1_13_emptyMigrationCreatesOwnedTablesAndRedactedViews() throws SQLException {
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM flyway.flyway_schema_history WHERE version='21' AND success
                """)).isEqualTo(1);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_tables WHERE tableowner='migrator' AND schemaname='core'
                    AND tablename IN ('payout','payout_status_history')
                """)).isEqualTo(2);
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_views WHERE viewowner='migrator' AND schemaname='core'
                    AND viewname IN ('v_payout','v_payout_status_history')
                """)).isEqualTo(2);
        assertThat(Sql.queryLong(db.readonly(), """
                SELECT count(*) FROM information_schema.columns WHERE table_schema='core'
                    AND table_name='v_payout' AND column_name IN ('initial_result','request_fingerprint')
                """)).isZero();
    }

    @Test
    void ac_7_23_upgradePreservesOldCommandsAttemptsAndBankEvidence() throws SQLException {
        var old = TestDatabase.freshBlank();
        try {
            Flyway.configure()
                    .dataSource(old.migrator())
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .target("20")
                    .load()
                    .migrate();
            seedBankStatement(old);
            Sql.execute(old.app(), bankLine(Map.of()), command(1, "SUBMIT_REFUND", "DONE"), """
                    INSERT INTO io.outbound_attempt(command_id,attempt_no,lease_epoch,sent_at,
                        request_summary,response_status,response_body,finished_at,recorded_at)
                    VALUES ('cmd_00000000000000000000000001',1,0,%1$s,'{}',200,'original',%1$s,%1$s)
                    """.formatted(TIME));
            var flyway = Flyway.configure()
                    .dataSource(old.migrator())
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .load();
            flyway.migrate();
            flyway.validate();
            assertThat(Sql.queryLong(old.app(), """
                    SELECT count(*) FROM io.outbound_command WHERE status='DONE' AND kind='SUBMIT_REFUND'
                        AND request_id=%s AND attempts=0 AND first_sent_at IS NULL
                    """.formatted(PAYOUT))).isEqualTo(1);
            assertThat(Sql.queryLong(old.app(), """
                    SELECT count(*) FROM io.outbound_attempt WHERE response_body='original' AND attempt_no=1
                    """)).isEqualTo(1);
            assertThat(Sql.queryLong(old.readonly(), """
                    SELECT count(*) FROM recon.v_bank_line WHERE bank_txn_id='bt_payout' AND amount=9680
                        AND match_status='UNMATCHED_NO_POSTING' AND matched_payout_id IS NULL
                    """)).isEqualTo(1);
            Sql.assertRejected("23505", old.app(), command(2, "SUBMIT_REFUND", "PENDING"));
            Sql.execute(old.app(), command(2, "QUERY_REFUND", "PENDING"), payout(Map.of()), history(Map.of()));
        } finally {
            old.drop();
        }
    }

    @Test
    void ac_1_13_appCanUpdateEveryGrantedColumnAndReadonlyCanReadViews() throws SQLException {
        Sql.execute(db.app(), payout(Map.of()), history(Map.of()), """
                UPDATE core.payout SET status='RETURNED',reserved_at=%1$s,submitted_at=%1$s,
                    unknown_at=%1$s,accepted_at=%1$s,paid_at=%1$s,failed_at=%1$s,returned_at=%1$s,
                    bank_ack_ref='bpm_test',bank_execution_date='2026-11-03',bank_txn_id='bt_paid',
                    return_bank_txn_id='bt_returned',failure_reason='retained evidence',
                    return_reason='ACCOUNT_CLOSED',updated_at=%1$s
                """.formatted(TIME));
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.payout WHERE status='RETURNED'"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.payout_status_history"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM core.v_payout WHERE status='RETURNED'"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM core.v_payout_status_history"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "merchant_payout_ref",
                "amount",
                "currency",
                "initial_result",
                "request_fingerprint",
                "recorded_at"
            })
    void ac_1_13_appCannotRewriteImmutablePayoutColumns(String column) throws SQLException {
        Sql.execute(db.app(), payout(Map.of()));
        Sql.assertRejected("42501", db.app(), "UPDATE core.payout SET " + column + "=" + column);
    }

    @Test
    void ac_1_13_appCannotDeleteAndReadonlyCannotAccessBaseTablesOrWriteViews() throws SQLException {
        Sql.execute(db.app(), payout(Map.of()), history(Map.of()));
        for (String table : new String[] {"payout", "payout_status_history"}) {
            Sql.assertRejected("42501", db.app(), "DELETE FROM core." + table);
            Sql.assertRejected("42501", db.app(), "TRUNCATE core." + table + " CASCADE");
            Sql.assertRejected("42501", db.readonly(), "SELECT * FROM core." + table);
            for (String relation : new String[] {"core." + table, "core.v_" + table}) {
                Sql.assertRejected("42501", db.readonly(), "UPDATE " + relation + " SET recorded_at=recorded_at");
                Sql.assertRejected("42501", db.readonly(), "DELETE FROM " + relation);
                Sql.assertRejected(
                        "42501", db.readonly(), "INSERT INTO " + relation + " (recorded_at) VALUES (" + TIME + ")");
            }
        }
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.outbound_command");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE core.payout_status_history SET evidence_id='changed'",
                "DELETE FROM core.payout_status_history",
                "TRUNCATE core.payout_status_history"
            })
    void ac_1_13_historyIsAppendOnlyEvenForOwner(String statement) throws SQLException {
        Sql.execute(db.app(), payout(Map.of()), history(Map.of()));
        Sql.assertRejected("42501", db.app(), statement);
        Sql.assertRejected("LL004", db.migrator(), statement);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "id|'rfd_01JB0000000000000000000001'",
                "id|'mpo_81JB0000000000000000000001'",
                "merchant_payout_ref|''",
                "merchant_payout_ref|'has space'",
                "merchant_payout_ref|'提现'",
                "merchant_payout_ref|repeat('x',65)",
                "amount|0",
                "amount|-1",
                "amount|100000000",
                "currency|'USD'",
                "status|'INVALID'",
                "status|'PAID'",
                "status|'RETURNED'",
                "status|'FAILED'"
            })
    void ac_7_20_invalidPayoutValuesAndMissingEvidenceAreRejected(String column, String value) {
        Sql.assertRejected("23514", db.app(), payout(Map.of(column, value)));
    }

    @Test
    void ac_7_10_returnedRequiresBothBankReferencesOnInsertAndUpdate() throws SQLException {
        Sql.assertRejected("23514", db.app(), payout(Map.of("status", "'RETURNED'", "bank_txn_id", "'bt_paid'")));
        Sql.assertRejected(
                "23514", db.app(), payout(Map.of("status", "'RETURNED'", "return_bank_txn_id", "'bt_returned'")));
        Sql.execute(
                db.app(),
                payout(Map.of(
                        "status", "'RETURNED'", "bank_txn_id", "'bt_paid'", "return_bank_txn_id", "'bt_returned'")));
        Sql.assertRejected("23514", db.app(), "UPDATE core.payout SET bank_txn_id=NULL");
        Sql.assertRejected("23514", db.app(), "UPDATE core.payout SET return_bank_txn_id=NULL");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "merchant_payout_ref",
                "amount",
                "currency",
                "status",
                "initial_result",
                "request_fingerprint",
                "reserved_at",
                "updated_at",
                "recorded_at"
            })
    void ac_7_17_payoutRequiredFieldsRejectSqlNull(String column) {
        Sql.assertRejected("23502", db.app(), payout(Map.of(column, "NULL")));
    }

    @Test
    void ac_7_17_businessIdentityIsPermanentAndInitialResponseSurvivesStateChanges() throws SQLException {
        Sql.execute(db.app(), payout(Map.of()));
        for (String status : new String[] {"SUBMITTED", "UNKNOWN", "ACCEPTED", "FAILED", "PAID", "RETURNED"}) {
            Sql.execute(
                    db.app(),
                    "UPDATE core.payout SET status='" + status
                            + "',bank_txn_id='bt_paid',return_bank_txn_id='bt_returned',failure_reason='rejected'");
        }
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.payout WHERE status='RETURNED'
                    AND initial_result='{"status":201,"body":{"status":"RESERVED"}}'::jsonb
                """)).isEqualTo(1);
        Sql.assertRejected("23505", db.app(), payout(Map.of("id", "'mpo_01JB0000000000000000000002'")));
        Sql.assertRejected("23505", db.app(), payout(Map.of("merchant_payout_ref", "'MP-2'")));
        Sql.execute(
                db.app(),
                payout(Map.of(
                        "id",
                        "'mpo_01JB0000000000000000000002'",
                        "merchant_payout_ref",
                        "repeat('x',64)",
                        "amount",
                        "99999999")));
        Sql.execute(
                db.app(),
                payout(Map.of("id", "'mpo_01JB0000000000000000000003'", "merchant_payout_ref", "'X'", "amount", "1")));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "payout_id|'mpo_missing'|23503",
                "from_status|'INVALID'|23514",
                "to_status|'INVALID'|23514",
                "evidence_type|'INVALID'|23514",
                "payout_id|NULL|23502",
                "to_status|NULL|23502",
                "evidence_type|NULL|23502",
                "evidence_id|NULL|23502",
                "occurred_at|NULL|23502",
                "recorded_at|NULL|23502"
            })
    void ac_7_1_historyRequiresValidPayoutStateAndEvidence(String column, String value, String code)
            throws SQLException {
        Sql.execute(db.app(), payout(Map.of()));
        Sql.assertRejected(code, db.app(), history(Map.of(column, value)));
    }

    @Test
    void ac_7_1_historyAcceptsEveryStateAndEvidenceKind() throws SQLException {
        Sql.execute(db.app(), payout(Map.of()));
        for (String state :
                new String[] {"RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED", "PAID", "FAILED", "RETURNED"}) {
            Sql.execute(db.app(), history(Map.of("from_status", "'" + state + "'", "to_status", "'" + state + "'")));
        }
        for (String evidence : new String[] {"api", "bank_api", "bank_txn", "correction", "operator"}) {
            Sql.execute(db.app(), history(Map.of("evidence_type", "'" + evidence + "'")));
        }
        assertThat(Sql.queryLong(db.app(), "SELECT count(DISTINCT id) FROM core.payout_status_history"))
                .isEqualTo(12);
        Sql.assertRejected("23505", db.migrator(), """
                INSERT INTO core.payout_status_history OVERRIDING SYSTEM VALUE
                    SELECT * FROM core.payout_status_history
                """);
    }

    @ParameterizedTest
    @CsvSource({"PAYOUT_PAID,DEBIT", "PAYOUT_RETURNED,CREDIT"})
    void ac_7_10_bankMatchesRequirePayoutAndCorrectDirection(String status, String direction) throws SQLException {
        seedBankStatement(db);
        Sql.execute(db.app(), payout(Map.of()));
        var values =
                new LinkedHashMap<>(Map.of("match_status", "'" + status + "'", "direction", "'" + direction + "'"));
        Sql.assertRejected("23514", db.app(), bankLine(values));
        values.put("matched_payout_id", "'mpo_missing'");
        Sql.assertRejected("23503", db.app(), bankLine(values));
        values.put("matched_payout_id", PAYOUT);
        values.put("direction", direction.equals("DEBIT") ? "'CREDIT'" : "'DEBIT'");
        Sql.assertRejected("23514", db.app(), bankLine(values));
        values.put("direction", "'" + direction + "'");
        values.put("status", "'PENDING'");
        Sql.assertRejected("23514", db.app(), bankLine(values));
        values.put("status", "'BOOKED'");
        Sql.execute(db.app(), bankLine(values));
        Sql.assertRejected("23514", db.app(), "UPDATE recon.bank_line SET matched_payout_id=NULL");
        assertThat(Sql.queryLong(
                        db.readonly(), "SELECT count(*) FROM recon.v_bank_line WHERE matched_payout_id=" + PAYOUT))
                .isEqualTo(1);
    }

    @Test
    void ac_7_11_conflictCandidateCanLaterBeMatchedWithoutChangingBankEvidence() throws SQLException {
        seedBankStatement(db);
        Sql.execute(
                db.app(),
                payout(Map.of()),
                bankLine(Map.of()),
                "UPDATE recon.bank_line SET matched_payout_id=" + PAYOUT,
                "UPDATE recon.bank_line SET match_status='PAYOUT_PAID'");
        assertThat(Sql.queryLong(db.readonly(), """
                SELECT count(*) FROM recon.v_bank_line WHERE match_status='PAYOUT_PAID'
                    AND direction='DEBIT' AND amount=9680 AND matched_payout_id=%s
                """.formatted(PAYOUT))).isEqualTo(1);
        Sql.assertRejected("42501", db.app(), "UPDATE recon.bank_line SET amount=1");
        Sql.assertRejected("LL004", db.migrator(), "UPDATE recon.bank_line SET amount=1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMIT_REFUND", "SUBMIT_PAYOUT"})
    void ac_7_17_submitRequestIsPermanentlyUniqueAcrossSubmitKinds(String kind) throws SQLException {
        Sql.execute(db.app(), command(1, kind, "DONE"));
        Sql.assertRejected("23505", db.app(), command(2, "SUBMIT_REFUND", "PENDING"));
        Sql.assertRejected("23505", db.app(), command(2, "SUBMIT_PAYOUT", "DONE"));
        Sql.execute(db.app(), command(2, "QUERY_REFUND", "PENDING"), command(3, "QUERY_PAYOUT", "PENDING"));
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.outbound_command"))
                .isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({
        "QUERY_REFUND,PENDING",
        "QUERY_REFUND,IN_FLIGHT",
        "QUERY_REFUND,UNKNOWN",
        "QUERY_PAYOUT,PENDING",
        "QUERY_PAYOUT,IN_FLIGHT",
        "QUERY_PAYOUT,UNKNOWN"
    })
    void ac_7_23_onlyOneActiveQueryUntilDoneAndReopeningCannotBypassIndex(String kind, String status)
            throws SQLException {
        Sql.execute(db.app(), command(1, kind, status));
        Sql.assertRejected("23505", db.app(), command(2, kind, "PENDING"));
        Sql.execute(db.app(), "UPDATE io.outbound_command SET status='DONE'", command(2, kind, "PENDING"));
        Sql.assertRejected("23505", db.app(), "UPDATE io.outbound_command SET status='UNKNOWN' WHERE status='DONE'");
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.outbound_command"))
                .isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYOUT_STATE_CONFLICT", "PAYOUT_UNRESOLVED"})
    void ac_7_18_existingIssueChecksAcceptPayoutCategories(String category) throws SQLException {
        Sql.execute(db.app(), """
                INSERT INTO recon.issue(id,category,subject_type,subject_id,detected_by,status,recorded_at,updated_at)
                VALUES ('iss_01JB0000000000000000000001','%s','payout',%s,'AGING','OPEN',%s,%s)
                """.formatted(category, PAYOUT, TIME, TIME));
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM recon.v_issue WHERE subject_type='payout'"))
                .isEqualTo(1);
    }

    private static String payout(Map<String, String> overrides) {
        var values = new LinkedHashMap<String, String>();
        values.put("id", PAYOUT);
        values.put("merchant_payout_ref", "'MP-1'");
        values.put("amount", "9680");
        values.put("currency", "'CAD'");
        values.put("status", "'RESERVED'");
        values.put("initial_result", "'{\"status\":201,\"body\":{\"status\":\"RESERVED\"}}'");
        values.put("request_fingerprint", "'fingerprint'");
        values.put("reserved_at", TIME);
        values.put("updated_at", TIME);
        values.put("recorded_at", TIME);
        return insert("core.payout", values, overrides);
    }

    private static String history(Map<String, String> overrides) {
        var values = new LinkedHashMap<String, String>();
        values.put("payout_id", PAYOUT);
        values.put("from_status", "NULL");
        values.put("to_status", "'RESERVED'");
        values.put("evidence_type", "'api'");
        values.put("evidence_id", "'request-1'");
        values.put("occurred_at", TIME);
        values.put("recorded_at", TIME);
        return insert("core.payout_status_history", values, overrides);
    }

    private static String command(int id, String kind, String status) {
        return """
                INSERT INTO io.outbound_command(id,kind,subject_id,request_id,status,next_attempt_at,created_at,recorded_at)
                VALUES ('cmd_%026d','%s',%s,%s,'%s',%s,%s,%s)
                """.formatted(id, kind, PAYOUT, PAYOUT, status, TIME, TIME, TIME);
    }

    private static void seedBankStatement(TestDatabase.Handle database) throws SQLException {
        Sql.execute(database.app(), """
                INSERT INTO io.imported_file(id,kind,source_name,sha256,raw,statement_date,version,status,received_at,recorded_at)
                VALUES (%1$s,'BANK_STATEMENT','payout.csv',encode(sha256('raw'::bytea),'hex'),'raw',
                    '2026-11-02',1,'ACCEPTED',%2$s,%2$s)
                """.formatted(FILE, TIME), """
                INSERT INTO recon.bank_statement(file_id,account_ref,currency,opening_balance,closing_balance,
                    period_start,period_end,version,recorded_at)
                VALUES (%s,'SIM-CAD-001','CAD',9680,0,'2026-11-02','2026-11-02',1,%s)
                """.formatted(FILE, TIME));
    }

    private static String bankLine(Map<String, String> overrides) {
        var values = new LinkedHashMap<String, String>();
        values.put("bank_txn_id", "'bt_payout'");
        values.put("version", "1");
        values.put("file_id", FILE);
        values.put("direction", "'DEBIT'");
        values.put("currency", "'CAD'");
        values.put("status", "'BOOKED'");
        values.put("amount", "9680");
        values.put("reference", PAYOUT);
        values.put("booking_date", "'2026-11-02'");
        values.put("value_date", "'2026-11-02'");
        values.put("memo", "'Payout fixture'");
        values.put("match_status", "'UNMATCHED_NO_POSTING'");
        values.put("recorded_at", TIME);
        return insert("recon.bank_line", values, overrides);
    }

    private static String insert(String table, Map<String, String> values, Map<String, String> overrides) {
        values.putAll(overrides);
        return "INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES ("
                + String.join(",", values.values()) + ")";
    }
}
