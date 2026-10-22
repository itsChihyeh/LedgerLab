package dev.ledgerlab.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class DeliveryTableTest {
    private TestDatabase.Handle db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.freshMigrated();
        Sql.execute(db.app(), """
                INSERT INTO io.inbox_event (event_id, event_type, api_version, livemode, payload,
                    payload_sha256, signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
                SELECT 'evt_delivery', 'charge.succeeded', 'test', false, p, encode(sha256(p), 'hex'),
                    now(), now(), now(), now(), 'RECEIVED', now()
                FROM (SELECT '{"data":{"object":{"id":"ch_delivery"}}}'::bytea AS p) payload
                """);
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    private static String delivery(String column, String value) {
        String[] columns = {"event_id", "outcome", "payload_sha256", "signed_at", "received_at", "recorded_at"};
        String[] values = {"'evt_delivery'", "'NEW'", "repeat('a', 64)", "now()", "now()", "now()"};
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(column)) values[i] = value;
        }
        return "INSERT INTO io.webhook_delivery (" + String.join(",", columns) + ") VALUES (" + String.join(",", values)
                + ")";
    }

    @Test
    void ac_1_13_rolesSeeOnlyTheirAllowedDeliveryFields() throws SQLException {
        for (String outcome : new String[] {"NEW", "DUPLICATE", "CONFLICT"}) {
            Sql.execute(db.app(), delivery("outcome", "'" + outcome + "'"));
        }
        assertThat(Sql.queryLong(db.app(), "SELECT count(DISTINCT id) FROM io.webhook_delivery"))
                .isEqualTo(3);
        assertThat(Sql.queryLong(db.readonly(), """
                SELECT count(*) FROM core.v_payment_delivery
                WHERE event_id = 'evt_delivery' AND event_type = 'charge.succeeded'
                    AND processor_payment_ref = 'ch_delivery' AND status = 'RECEIVED' AND result IS NULL
                """)).isEqualTo(3);
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.webhook_delivery");
        Sql.assertRejected("42501", db.readonly(), delivery("outcome", "'NEW'"));
        // PostgreSQL rejects a join-view rewrite before checking grants. Check ACLs independently;
        // the direct table operations above and below prove the role boundary with SQLSTATE 42501.
        assertThat(JdbcClient.create(db.readonly())
                        .sql("""
                SELECT has_table_privilege(current_user, 'core.v_payment_delivery', 'INSERT')
                    OR has_table_privilege(current_user, 'core.v_payment_delivery', 'UPDATE')
                    OR has_table_privilege(current_user, 'core.v_payment_delivery', 'DELETE')
                """)
                        .query(Boolean.class)
                        .single())
                .isFalse();
        Sql.assertRejected("42501", db.app(), "ALTER TABLE io.webhook_delivery ADD COLUMN forbidden text");
        assertThat(JdbcClient.create(db.migrator()).sql("""
                SELECT viewowner FROM pg_views WHERE schemaname = 'core' AND viewname = 'v_payment_delivery'
                """).query(String.class).single())
                .isEqualTo("migrator");
        assertThat(JdbcClient.create(db.readonly()).sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'core' AND table_name = 'v_payment_delivery' ORDER BY ordinal_position
                """).query(String.class).list())
                .containsExactly(
                        "id",
                        "event_id",
                        "event_type",
                        "outcome",
                        "received_at",
                        "status",
                        "result",
                        "processor_payment_ref");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE io.webhook_delivery SET outcome = 'DUPLICATE'",
                "DELETE FROM io.webhook_delivery",
                "TRUNCATE io.webhook_delivery"
            })
    void ac_1_13_everyRoleIsPreventedFromRewritingDeliveries(String sql) throws SQLException {
        Sql.execute(db.app(), delivery("outcome", "'NEW'"));
        Sql.assertRejected("42501", db.app(), sql);
        Sql.assertRejected("42501", db.readonly(), sql);
        Sql.assertRejected("LL004", db.migrator(), sql);
        Sql.assertRejected("LL004", db.superuser(), sql);
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.webhook_delivery"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "outcome", "payload_sha256", "signed_at", "received_at", "recorded_at"})
    void ac_1_13_requiredDeliveryFieldsRejectNull(String column) throws SQLException {
        Sql.assertRejected("23502", db.app(), delivery(column, "NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"''", "repeat('a', 63)", "repeat('a', 65)", "repeat('g', 64)", "repeat('a', 63) || E'\\n'"})
    void ac_1_13_deliveryHashMustBe64HexDigits(String hash) throws SQLException {
        Sql.assertRejected("23514", db.app(), delivery("payload_sha256", hash));
    }

    @Test
    void ac_1_13_deliveryOutcomeForeignKeyAndIdentityAreEnforced() throws SQLException {
        Sql.assertRejected("23514", db.app(), delivery("outcome", "'APPLIED'"));
        Sql.assertRejected("23503", db.app(), delivery("event_id", "'evt_missing'"));
        Sql.assertRejected("428C9", db.app(), "INSERT INTO io.webhook_delivery (id) VALUES (1)");
        Sql.execute(db.app(), delivery("payload_sha256", "repeat('A', 64)"));
        Sql.assertRejected("23505", db.migrator(), """
                INSERT INTO io.webhook_delivery OVERRIDING SYSTEM VALUE SELECT * FROM io.webhook_delivery
                """);
    }

    @Test
    void ac_1_14_viewReportsCurrentEventResultAndOnlyChargeReferences() throws SQLException {
        Sql.execute(db.app(), delivery("outcome", "'NEW'"), """
                UPDATE io.inbox_event SET status = 'PROCESSED', result = 'APPLIED' WHERE event_id = 'evt_delivery'
                """);
        assertThat(Sql.queryLong(
                        db.readonly(), "SELECT count(*) FROM core.v_payment_delivery WHERE result = 'APPLIED'"))
                .isEqualTo(1);
        Sql.execute(db.migrator(), "UPDATE io.inbox_event SET event_type = 'refund.updated'");
        assertThat(Sql.queryLong(
                        db.readonly(),
                        "SELECT count(*) FROM core.v_payment_delivery WHERE processor_payment_ref IS NULL"))
                .isEqualTo(1);
    }
}
