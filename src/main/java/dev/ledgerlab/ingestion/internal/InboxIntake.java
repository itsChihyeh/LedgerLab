package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.reconciliation.IngestionIssues;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
final class InboxIntake {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IngestionIssues issues;
    private final ObjectMapper mapper;
    private final Clock clock;

    InboxIntake(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IngestionIssues issues,
            ObjectMapper mapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
    }

    void receive(IncomingEvent event, byte[] body, Instant signedAt) {
        String hash = sha256(body);
        transactions.executeWithoutResult(status -> {
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            int inserted = jdbc.sql("""
                    INSERT INTO io.inbox_event
                        (event_id, event_type, api_version, livemode, payload, payload_sha256,
                         signed_at, occurred_at, received_at, recorded_at, next_attempt_at, status)
                    VALUES (:id, :type, :version, :live, :payload, :hash, :signed, :occurred, :now, :now, :now, 'RECEIVED')
                    ON CONFLICT (event_id) DO NOTHING
                    """)
                    .param("id", event.id())
                    .param("type", event.type())
                    .param("version", event.apiVersion())
                    .param("live", event.livemode())
                    .param("payload", body)
                    .param("hash", hash)
                    .param("signed", signedAt.atOffset(ZoneOffset.UTC))
                    .param("occurred", event.occurredAt().atOffset(ZoneOffset.UTC))
                    .param("now", now)
                    .update();
            if (inserted == 1) {
                recordDelivery(event.id(), "NEW", hash, signedAt, now);
                return;
            }
            String originalHash = jdbc.sql("SELECT payload_sha256 FROM io.inbox_event WHERE event_id = :id")
                    .param("id", event.id())
                    .query(String.class)
                    .single();
            if (originalHash.equals(hash)) {
                recordDelivery(event.id(), "DUPLICATE", hash, signedAt, now);
                return;
            }
            long conflictId = jdbc.sql("""
                    INSERT INTO io.inbox_conflict
                        (event_id, payload, payload_sha256, signed_at, received_at, recorded_at)
                    VALUES (:id, :payload, :hash, :signed, :now, :now)
                    RETURNING id
                    """)
                    .param("id", event.id())
                    .param("payload", body)
                    .param("hash", hash)
                    .param("signed", signedAt.atOffset(ZoneOffset.UTC))
                    .param("now", now)
                    .query(Long.class)
                    .single();
            issues.open(
                    "EVENT_CONTENT_CONFLICT",
                    "event",
                    event.id(),
                    mapper.writeValueAsString(Map.of("payload_sha256", originalHash)),
                    mapper.writeValueAsString(Map.of("payload_sha256", hash)),
                    mapper.writeValueAsString(List.of(
                            Map.of("table", "io.inbox_event", "id", event.id()),
                            Map.of("table", "io.inbox_conflict", "id", conflictId))));
            recordDelivery(event.id(), "CONFLICT", hash, signedAt, now);
        });
    }

    private void recordDelivery(String eventId, String outcome, String hash, Instant signedAt, OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO io.webhook_delivery
                    (event_id, outcome, payload_sha256, signed_at, received_at, recorded_at)
                VALUES (:id, :outcome, :hash, :signed, :now, :now)
                """)
                .param("id", eventId)
                .param("outcome", outcome)
                .param("hash", hash)
                .param("signed", signedAt.atOffset(ZoneOffset.UTC))
                .param("now", now)
                .update();
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
