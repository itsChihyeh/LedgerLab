package dev.ledgerlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.simple.JdbcClient;

/** One SQL snapshot checks even events that have never reached a terminal status. */
public final class InboxHistoryAssertions {
    private InboxHistoryAssertions() {}

    public static void assertConsistent(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                SELECT e.event_id FROM io.inbox_event e LEFT JOIN LATERAL (
                    SELECT to_status, parked_on FROM io.inbox_status_history
                    WHERE event_id=e.event_id ORDER BY recorded_at DESC, id DESC LIMIT 1
                ) h ON true
                WHERE h.to_status IS DISTINCT FROM e.status OR h.parked_on IS DISTINCT FROM e.parked_on
                ORDER BY e.event_id
                """).query(String.class).list())
                .as("latest inbox history agrees with current status and dependency")
                .isEmpty();
    }
}
