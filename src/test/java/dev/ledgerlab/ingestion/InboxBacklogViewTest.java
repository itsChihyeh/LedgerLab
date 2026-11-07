package dev.ledgerlab.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** V24: the metric view over the inbox, and nothing more (ADR-0014). */
class InboxBacklogViewTest {

    @Test
    void ac_12_5_backlogViewExposesThreeColumnsAndOnlyUnfinishedRows() throws Exception {
        var db = TestDatabase.freshMigrated();
        try {
            insert(db, "evt_a", "RECEIVED");
            insert(db, "evt_b", "PARKED");
            insert(db, "evt_c", "IGNORED");
            var readonly = JdbcClient.create(db.readonly());

            assertThat(readonly.sql("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema='core' AND table_name='v_inbox_backlog' ORDER BY ordinal_position
                    """).query(String.class).list())
                    .isEqualTo(List.of("status", "received_at", "next_attempt_at"));
            assertThat(readonly.sql("SELECT status FROM core.v_inbox_backlog ORDER BY status")
                            .query(String.class)
                            .list())
                    .containsExactly("PARKED", "RECEIVED");
            assertThat(readonly.sql("SELECT viewowner FROM pg_views WHERE viewname='v_inbox_backlog'")
                            .query(String.class)
                            .single())
                    .isEqualTo("migrator");
        } finally {
            db.drop();
        }
    }

    @Test
    void ac_12_5_readonlyStillCannotReachTheInboxTableOrWriteTheView() {
        var db = TestDatabase.freshMigrated();
        try {
            Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.inbox_event");
            Sql.assertRejected("42501", db.readonly(), "DELETE FROM core.v_inbox_backlog");
            Sql.assertRejected("42501", db.readonly(), "UPDATE core.v_inbox_backlog SET status='DEAD'");
            // app keeps its table grants and gets nothing new on the view.
            Sql.assertRejected("42501", db.app(), "SELECT * FROM core.v_inbox_backlog");
            Sql.assertRejected("42501", db.app(), "DELETE FROM core.v_inbox_backlog");
        } finally {
            db.drop();
        }
    }

    private static void insert(TestDatabase.Handle db, String id, String status) throws Exception {
        String parkedOn = status.equals("PARKED") ? "'payment'" : "NULL";
        Sql.execute(db.superuser(), """
                INSERT INTO io.inbox_event(event_id, event_type, api_version, livemode, payload, payload_sha256,
                    signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at, parked_on)
                VALUES ('%s', 'charge.succeeded', 'v', false, 'x'::bytea, encode(sha256('x'::bytea), 'hex'),
                    now(), now(), now(), now(), '%s', now(), %s)
                """.formatted(id, status, parkedOn));
    }
}
