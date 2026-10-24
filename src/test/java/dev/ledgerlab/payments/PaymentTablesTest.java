package dev.ledgerlab.payments;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-1.13: core payment constraints and least-privilege access through real database roles. */
class PaymentTablesTest {

    private static final String PAYMENT_ID = "pay_00000000000000000000000001";
    private static final String TRANSITION = """
            INSERT INTO core.payment_transition
                (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at)
            VALUES ('pay_00000000000000000000000001', NULL, 'CAPTURED', 'WEBHOOK', 'evt_test', now(), now())
            """;

    private TestDatabase.Handle db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.freshMigrated();
        Sql.execute(db.app(), """
                INSERT INTO io.inbox_event
                    (event_id, event_type, api_version, livemode, payload, payload_sha256,
                     signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
                VALUES ('evt_test', 'charge.succeeded', 'test-version', false,
                    '{}'::bytea, encode(sha256('{}'::bytea), 'hex'),
                    now(), now(), now(), now(), 'RECEIVED', now())
                """);
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    private static String payment(long amount) {
        return """
                INSERT INTO core.payment
                    (id, processor_payment_ref, amount, expected_fee, status,
                     source_event_id, occurred_at, received_at, recorded_at)
                VALUES ('%s', 'ch_test', %d, 0, 'CAPTURED', 'evt_test', now(), now(), now())
                """.formatted(PAYMENT_ID, amount);
    }

    private void seed() throws SQLException {
        Sql.execute(db.app(), payment(10000), TRANSITION);
    }

    @Test
    void ac_1_13_appCanInsertAndUpdateOnlyMutablePaymentFields() throws SQLException {
        seed();
        assertThat(Sql.queryLong(db.app(), "SELECT refunded_amount FROM core.payment"))
                .isZero();
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.payment
                WHERE processor_intent_ref IS NULL AND actual_fee IS NULL AND batch_id IS NULL
                """)).isEqualTo(1);
        Sql.execute(db.migrator(), """
                INSERT INTO core.settlement_batch
                    (batch_id, payout_amount, currency, automatic, method, status,
                     source_event_id, occurred_at, recorded_at, updated_at)
                VALUES ('po_test', 9680, 'cad', true, 'standard', 'ANNOUNCED',
                    'evt_test', '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z', '2026-10-23T00:00:00Z')
                """);
        Sql.assertRejected("23503", db.app(), "UPDATE core.payment SET batch_id = 'po_missing'");
        Sql.execute(db.app(), """
                UPDATE core.payment
                SET status = 'SETTLED', actual_fee = 320, batch_id = 'po_test', refunded_amount = 10000
                """);
        assertThat(Sql.queryLong(db.app(), """
                SELECT count(*) FROM core.payment
                WHERE status = 'SETTLED' AND actual_fee = 320 AND batch_id = 'po_test'
                    AND refunded_amount = 10000 AND amount = 10000 AND expected_fee = 0
                """)).isEqualTo(1);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.payment_transition WHERE id > 0"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE core.payment SET amount = 5000",
                "UPDATE core.payment SET processor_payment_ref = 'ch_other'",
                "UPDATE core.payment SET expected_fee = 320",
                "UPDATE core.payment SET occurred_at = now()",
                "UPDATE core.payment SET id = 'pay_00000000000000000000000002'",
                "UPDATE core.payment SET processor_intent_ref = 'pi_other'",
                "UPDATE core.payment SET source_event_id = 'evt_other'",
                "UPDATE core.payment SET received_at = now()",
                "UPDATE core.payment SET recorded_at = now()",
                "DELETE FROM core.payment",
                "TRUNCATE core.payment CASCADE",
                "UPDATE core.payment_transition SET to_status = 'VOIDED'",
                "DELETE FROM core.payment_transition",
                "TRUNCATE core.payment_transition"
            })
    void ac_1_13_appCannotRewriteProtectedData(String sql) throws SQLException {
        seed();
        Sql.assertRejected("42501", db.app(), sql);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE core.payment_transition SET to_status = 'VOIDED'",
                "DELETE FROM core.payment_transition",
                "TRUNCATE core.payment_transition"
            })
    void ac_1_13_ownerCannotRewriteHistory(String sql) throws SQLException {
        seed();
        Sql.assertRejected("LL004", db.migrator(), sql);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM core.payment_transition WHERE to_status = 'CAPTURED'"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {50, 99999999})
    void ac_1_13_paymentAmountBoundariesAreAccepted(long amount) throws SQLException {
        Sql.execute(db.app(), payment(amount));
        assertThat(Sql.queryLong(db.app(), "SELECT amount FROM core.payment")).isEqualTo(amount);
    }

    @ParameterizedTest
    @ValueSource(longs = {49, 100000000})
    void ac_1_13_outOfRangePaymentAmountsAreRejected(long amount) {
        Sql.assertRejected("23514", db.app(), payment(amount));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "refunded_amount = 10001",
                "refunded_amount = -1",
                "expected_fee = -1",
                "actual_fee = -1",
                "status = 'UNKNOWN'",
                "processor_payment_ref = 'pi_wrong'",
                "processor_payment_ref = 'ch_'",
                "id = 'rfd_00000000000000000000000001'",
                "id = 'pay_123'",
                "id = 'pay_0000000000000000000000000I'"
            })
    void ac_1_13_paymentChecksRejectInvalidValues(String assignment) throws SQLException {
        Sql.execute(db.app(), payment(10000));
        // Owner access isolates constraints from app's column-level permissions.
        Sql.assertRejected("23514", db.migrator(), "UPDATE core.payment SET " + assignment);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "id",
                "processor_payment_ref",
                "amount",
                "expected_fee",
                "status",
                "refunded_amount",
                "source_event_id",
                "occurred_at",
                "received_at",
                "recorded_at"
            })
    void ac_1_13_paymentRequiredFieldsRejectNull(String column) throws SQLException {
        Sql.execute(db.app(), payment(10000));
        Sql.assertRejected("23502", db.migrator(), "UPDATE core.payment SET " + column + " = NULL");
    }

    @Test
    void ac_1_13_processorPaymentAndPaymentIdAreUnique() throws SQLException {
        Sql.execute(db.app(), payment(10000));
        Sql.assertRejected("23505", db.app(), payment(50).replace(PAYMENT_ID, "pay_00000000000000000000000002"));
        Sql.assertRejected("23505", db.app(), payment(50).replace("ch_test", "ch_other"));
    }

    @Test
    void ac_1_13_transitionRequiresExistingPayment() {
        Sql.assertRejected("23503", db.app(), TRANSITION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"from_status", "to_status"})
    void ac_1_13_transitionStatusesRejectUnknownValues(String column) throws SQLException {
        Sql.execute(db.app(), payment(10000));
        Sql.assertRejected("23514", db.app(), transitionWith(column, "'UNKNOWN'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"subject_id", "to_status", "evidence_type", "evidence_id", "occurred_at", "recorded_at"})
    void ac_1_13_transitionRequiredFieldsRejectNull(String column) throws SQLException {
        Sql.execute(db.app(), payment(10000));
        Sql.assertRejected("23502", db.app(), transitionWith(column, "NULL"));
    }

    private static String transitionWith(String column, String value) {
        String[] columns = {
            "subject_id", "from_status", "to_status", "evidence_type", "evidence_id", "occurred_at", "recorded_at"
        };
        String[] values = {"'" + PAYMENT_ID + "'", "NULL", "'CAPTURED'", "'WEBHOOK'", "'evt_test'", "now()", "now()"};
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(column)) {
                values[i] = value;
            }
        }
        return "INSERT INTO core.payment_transition (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", values) + ")";
    }

    @Test
    void ac_1_13_transitionIdentityIsGeneratedAndUnique() throws SQLException {
        seed();
        Sql.execute(db.app(), transitionWith("from_status", "'CAPTURED'"));
        assertThat(Sql.queryLong(db.app(), "SELECT count(DISTINCT id) FROM core.payment_transition"))
                .isEqualTo(2);
        Sql.assertRejected("428C9", db.app(), "INSERT INTO core.payment_transition (id) VALUES (1)");
        Sql.assertRejected("23505", db.migrator(), """
                INSERT INTO core.payment_transition OVERRIDING SYSTEM VALUE
                SELECT * FROM core.payment_transition
                """);
    }

    @Test
    void ac_1_13_readonlyReadsBothViews() throws SQLException {
        seed();
        assertThat(Sql.queryLong(db.readonly(), "SELECT amount FROM core.v_payment"))
                .isEqualTo(10000);
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM core.v_payment_transition"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM core.payment",
                "SELECT * FROM core.payment_transition",
                "INSERT INTO core.v_payment (id) VALUES ('pay_00000000000000000000000002')",
                "UPDATE core.v_payment SET status = 'VOIDED'",
                "DELETE FROM core.v_payment",
                "INSERT INTO core.v_payment_transition (subject_id) VALUES ('pay_00000000000000000000000001')",
                "UPDATE core.v_payment_transition SET to_status = 'VOIDED'",
                "DELETE FROM core.v_payment_transition"
            })
    void ac_1_13_readonlyCannotReadBaseTablesOrWriteViews(String sql) throws SQLException {
        seed();
        Sql.assertRejected("42501", db.readonly(), sql);
    }
}
