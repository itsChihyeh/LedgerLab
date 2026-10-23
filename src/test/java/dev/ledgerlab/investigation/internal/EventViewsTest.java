package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class EventViewsTest {
    @Test
    void ac_1_13_ac_5_15_eventViewsExposeOnlySafeColumnsAndNoWritePrivileges() {
        var db = TestDatabase.freshMigrated();
        try {
            var readonly = JdbcClient.create(db.readonly());
            var columns = Map.of(
                    "v_batch_delivery",
                            List.of(
                                    "id",
                                    "event_id",
                                    "event_type",
                                    "outcome",
                                    "received_at",
                                    "status",
                                    "result",
                                    "batch_id"),
                    "v_event",
                            List.of(
                                    "event_id",
                                    "event_type",
                                    "api_version",
                                    "livemode",
                                    "occurred_at",
                                    "received_at",
                                    "status",
                                    "result",
                                    "subject",
                                    "subject_id"),
                    "v_event_delivery",
                            List.of(
                                    "id",
                                    "event_id",
                                    "event_type",
                                    "outcome",
                                    "received_at",
                                    "status",
                                    "result",
                                    "subject"));
            columns.forEach((view, expected) -> {
                assertThat(readonly.sql("SELECT count(*) FROM core." + view)
                                .query(Long.class)
                                .single())
                        .isZero();
                assertThat(readonly.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema='core' AND table_name=? ORDER BY ordinal_position
                        """).param(view).query(String.class).list())
                        .isEqualTo(expected);
                assertThat(readonly.sql("SELECT viewowner FROM pg_views WHERE schemaname='core' AND viewname=?")
                                .param(view)
                                .query(String.class)
                                .single())
                        .isEqualTo("migrator");
                for (String role : List.of("readonly", "app")) {
                    for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE"))
                        assertThat(readonly.sql("SELECT has_table_privilege(?, ?, ?)")
                                        .params(role, "core." + view, privilege)
                                        .query(Boolean.class)
                                        .single())
                                .isFalse();
                }
                Sql.assertRejected("42501", db.app(), "SELECT * FROM core." + view);
                Sql.assertRejected("42501", db.app(), "ALTER VIEW core." + view + " RENAME TO forbidden");
            });
            Sql.assertRejected("42501", db.readonly(), "UPDATE core.v_event SET status='IGNORED'");
            for (String table : List.of("inbox_event", "webhook_delivery", "inbox_conflict", "processor_fetch"))
                Sql.assertRejected("42501", db.readonly(), "SELECT * FROM io." + table);
            assertThat(readonly.sql("SELECT has_schema_privilege(current_user, 'io', 'USAGE')")
                            .query(Boolean.class)
                            .single())
                    .isFalse();
        } finally {
            db.drop();
        }
    }
}
