package dev.ledgerlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-1.13: issue constraints and privileges enforced by real PostgreSQL connections. */
class IssueTableTest {
    private static final String ISSUE = """
            INSERT INTO recon.issue (id, category, subject_type, subject_id, detected_by,
                status, recorded_at, updated_at)
            VALUES ('iss_00000000000000000000000001', 'EVENT_CONTENT_CONFLICT', 'event',
                'evt_test', 'INGESTION', 'OPEN', now(), now())
            """;
    private TestDatabase.Handle db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.freshMigrated();
        Sql.execute(
                db.app(),
                ReconTablesTest.RUN_SQL,
                ReconTablesTest.RUN_SQL.replace(ReconTablesTest.RUN, "rr_00000000000000000000000002"));
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    @Test
    void ac_1_13_appCanInsertAndUpdateAllDispositionColumns() throws SQLException {
        Sql.execute(db.app(), ISSUE.replace("'INGESTION'", "'AGING'"));
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM recon.issue WHERE evidence_refs = '[]'::jsonb
                    AND expected IS NULL AND actual IS NULL AND first_run_id IS NULL
                    AND last_run_id IS NULL AND explanation IS NULL AND resolution_correction_id IS NULL
                """)).isEqualTo(1);
        Sql.execute(db.app(), """
                UPDATE recon.issue SET status = 'RESOLVED', last_run_id = 'rr_00000000000000000000000002',
                    explanation = 'corrected', resolution_correction_id = 'cor_test',
                    updated_at = '2030-01-01Z'
                """);
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM recon.issue WHERE status = 'RESOLVED' AND last_run_id = 'rr_00000000000000000000000002'
                    AND explanation = 'corrected' AND resolution_correction_id = 'cor_test'
                    AND updated_at = '2030-01-01Z'
                """)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE recon.issue SET category = 'LIVEMODE_EVENT'",
                "UPDATE recon.issue SET subject_id = 'evt_other'",
                "UPDATE recon.issue SET subject_type = 'payment'",
                "UPDATE recon.issue SET detected_by = 'AGING'",
                "UPDATE recon.issue SET first_run_id = 'rr_test'",
                "UPDATE recon.issue SET recorded_at = now()",
                "UPDATE recon.issue SET id = 'iss_00000000000000000000000002'",
                "DELETE FROM recon.issue",
                "TRUNCATE recon.issue"
            })
    void ac_1_13_appCannotRewriteIssueIdentity(String sql) throws SQLException {
        Sql.execute(db.app(), ISSUE);
        Sql.assertRejected("42501", db.app(), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expected", "actual", "evidence_refs"})
    void ac_5_21_appCanRefreshCurrentContentButCannotRewriteHistory(String column) throws SQLException {
        Sql.execute(db.app(), ISSUE.replace("'INGESTION'", "'AGING'"));
        Sql.execute(db.app(), "UPDATE recon.issue SET " + column + " = '[206]'::jsonb");
        assertThat(Sql.queryLong(
                        db.readonly(), "SELECT count(*) FROM recon.v_issue WHERE " + column + " = '[206]'::jsonb"))
                .isEqualTo(1);
        Sql.assertRejected("42501", db.readonly(), "UPDATE recon.v_issue SET " + column + " = '[]'::jsonb");
        Sql.assertRejected("42501", db.app(), "UPDATE recon.finding SET " + column + " = '[]'::jsonb");
    }

    @Test
    void ac_1_13_readonlyReadsEveryViewColumn() throws SQLException {
        Sql.execute(db.app(), ISSUE);
        try (var connection = db.readonly().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM recon.v_issue")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getMetaData().getColumnCount()).isEqualTo(17);
            assertThat(rows.getString("id")).isEqualTo("iss_00000000000000000000000001");
            assertThat(rows.getString("category")).isEqualTo("EVENT_CONTENT_CONFLICT");
            assertThat(rows.getString("evidence_refs")).isEqualTo("[]");
            assertThat(rows.next()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM recon.issue",
                "INSERT INTO recon.v_issue (id) VALUES ('iss_00000000000000000000000002')",
                "UPDATE recon.v_issue SET status = 'OPEN'",
                "DELETE FROM recon.v_issue"
            })
    void ac_1_13_readonlyCannotReadBaseTableOrWriteView(String sql) throws SQLException {
        Sql.execute(db.app(), ISSUE);
        Sql.assertRejected("42501", db.readonly(), sql);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "category = 'UNKNOWN'",
                "status = 'UNKNOWN'",
                "detected_by = 'UNKNOWN'",
                "subject_type = ''",
                "subject_type = E' \\t\\n'",
                "subject_id = ''",
                "subject_id = E' \\t\\n'",
                "id = 'pay_00000000000000000000000001'",
                "id = 'iss_123'",
                "id = 'iss_0000000000000000000000000I'",
                "evidence_refs = '{}'::jsonb",
                "evidence_refs = 'null'::jsonb",
                "evidence_refs = '1'::jsonb",
                "evidence_refs = '\"ref\"'::jsonb"
            })
    void ac_1_13_checksRejectInvalidValues(String assignment) throws SQLException {
        Sql.execute(db.app(), ISSUE);
        Sql.assertRejected("23514", db.migrator(), "UPDATE recon.issue SET " + assignment);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "category",
                "subject_type",
                "subject_id",
                "detected_by",
                "status",
                "evidence_refs",
                "recorded_at",
                "updated_at"
            })
    void ac_1_13_requiredColumnsRejectNull(String column) throws SQLException {
        Sql.execute(db.app(), ISSUE);
        Sql.assertRejected("23502", db.migrator(), "UPDATE recon.issue SET " + column + " = NULL");
    }

    @Test
    void ac_1_13_identityAndPrimaryKeyAreUnique() throws SQLException {
        Sql.execute(db.app(), ISSUE);
        Sql.assertRejected("23505", db.app(), ISSUE.replace("00000001", "00000002"));
        Sql.assertRejected("23505", db.app(), ISSUE.replace("evt_test", "evt_other"));
        Sql.execute(db.app(), ISSUE.replace("00000001", "00000002").replace("evt_test", "evt_other"));
        Sql.execute(db.app(), ISSUE.replace("00000001", "00000003").replace("'event'", "'payment'"));
        Sql.execute(
                db.app(), ISSUE.replace("00000001", "00000004").replace("EVENT_CONTENT_CONFLICT", "LIVEMODE_EVENT"));
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM recon.issue")).isEqualTo(4);
    }

    static Stream<Arguments> detectionRuns() {
        return Stream.of("RUN", "INGESTION", "AGING")
                .flatMap(source -> Stream.of(false, true)
                        .flatMap(first -> Stream.of(false, true).map(last -> Arguments.of(source, first, last))));
    }

    @ParameterizedTest
    @MethodSource("detectionRuns")
    void ac_1_13_detectionRunMatrixIsEnforced(String source, boolean first, boolean last) throws SQLException {
        String sql = ISSUE.replace("status, recorded_at", "first_run_id, last_run_id, status, recorded_at")
                .replace(
                        "'INGESTION', 'OPEN'",
                        "'" + source + "', " + (first ? "'rr_00000000000000000000000001'" : "NULL") + ", "
                                + (last ? "'rr_00000000000000000000000002'" : "NULL") + ", 'OPEN'");
        boolean valid = switch (source) {
            case "RUN" -> first;
            case "INGESTION" -> !first && !last;
            default -> true;
        };
        if (valid) {
            Sql.execute(db.app(), sql);
            assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM recon.issue"))
                    .isEqualTo(1);
        } else {
            Sql.assertRejected("23514", db.app(), sql);
        }
    }

    static Stream<Arguments> dispositions() {
        return Stream.of("OPEN", "EXPLAINED", "RESOLVED", "ACCEPTED")
                .flatMap(status -> Stream.of(false, true)
                        .flatMap(explanation -> Stream.of(false, true)
                                .map(correction -> Arguments.of(status, explanation, correction))));
    }

    @ParameterizedTest
    @MethodSource("dispositions")
    void ac_1_13_statusExplanationMatrixIsEnforced(String status, boolean explanation, boolean correction)
            throws SQLException {
        String sql = ISSUE.replace("status, recorded_at", "explanation, resolution_correction_id, status, recorded_at")
                .replace(
                        "'OPEN'",
                        (explanation ? "'evidence arrived'" : "NULL") + ", " + (correction ? "'cor_test'" : "NULL")
                                + ", '" + status + "'");
        boolean valid = switch (status) {
            case "OPEN" -> true;
            case "RESOLVED" -> explanation || correction;
            default -> explanation;
        };
        if (valid) {
            Sql.execute(db.app(), sql);
            assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM recon.issue"))
                    .isEqualTo(1);
        } else {
            Sql.assertRejected("23514", db.app(), sql);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "MISSING_IN_LEDGER", "MISSING_IN_STATEMENT", "AMOUNT_MISMATCH", "FEE_MISMATCH",
                "DUPLICATE_STATEMENT_LINE", "BATCH_TOTAL_MISMATCH", "REFUND_NOT_IN_STATEMENT", "DEPOSIT_MISSING",
                "DEPOSIT_AMOUNT_MISMATCH", "UNMATCHED_BANK_LINE", "UNAPPLIED_CASH_OPEN", "BANK_BALANCE_MISMATCH",
                "BATCH_VERSION_CONFLICT", "STATEMENT_VERSION_CONFLICT", "TIMING_DIFFERENCE", "PAYOUT_STATE_CONFLICT",
                "BATCH_STATE_CONFLICT", "REFUND_STATE_CONFLICT", "UNSUPPORTED_PAYOUT", "EVENT_CONTENT_CONFLICT",
                "EVENT_BUSINESS_CONFLICT", "UNSUPPORTED_EVENT", "LIVEMODE_EVENT", "PAYMENT_NOT_SETTLED",
                "DEPOSIT_NOT_RECEIVED", "REFUND_AWAITING_FUNDS_AGED", "REFUND_NOT_EXECUTED", "PAYOUT_UNRESOLVED",
                "EVENT_PARKED_AGED"
            })
    void ac_1_13_documentedCategoriesCanBeInsertedWithoutRunForIngestion(String category) throws SQLException {
        Sql.execute(db.app(), ISSUE.replace("EVENT_CONTENT_CONFLICT", category));
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM recon.issue WHERE detected_by = 'INGESTION'
                    AND first_run_id IS NULL AND last_run_id IS NULL
                """)).isEqualTo(1);
    }

    @Test
    void ac_1_13_jsonEvidenceCanBeRetained() throws SQLException {
        Sql.execute(
                db.app(),
                ISSUE.replace("status, recorded_at", "expected, actual, evidence_refs, status, recorded_at")
                        .replace("'OPEN'", "'{\"amount\":100}', '{\"amount\":99}', '[\"evt_test\"]', 'OPEN'"));
        assertThat(Sql.queryLong(db.readonly(), """
                SELECT count(*) FROM recon.v_issue WHERE expected = '{"amount":100}'::jsonb
                    AND actual = '{"amount":99}'::jsonb AND evidence_refs = '["evt_test"]'::jsonb
                """)).isEqualTo(1);
    }
}
