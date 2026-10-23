package dev.ledgerlab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReconTablesTest {
    public static final String RUN = "rr_00000000000000000000000001";
    public static final String RUN_SQL = """
            INSERT INTO recon.run (id, kind, status, input_file_ids, input_hashes, rule_version,
                cutoff_at, evidence_as_of, started_at, summary, recorded_at)
            VALUES ('rr_00000000000000000000000001', 'PROCESSOR', 'RUNNING', '[]', '{}', 'v1',
                '2026-10-22T04:00Z', '2026-10-22T05:00Z', '2026-10-23T06:00Z', '{}', '2026-10-23T06:00Z')
            """;
    private static final String ISSUE_SQL = """
            INSERT INTO recon.issue (id, category, subject_type, subject_id, detected_by,
                first_run_id, last_run_id, status, recorded_at, updated_at)
            VALUES ('iss_00000000000000000000000001', 'FEE_MISMATCH', 'payment', 'p1', 'RUN',
                'rr_00000000000000000000000001', 'rr_00000000000000000000000001', 'OPEN', now(), now())
            """;
    private static final String FINDING_SQL = """
            INSERT INTO recon.finding (id, run_id, issue_id, category, subject_type, subject_id,
                expected, actual, evidence_refs, fingerprint, recorded_at)
            VALUES ('fnd_00000000000000000000000001', 'rr_00000000000000000000000001',
                'iss_00000000000000000000000001', 'FEE_MISMATCH', 'payment', 'p1', '175', '205', '[]', repeat('a', 64), now())
            """;
    private static final String TRANSITION_SQL = """
            INSERT INTO recon.issue_transition (id, issue_id, from_status, to_status, run_id,
                reason, explanation, evidence_refs, recorded_at)
            VALUES ('itr_00000000000000000000000001', 'iss_00000000000000000000000001', NULL, 'OPEN',
                'rr_00000000000000000000000001', 'DETECTED', 'run finding', '[]', now())
            """;
    private TestDatabase.Handle db;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "finding:id",
                "finding:run_id",
                "finding:category",
                "finding:subject_type",
                "finding:subject_id",
                "finding:evidence_refs",
                "finding:fingerprint",
                "finding:issue_id",
                "finding:recorded_at",
                "issue_transition:id",
                "issue_transition:issue_id",
                "issue_transition:to_status",
                "issue_transition:reason",
                "issue_transition:explanation",
                "issue_transition:evidence_refs",
                "issue_transition:recorded_at"
            })
    void ac_5_21_historyRequiresItsIdentityAndEvidence(String target) throws SQLException {
        Sql.execute(db.app(), RUN_SQL, ISSUE_SQL, FINDING_SQL, TRANSITION_SQL);
        String[] parts = target.split(":", -1);
        String prefix = parts[0].equals("finding") ? "fnd" : "itr";
        Sql.assertRejected(
                "23502",
                db.app(),
                "INSERT INTO recon." + parts[0]
                        + " SELECT (jsonb_populate_record(NULL::recon." + parts[0]
                        + ", to_jsonb(t) || jsonb_build_object('id','" + prefix + "_00000000000000000000000002','"
                        + parts[1]
                        + "',NULL))).* FROM recon." + parts[0] + " t");
    }

    @Test
    void ac_5_21_dueAtAndReadonlyEvidenceViewEnforceContracts() throws SQLException {
        Sql.execute(db.app(), RUN_SQL, ISSUE_SQL);
        Sql.assertRejected(
                "23514",
                db.app(),
                FINDING_SQL
                        .replace("fingerprint, recorded_at", "fingerprint, due_at, recorded_at")
                        .replace("repeat('a', 64), now()", "repeat('a', 64), now(), now()"));
        Sql.assertRejected("23502", db.app(), "UPDATE recon.issue SET reopen_count=NULL");
        for (var source : new javax.sql.DataSource[] {db.app(), db.readonly()}) {
            // PostgreSQL rejects UNION view writes during rewriting, before its ACL check.
            Sql.assertRejected("55000", source, "DELETE FROM recon.v_input_evidence");
            Sql.assertRejected("55000", source, "INSERT INTO recon.v_input_evidence (id) VALUES ('bad')");
            Sql.assertRejected("55000", source, "UPDATE recon.v_input_evidence SET sha256='bad'");
            assertThat(Sql.queryLong(source, """
                    SELECT count(*) FROM (VALUES ('INSERT'), ('UPDATE'), ('DELETE')) AS p(privilege)
                    WHERE has_table_privilege(current_user, 'recon.v_input_evidence', p.privilege)
                    """)).isZero();
        }
        assertThat(Sql.queryLong(db.migrator(), """
                SELECT count(*) FROM pg_tables WHERE schemaname='recon'
                    AND tablename IN ('run','finding','issue_transition') AND tableowner='migrator'
                """)).isEqualTo(3);
    }

    @BeforeEach
    void setUp() {
        db = TestDatabase.freshMigrated();
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    @Test
    void ac_5_21_migrationGrantsOnlyRequiredWritesAndViewReads() throws SQLException {
        Sql.execute(
                db.app(),
                RUN_SQL,
                ISSUE_SQL,
                FINDING_SQL,
                TRANSITION_SQL,
                "UPDATE recon.run SET status='FINISHED', finished_at=now(), summary='{\"FEE_MISMATCH\":1}'",
                "UPDATE recon.issue SET fingerprint=repeat('b',64), reopen_count=1");
        for (String table : new String[] {"run", "finding", "issue_transition"}) {
            assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM recon.v_" + table))
                    .isEqualTo(1);
            Sql.assertRejected("42501", db.readonly(), "SELECT * FROM recon." + table);
            Sql.assertRejected("42501", db.readonly(), "DELETE FROM recon.v_" + table);
            Sql.assertRejected("42501", db.readonly(), "UPDATE recon.v_" + table + " SET id=id");
            Sql.assertRejected("42501", db.readonly(), "INSERT INTO recon.v_" + table + " (id) VALUES ('bad')");
            Sql.assertRejected("42501", db.app(), "TRUNCATE recon." + table + " CASCADE");
        }
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM recon.v_input_evidence"))
                .isZero();
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.processor_fetch");
        Sql.assertRejected("42501", db.app(), "UPDATE recon.run SET rule_version='v2'");
        Sql.assertRejected("42501", db.app(), "DELETE FROM recon.run");
        for (String table : new String[] {"finding", "issue_transition"}) {
            for (String operation :
                    new String[] {"UPDATE recon.%s SET id=id", "DELETE FROM recon.%s", "TRUNCATE recon.%s"}) {
                Sql.assertRejected("42501", db.app(), operation.formatted(table));
                Sql.assertRejected("LL004", db.migrator(), operation.formatted(table));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id='bad'",
                "kind='OTHER'",
                "status='OTHER'",
                "input_file_ids='{}'",
                "input_hashes='[]'",
                "rule_version=' '",
                "summary='[]'",
                "cutoff_at=evidence_as_of + interval '1 second'"
            })
    void ac_5_9_runChecksRejectInvalidValues(String assignment) throws SQLException {
        Sql.execute(db.app(), RUN_SQL);
        Sql.assertRejected("23514", db.migrator(), "UPDATE recon.run SET " + assignment);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "kind",
                "status",
                "input_file_ids",
                "input_hashes",
                "rule_version",
                "cutoff_at",
                "evidence_as_of",
                "started_at",
                "summary",
                "recorded_at"
            })
    void ac_5_9_runRequiresItsReplayMetadata(String column) throws SQLException {
        Sql.execute(db.app(), RUN_SQL);
        Sql.assertRejected("23502", db.migrator(), "UPDATE recon.run SET " + column + "=NULL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fingerprint='bad'", "reopen_count=-1"})
    void ac_5_21_issueChecksRejectInvalidFingerprintAndCount(String assignment) throws SQLException {
        Sql.execute(db.app(), RUN_SQL, ISSUE_SQL);
        Sql.assertRejected("23514", db.app(), "UPDATE recon.issue SET " + assignment);
    }

    @Test
    void ac_5_21_foreignKeysAndFindingIdentityAreEnforced() throws SQLException {
        Sql.execute(db.app(), RUN_SQL);
        Sql.assertRejected("23505", db.app(), RUN_SQL);
        Sql.assertRejected("23503", db.app(), ISSUE_SQL.replace(RUN, "rr_00000000000000000000000002"));
        Sql.execute(db.app(), ISSUE_SQL);
        Sql.assertRejected("23503", db.app(), "UPDATE recon.issue SET last_run_id='missing'");
        Sql.assertRejected("23503", db.migrator(), "UPDATE recon.issue SET first_run_id='missing'");
        for (String sql : new String[] {FINDING_SQL, TRANSITION_SQL}) {
            Sql.assertRejected("23503", db.app(), sql.replace(RUN, "rr_00000000000000000000000002"));
            Sql.assertRejected(
                    "23503", db.app(), sql.replace("iss_00000000000000000000000001", "iss_00000000000000000000000002"));
            Sql.execute(db.app(), sql);
            Sql.assertRejected("23505", db.app(), sql);
        }
        Sql.assertRejected(
                "23505",
                db.app(),
                FINDING_SQL.replace("fnd_00000000000000000000000001", "fnd_00000000000000000000000002"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "'fnd_00000000000000000000000001'|'bad'",
                "'FEE_MISMATCH'|' '",
                "'payment'|' '",
                "'p1'|' '",
                "'[]'|'{}'",
                "repeat('a', 64)|'bad'",
                "'FEE_MISMATCH'|'TIMING_DIFFERENCE'"
            })
    void ac_5_21_findingChecksRejectInvalidValues(String replacement) throws SQLException {
        Sql.execute(db.app(), RUN_SQL, ISSUE_SQL);
        String[] parts = replacement.split("\\|", -1);
        Sql.assertRejected("23514", db.app(), FINDING_SQL.replace(parts[0], parts[1]));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "'itr_00000000000000000000000001'|'bad'",
                "NULL|'OTHER'",
                "'OPEN'|'OTHER'",
                "'DETECTED'|'OTHER'",
                "'run finding'|' '",
                "'[]'|'{}'"
            })
    void ac_5_21_transitionChecksRejectInvalidValues(String replacement) throws SQLException {
        Sql.execute(db.app(), RUN_SQL, ISSUE_SQL);
        String[] parts = replacement.split("\\|", -1);
        Sql.assertRejected("23514", db.app(), TRANSITION_SQL.replace(parts[0], parts[1]));
    }
}
