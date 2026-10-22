package dev.ledgerlab.ingestion;

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

/** AC-1.13 and AC-2.4 foundations: evidence integrity and database role boundaries. */
class InboxTablesTest {
    private static final String EVENT = """
            INSERT INTO io.inbox_event
                (event_id, event_type, api_version, livemode, payload, payload_sha256,
                 signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
            VALUES ('evt_test', 'charge.succeeded', 'test-version', false,
                'original'::bytea, encode(sha256('original'::bytea), 'hex'),
                now(), now(), now(), now(), 'RECEIVED', now())
            """;
    private TestDatabase.Handle db;

    @BeforeEach
    void setUp() {
        db = TestDatabase.freshMigrated();
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    private static String conflictWith(String column, String value) {
        String[] columns = {"event_id", "payload", "payload_sha256", "signed_at", "received_at", "recorded_at"};
        String[] values = {
            "'evt_test'", "'conflict'::bytea", "encode(sha256('conflict'::bytea), 'hex')", "now()", "now()", "now()"
        };
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(column)) {
                values[i] = value;
            }
        }
        return "INSERT INTO io.inbox_conflict (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", values)
                + ")";
    }

    private void seed() throws SQLException {
        Sql.execute(db.app(), EVENT, conflictWith("event_id", "'evt_test'"));
    }

    @Test
    void ac_1_13_appCanInsertAndUpdateEveryWorkerColumn() throws SQLException {
        seed();
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM io.inbox_event WHERE attempts = 0 AND lease_epoch = 0
                    AND result IS NULL AND lease_owner IS NULL AND lease_until IS NULL
                    AND parked_on IS NULL AND last_error IS NULL AND processed_at IS NULL
                """)).isEqualTo(1);
        Sql.execute(db.app(), """
                UPDATE io.inbox_event SET status = 'PARKED', parked_on = 'ch_missing', attempts = 1,
                    next_attempt_at = '2030-01-01Z', lease_owner = 'worker-1',
                    lease_until = '2030-01-02Z', lease_epoch = 1, last_error = 'missing dependency'
                """);
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM io.inbox_event WHERE status = 'PARKED' AND parked_on = 'ch_missing'
                    AND attempts = 1 AND next_attempt_at = '2030-01-01Z' AND lease_owner = 'worker-1'
                    AND lease_until = '2030-01-02Z' AND lease_epoch = 1 AND last_error = 'missing dependency'
                """)).isEqualTo(1);
        Sql.execute(db.app(), """
                UPDATE io.inbox_event SET status = 'PROCESSED', result = 'APPLIED',
                    parked_on = NULL, processed_at = '2030-01-03Z'
                """);
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM io.inbox_event WHERE status = 'PROCESSED' AND result = 'APPLIED'
                    AND parked_on IS NULL AND processed_at = '2030-01-03Z'
                """)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE io.inbox_event SET payload = 'changed'::bytea",
                "UPDATE io.inbox_event SET payload_sha256 = 'changed'",
                "UPDATE io.inbox_event SET event_id = 'evt_other'",
                "UPDATE io.inbox_event SET event_type = 'other'",
                "UPDATE io.inbox_event SET api_version = 'other'",
                "UPDATE io.inbox_event SET livemode = true",
                "UPDATE io.inbox_event SET signed_at = now()",
                "UPDATE io.inbox_event SET occurred_at = now()",
                "UPDATE io.inbox_event SET received_at = now()",
                "UPDATE io.inbox_event SET recorded_at = now()",
                "DELETE FROM io.inbox_event",
                "TRUNCATE io.inbox_event CASCADE",
                "UPDATE io.inbox_conflict SET payload = 'changed'::bytea",
                "DELETE FROM io.inbox_conflict",
                "TRUNCATE io.inbox_conflict"
            })
    void ac_1_13_appCannotRewriteEvidence(String sql) throws SQLException {
        seed();
        Sql.assertRejected("42501", db.app(), sql);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE io.inbox_conflict SET signed_at = now()",
                "DELETE FROM io.inbox_conflict",
                "TRUNCATE io.inbox_conflict"
            })
    void ac_1_13_ownerCannotRewriteConflicts(String sql) throws SQLException {
        seed();
        Sql.assertRejected("LL004", db.migrator(), sql);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.inbox_conflict"))
                .isEqualTo(1);
    }

    @Test
    void ac_2_4_eventIdIsUniqueAndConflictsRetainMultiplePayloads() throws SQLException {
        seed();
        Sql.assertRejected("23505", db.app(), EVENT);
        Sql.execute(db.app(), conflictWith("event_id", "'evt_test'").replace("conflict'::bytea", "different'::bytea"));
        assertThat(Sql.queryLong(db.app(), "SELECT count(DISTINCT payload) FROM io.inbox_conflict"))
                .isEqualTo(2);
        assertThat(Sql.queryLong(db.app(), "SELECT count(DISTINCT id) FROM io.inbox_conflict WHERE id > 0"))
                .isEqualTo(2);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.inbox_event WHERE payload = 'original'::bytea"))
                .isEqualTo(1);
        Sql.assertRejected("428C9", db.app(), "INSERT INTO io.inbox_conflict (id) VALUES (1)");
        Sql.assertRejected("23505", db.migrator(), """
                INSERT INTO io.inbox_conflict OVERRIDING SYSTEM VALUE SELECT * FROM io.inbox_conflict
                """);
    }

    static Stream<Arguments> statusResults() {
        return Stream.of("RECEIVED", "PARKED", "PROCESSED", "REJECTED", "IGNORED", "DEAD")
                .flatMap(status -> Stream.of(
                                "NULL",
                                "APPLIED",
                                "DUPLICATE_FACT",
                                "BUSINESS_CONFLICT",
                                "INVALID_AMOUNT",
                                "UNSUPPORTED_CURRENCY",
                                "UNSUPPORTED_CAPTURE",
                                "UNSUPPORTED_TYPE",
                                "LIVEMODE")
                        .map(result -> Arguments.of(status, result)));
    }

    @ParameterizedTest
    @MethodSource("statusResults")
    void ac_1_13_statusAndResultMatrixIsEnforced(String status, String result) throws SQLException {
        Sql.execute(db.app(), EVENT);
        boolean absent = result.equals("NULL");
        boolean success = result.equals("APPLIED") || result.equals("DUPLICATE_FACT");
        boolean valid = switch (status) {
            case "PROCESSED" -> success;
            case "REJECTED" -> !absent && !success;
            default -> absent;
        };
        String sql = "UPDATE io.inbox_event SET status = '" + status + "', result = "
                + (absent ? "NULL" : "'" + result + "'") + ", parked_on = "
                + (status.equals("PARKED") ? "'ch_missing'" : "NULL");
        if (valid) {
            Sql.execute(db.app(), sql);
            assertThat(Sql.queryLong(
                            db.app(),
                            "SELECT count(*) FROM io.inbox_event WHERE status = '" + status
                                    + "' AND result IS NOT DISTINCT FROM " + (absent ? "NULL" : "'" + result + "'")))
                    .isEqualTo(1);
        } else {
            Sql.assertRejected("23514", db.app(), sql);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "payload_sha256 = 'incorrect'",
                "payload = 'changed'::bytea",
                "status = 'UNKNOWN'",
                "status = 'REJECTED', result = 'UNKNOWN'",
                "status = 'PARKED'",
                "parked_on = 'ch_wrong'",
                "status = 'PROCESSED', result = 'APPLIED', parked_on = 'ch_wrong'",
                "status = 'REJECTED', result = 'LIVEMODE', parked_on = 'ch_wrong'",
                "status = 'IGNORED', parked_on = 'ch_wrong'",
                "status = 'DEAD', parked_on = 'ch_wrong'",
                "attempts = -1",
                "lease_epoch = -1",
                "event_id = 'ch_wrong'",
                "event_id = 'evtXwrong'"
            })
    void ac_1_13_eventChecksRejectInvalidValues(String assignment) throws SQLException {
        Sql.execute(db.app(), EVENT);
        Sql.assertRejected("23514", db.migrator(), "UPDATE io.inbox_event SET " + assignment);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "event_id",
                "event_type",
                "api_version",
                "livemode",
                "payload",
                "payload_sha256",
                "signed_at",
                "occurred_at",
                "received_at",
                "recorded_at",
                "status",
                "attempts",
                "next_attempt_at",
                "lease_epoch"
            })
    void ac_1_13_eventRequiredFieldsRejectNull(String column) throws SQLException {
        Sql.execute(db.app(), EVENT);
        Sql.assertRejected("23502", db.migrator(), "UPDATE io.inbox_event SET " + column + " = NULL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "payload", "payload_sha256", "signed_at", "received_at", "recorded_at"})
    void ac_1_13_conflictRequiredFieldsRejectNull(String column) throws SQLException {
        Sql.execute(db.app(), EVENT);
        Sql.assertRejected("23502", db.app(), conflictWith(column, "NULL"));
    }

    @Test
    void ac_1_13_conflictHashAndEventReferenceAreChecked() throws SQLException {
        Sql.execute(db.app(), EVENT);
        Sql.assertRejected("23514", db.app(), conflictWith("payload_sha256", "'incorrect'"));
        Sql.assertRejected("23503", db.app(), conflictWith("event_id", "'evt_missing'"));
    }

    @Test
    void ac_1_13_paymentRequiresExistingEvent() throws SQLException {
        String payment = """
                INSERT INTO core.payment (id, processor_payment_ref, amount, expected_fee, status,
                    source_event_id, occurred_at, received_at, recorded_at)
                VALUES ('pay_00000000000000000000000001', 'ch_test', 10000, 0, 'CAPTURED',
                    'evt_test', now(), now(), now())
                """;
        Sql.assertRejected("23503", db.app(), payment);
        Sql.execute(db.app(), EVENT, payment);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.payment")).isEqualTo(1);
    }

    @Test
    void ac_1_13_livemodeEvidenceCanBeRetainedAndRejected() throws SQLException {
        Sql.execute(db.app(), EVENT.replace("false", "true"));
        Sql.execute(db.app(), "UPDATE io.inbox_event SET status = 'REJECTED', result = 'LIVEMODE'");
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM io.inbox_event WHERE livemode AND status = 'REJECTED' AND result = 'LIVEMODE'
                """)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"io.inbox_event", "io.inbox_conflict"})
    void ac_1_13_readonlyCannotReadInbox(String table) throws SQLException {
        seed();
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM " + table);
    }
}
