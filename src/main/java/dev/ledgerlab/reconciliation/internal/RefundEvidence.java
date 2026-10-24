package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** State and execution times come from append-only history, never current refund status. */
final class RefundEvidence {
    private RefundEvidence() {}

    record Refund(
            String id,
            @Nullable String ref,
            long amount,
            Instant accepted,
            String status,
            long transition,
            Instant occurred,
            @Nullable Instant submitted,
            @Nullable String command) {
        List<Map<String, Object>> references() {
            var refs = new java.util.ArrayList<Map<String, Object>>();
            refs.add(Map.of("refund_id", id, "transition_id", transition));
            if (command != null) refs.add(Map.of("command_id", command));
            return refs;
        }
    }

    static List<Refund> read(JdbcClient jdbc, Instant evidenceAsOf) {
        // The processor identity is fixed once bound by a processor result. Do not borrow a
        // later binding for a historical READY/UNKNOWN refund with no processor result yet.
        return jdbc.sql("""
                SELECT r.id, CASE WHEN EXISTS (SELECT 1 FROM core.refund_transition known
                    WHERE known.subject_id=r.id AND known.recorded_at < :asOf
                        AND known.to_status IN ('SUBMITTED','SUCCEEDED','FAILED'))
                    THEN r.processor_refund_ref END AS processor_refund_ref, r.amount, r.accepted_at,
                    h.to_status, h.id AS transition_id, h.occurred_at,
                    coalesce(c.first_sent_at, submitted.occurred_at) AS submitted_at, c.id AS command_id
                FROM core.refund r
                JOIN LATERAL (SELECT id, to_status, occurred_at FROM core.refund_transition
                    WHERE subject_id=r.id AND recorded_at < :asOf
                    ORDER BY recorded_at DESC, id DESC LIMIT 1) h ON true
                LEFT JOIN io.outbound_command c ON c.subject_id=r.id AND c.kind='SUBMIT_REFUND'
                    AND c.recorded_at < :asOf AND c.first_sent_at < :asOf
                LEFT JOIN LATERAL (SELECT occurred_at FROM core.refund_transition
                    WHERE subject_id=r.id AND recorded_at < :asOf AND to_status='SUBMITTED'
                    ORDER BY recorded_at, id LIMIT 1) submitted ON true
                WHERE r.recorded_at < :asOf AND r.accepted_at < :asOf ORDER BY r.id
                """)
                .param("asOf", evidenceAsOf.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> {
                    var submitted = rs.getObject("submitted_at", OffsetDateTime.class);
                    return new Refund(
                            rs.getString("id"),
                            rs.getString("processor_refund_ref"),
                            rs.getLong("amount"),
                            rs.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                            rs.getString("to_status"),
                            rs.getLong("transition_id"),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                            submitted == null ? null : submitted.toInstant(),
                            rs.getString("command_id"));
                })
                .list();
    }
}
