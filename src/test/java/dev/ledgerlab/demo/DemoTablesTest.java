package dev.ledgerlab.demo;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** AC-9.6: the demo's only tables hold per-day counts, and the database enforces that and nothing more. */
class DemoTablesTest {
    private static final String COUNT = """
            INSERT INTO demo.action_count(day, action, count, recorded_at)
            VALUES (DATE '2026-11-05', 'pay', 1, now())
            ON CONFLICT (day, action) DO UPDATE SET count = demo.action_count.count + 1
            """;

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
    void ac_9_6_appCountsWithInsertOnConflictUpdateAndNothingElse() throws SQLException {
        Sql.execute(db.app(), COUNT, COUNT, COUNT);
        assertThat(Sql.queryLong(db.migrator(), "SELECT count FROM demo.action_count"))
                .isEqualTo(3);
        Sql.execute(db.app(), """
                INSERT INTO demo.page_view(day, count, recorded_at) VALUES (DATE '2026-11-05', 1, now())
                ON CONFLICT (day) DO UPDATE SET count = demo.page_view.count + 1
                """);
        // The day, the action and the first-write time cannot be rewritten, and nothing can be deleted.
        Sql.assertRejected("42501", db.app(), "UPDATE demo.action_count SET action = 'kill-worker'");
        Sql.assertRejected("42501", db.app(), "UPDATE demo.action_count SET day = DATE '2020-01-01'");
        Sql.assertRejected("42501", db.app(), "UPDATE demo.page_view SET recorded_at = now()");
        Sql.assertRejected("42501", db.app(), "DELETE FROM demo.action_count");
        Sql.assertRejected("42501", db.app(), "DELETE FROM demo.page_view");
        Sql.assertRejected("42501", db.app(), "TRUNCATE demo.action_count");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'drop-all-tables'", "'203.0.113.7'", "''"})
    void ac_9_6_onlyTheEightActionsAndTheRefusalTallyCanBeCounted(String action) {
        Sql.assertRejected("23514", db.app(), """
                INSERT INTO demo.action_count(day, action, count, recorded_at)
                VALUES (DATE '2026-11-05', %s, 1, now())
                """.formatted(action));
    }

    @Test
    void ac_9_6_countsMustBePositive() {
        Sql.assertRejected("23514", db.app(), """
                INSERT INTO demo.page_view(day, count, recorded_at) VALUES (DATE '2026-11-05', 0, now())
                """);
    }

    @Test
    void ac_9_6_readonlyReadsViewsOnlyAndNeverTheInboxItself() throws SQLException {
        Sql.execute(db.app(), COUNT);
        assertThat(Sql.queryLong(db.readonly(), "SELECT count FROM demo.v_action_count"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM demo.v_page_view"))
                .isZero();
        assertThat(Sql.queryLong(db.readonly(), "SELECT count(*) FROM core.v_inbox_status_history"))
                .isZero();
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM demo.action_count");
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM demo.page_view");
        Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io.inbox_status_history");
        Sql.assertRejected("42501", db.readonly(), COUNT);
        Sql.assertRejected("42501", db.readonly(), "DELETE FROM demo.v_action_count");
    }

    @Test
    void ac_9_6_inboxHistoryViewExposesNoPayloadOrError() throws SQLException {
        assertThat(Sql.queryLong(db.readonly(), """
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'core'
                    AND table_name = 'v_inbox_status_history'
                    AND column_name IN ('payload', 'last_error', 'lease_owner')
                """)).isZero();
    }
}
