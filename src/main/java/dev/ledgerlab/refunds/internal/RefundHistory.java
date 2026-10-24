package dev.ledgerlab.refunds.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class RefundHistory {
    private final JdbcClient jdbc;

    public RefundHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void append(String id, @Nullable String from, String to, String source, Instant now) {
        jdbc.sql("""
                INSERT INTO core.refund_transition
                    (subject_id, from_status, to_status, evidence_type, evidence_id,
                     evidence_source, evidence_ref, occurred_at, recorded_at)
                VALUES (:id, :from, :to, 'refund', :id, :source, :id, :now, :now)
                """)
                .param("id", id)
                .param("from", from)
                .param("to", to)
                .param("source", source)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }
}
