package dev.ledgerlab.investigation.internal;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
final class EventQueries {
    static final RowMapper<Delivery> DELIVERY = (rs, row) -> new Delivery(
            rs.getLong("id"),
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("outcome"),
            rs.getTimestamp("received_at").toInstant(),
            rs.getString("status"),
            rs.getString("result"),
            rs.getString("subject"));

    private final QuerySnapshot snapshot;
    private final JdbcClient jdbc;

    EventQueries(QuerySnapshot snapshot) {
        this.snapshot = snapshot;
        this.jdbc = snapshot.jdbc;
    }

    EventDetail detail(String eventId) {
        return snapshot.read(asOf -> {
            EventSummary event = jdbc.sql("""
                    SELECT event_id, event_type, api_version, livemode, occurred_at, received_at,
                           status, result, subject, subject_id
                    FROM core.v_event WHERE event_id = ?
                    """)
                    .param(eventId)
                    .query((rs, row) -> new EventSummary(
                            rs.getString("event_id"),
                            rs.getString("event_type"),
                            rs.getString("api_version"),
                            rs.getBoolean("livemode"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getTimestamp("received_at").toInstant(),
                            rs.getString("status"),
                            rs.getString("result"),
                            rs.getString("subject"),
                            rs.getString("subject_id")))
                    .optional()
                    .orElseThrow(() -> WorkbenchQueries.missing("Event"));
            var deliveries = jdbc.sql("""
                    SELECT id, event_id, event_type, outcome, received_at, status, result, subject
                    FROM core.v_event_delivery WHERE event_id = ? ORDER BY received_at, id
                    """).param(eventId).query(DELIVERY).list();
            return new EventDetail(
                    event.eventId(),
                    event.eventType(),
                    event.apiVersion(),
                    event.livemode(),
                    event.occurredAt(),
                    event.receivedAt(),
                    event.status(),
                    event.result(),
                    event.subject(),
                    event.subjectId(),
                    deliveries,
                    asOf);
        });
    }

    record Delivery(
            long id,
            String eventId,
            String eventType,
            String outcome,
            Instant receivedAt,
            String status,
            @Nullable String result,
            @Nullable String subject) {}

    private record EventSummary(
            String eventId,
            String eventType,
            String apiVersion,
            boolean livemode,
            Instant occurredAt,
            Instant receivedAt,
            String status,
            @Nullable String result,
            @Nullable String subject,
            @Nullable String subjectId) {}

    record EventDetail(
            String eventId,
            String eventType,
            String apiVersion,
            boolean livemode,
            Instant occurredAt,
            Instant receivedAt,
            String status,
            @Nullable String result,
            @Nullable String subject,
            @Nullable String subjectId,
            List<Delivery> deliveries,
            PaymentQueries.AsOf asOf) {}
}
