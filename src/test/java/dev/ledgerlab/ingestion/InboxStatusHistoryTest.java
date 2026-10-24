package dev.ledgerlab.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.InboxHistoryAssertions;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.util.LinkedHashMap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class InboxStatusHistoryTest {
    private TestDatabase.Handle db;
    private static final String EVENT = """
            INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
            VALUES ('evt_history','payout.paid','test',false,'x'::bytea,encode(sha256('x'::bytea),'hex'),
                '2026-10-21T14:00Z','2026-10-21T14:00Z','2026-10-21T14:00Z','2026-10-21T14:00Z',
                'RECEIVED','2026-10-21T14:00Z')
            """;

    @BeforeEach
    void setup() {
        db = TestDatabase.freshMigrated();
    }

    @AfterEach
    void cleanup() {
        db.drop();
    }

    private static String history(String column, String value) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("event_id", "'evt_history'");
        fields.put("from_status", "NULL");
        fields.put("to_status", "'RECEIVED'");
        fields.put("result", "NULL");
        fields.put("parked_on", "NULL");
        fields.put("lease_epoch", "0");
        fields.put("recorded_at", "'2026-10-21T14:00Z'");
        fields.put("backfilled", "false");
        fields.put(column, value);
        return "INSERT INTO io.inbox_status_history (" + String.join(",", fields.keySet()) + ") VALUES ("
                + String.join(",", fields.values()) + ")";
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE io.inbox_status_history SET lease_epoch=1",
                "DELETE FROM io.inbox_status_history",
                "TRUNCATE io.inbox_status_history"
            })
    void ac_1_13_appAndOwnerCannotRewriteHistory(String sql) throws Exception {
        Sql.execute(db.app(), EVENT, history("lease_epoch", "0"));
        assertThat(Sql.queryLong(db.app(), "SELECT count(*) FROM io.inbox_status_history WHERE NOT backfilled"))
                .isEqualTo(1);
        Sql.assertRejected("42501", db.app(), sql);
        Sql.assertRejected("LL004", db.migrator(), sql);
    }

    @Test
    void ac_1_13_readonlyCannotAccessHistory() {
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.inbox_status_history");
        Sql.assertRejected("42501", db.readonly(), history("lease_epoch", "0"));
    }

    @Test
    void ac_4_10_consistencyGuardDetectsMissingHistoryAndChangedDependency() throws Exception {
        Sql.execute(db.app(), EVENT);
        var jdbc = JdbcClient.create(db.app());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> InboxHistoryAssertions.assertConsistent(jdbc))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("evt_history");
        Sql.execute(db.app(), history("lease_epoch", "0"));
        InboxHistoryAssertions.assertConsistent(jdbc);
        Sql.execute(db.app(), "UPDATE io.inbox_event SET status='PARKED',parked_on='batch:po_a'");
        Sql.execute(
                db.app(), history("to_status", "'PARKED'").replace("'PARKED',NULL,NULL", "'PARKED',NULL,'batch:po_a'"));
        InboxHistoryAssertions.assertConsistent(jdbc);
        Sql.execute(db.app(), "UPDATE io.inbox_event SET parked_on='batch:po_b'");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> InboxHistoryAssertions.assertConsistent(jdbc))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("evt_history");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            value = {
                "event_id|'evt_missing'|23503", "from_status|'UNKNOWN'|23514", "to_status|'UNKNOWN'|23514",
                "result|'UNKNOWN'|23514", "result|'APPLIED'|23514", "parked_on|'batch:po_missing'|23514",
                "to_status|'PARKED'|23514", "to_status|'PROCESSED'|23514", "to_status|'REJECTED'|23514",
                "lease_epoch|-1|23514", "event_id|NULL|23502", "to_status|NULL|23502",
                "lease_epoch|NULL|23502", "recorded_at|NULL|23502", "backfilled|NULL|23502"
            })
    void ac_1_13_historyConstraintsAreEnforced(String column, String value, String state) throws Exception {
        Sql.execute(db.app(), EVENT);
        Sql.assertRejected(state, db.app(), history(column, value));
    }

    @Test
    void ac_4_10_migrationBackfillsCurrentStateAtKnownTimestampOnly() throws Exception {
        var old = TestDatabase.freshBlank();
        try {
            Flyway.configure()
                    .dataSource(old.migrator())
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .target("12")
                    .load()
                    .migrate();
            Sql.execute(old.app(), EVENT, EVENT.replace("evt_history", "evt_done"));
            Sql.execute(old.app(), """
                    UPDATE io.inbox_event SET status='PARKED',parked_on='batch:po_missing',lease_epoch=3
                    WHERE event_id='evt_history'
                    """, """
                    UPDATE io.inbox_event SET status='IGNORED',processed_at='2026-10-21T15:00Z'
                    WHERE event_id='evt_done'
                    """);
            Flyway.configure()
                    .dataSource(old.migrator())
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .load()
                    .migrate();
            assertThat(Sql.queryLong(
                            old.app(),
                            "SELECT count(*) FROM io.inbox_status_history WHERE backfilled AND from_status IS NULL"))
                    .isEqualTo(2);
            assertThat(Sql.queryLong(old.app(), """
                    SELECT count(*) FROM io.inbox_status_history WHERE event_id='evt_history'
                        AND to_status='PARKED' AND parked_on='batch:po_missing' AND lease_epoch=3
                        AND recorded_at='2026-10-21T14:00Z'
                    """)).isEqualTo(1);
            assertThat(Sql.queryLong(old.app(), """
                    SELECT count(*) FROM io.inbox_status_history WHERE event_id='evt_done'
                        AND to_status='IGNORED' AND recorded_at='2026-10-21T15:00Z'
                    """)).isEqualTo(1);
            InboxHistoryAssertions.assertConsistent(JdbcClient.create(old.app()));
        } finally {
            old.drop();
        }
    }
}
