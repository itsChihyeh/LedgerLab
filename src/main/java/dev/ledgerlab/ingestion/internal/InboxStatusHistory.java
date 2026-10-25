package dev.ledgerlab.ingestion.internal;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Called only after a successful state change, while its original transaction still holds the row lock. */
final class InboxStatusHistory {
    private InboxStatusHistory() {}

    static void append(JdbcClient jdbc, String eventId, @Nullable String fromStatus, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO io.inbox_status_history
                    (event_id, from_status, to_status, result, parked_on, lease_epoch, recorded_at)
                SELECT event_id, :from, status, result, parked_on, lease_epoch, :now
                FROM io.inbox_event WHERE event_id = :id
                """)
                .param("from", fromStatus)
                .param("now", now)
                .param("id", eventId)
                .update();
    }
}
