package dev.ledgerlab.investigation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
final class RefundQueries {
    private final QuerySnapshot snapshot;

    RefundQueries(QuerySnapshot snapshot) {
        this.snapshot = snapshot;
    }

    record Refund(
            String refund_id,
            String payment_ref,
            String merchant_refund_ref,
            long amount,
            String status,
            Instant accepted_at,
            @Nullable Instant submitted_at,
            @Nullable Instant resolved_at,
            @Nullable String cancel_reason,
            @Nullable String failure_reason) {}

    record Transition(
            long id,
            @Nullable String from_status,
            String to_status,
            String evidence_source,
            String evidence_ref,
            Instant occurred_at,
            Instant recorded_at) {}

    record Command(
            String command_id,
            String status,
            int attempts,
            Instant next_attempt_at,
            @Nullable Instant first_sent_at,
            Instant created_at,
            Instant recorded_at,
            @Nullable String last_outcome) {}

    record Detail(Refund refund, List<Transition> history, List<Command> commands, PaymentQueries.AsOf asOf) {}

    Detail detail(String id) {
        return snapshot.read(asOf -> {
            var refund = snapshot.jdbc
                    .sql("""
                    SELECT id, payment_id, merchant_refund_ref, amount, status, accepted_at, submitted_at,
                        resolved_at, cancel_reason, failure_reason FROM core.v_refund WHERE id = :id
                    """)
                    .param("id", id)
                    .query((rs, row) -> new Refund(
                            rs.getString("id"),
                            rs.getString("payment_id"),
                            rs.getString("merchant_refund_ref"),
                            rs.getLong("amount"),
                            rs.getString("status"),
                            rs.getTimestamp("accepted_at").toInstant(),
                            optionalTime(rs, "submitted_at"),
                            optionalTime(rs, "resolved_at"),
                            rs.getString("cancel_reason"),
                            rs.getString("failure_reason")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
            var history = snapshot.jdbc
                    .sql("""
                    SELECT id, from_status, to_status, evidence_source, evidence_ref, occurred_at, recorded_at
                    FROM core.v_refund_transition WHERE subject_id = :id ORDER BY id
                    """)
                    .param("id", id)
                    .query((rs, row) -> new Transition(
                            rs.getLong("id"),
                            rs.getString("from_status"),
                            rs.getString("to_status"),
                            rs.getString("evidence_source"),
                            rs.getString("evidence_ref"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getTimestamp("recorded_at").toInstant()))
                    .list();
            var commands = snapshot.jdbc
                    .sql("""
                    SELECT command_id, status, attempts, next_attempt_at, first_sent_at, created_at, recorded_at, last_outcome
                    FROM core.v_refund_command_summary WHERE refund_id = :id ORDER BY created_at, command_id
                    """)
                    .param("id", id)
                    .query((rs, row) -> new Command(
                            rs.getString("command_id"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            rs.getTimestamp("next_attempt_at").toInstant(),
                            optionalTime(rs, "first_sent_at"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("recorded_at").toInstant(),
                            rs.getString("last_outcome")))
                    .list();
            return new Detail(refund, history, commands, asOf);
        });
    }

    private static @Nullable Instant optionalTime(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
