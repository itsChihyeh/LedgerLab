package dev.ledgerlab.investigation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
final class RefundQueries {
    private final QuerySnapshot snapshot;
    private final WorkbenchQueries workbench;
    private final ObjectMapper json;

    RefundQueries(QuerySnapshot snapshot, WorkbenchQueries workbench, ObjectMapper json) {
        this.snapshot = snapshot;
        this.workbench = workbench;
        this.json = json;
    }

    record Refund(
            String refund_id,
            String payment_ref,
            String merchant_refund_ref,
            long amount,
            String status,
            Instant accepted_at,
            long queue_seq,
            @Nullable Long queue_position,
            @Nullable String processor_refund_ref,
            @Nullable String command_status,
            @Nullable Integer command_attempts,
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

    record Attempt(
            String command_id,
            int attempt_no,
            Instant sent_at,
            Instant finished_at,
            Instant recorded_at,
            @Nullable Integer response_status,
            String result_code) {}

    record Detail(
            Refund refund,
            List<Transition> history,
            List<Command> commands,
            List<Attempt> attempts,
            List<QueryModels.Entry> entries,
            List<PaymentQueries.Posting> postings,
            PaymentQueries.AsOf asOf) {}

    private static final String REFUNDS = """
            SELECT r.*, q.queue_position, c.status AS command_status, c.attempts AS command_attempts
            FROM core.v_refund r
            LEFT JOIN (SELECT id, row_number() OVER (ORDER BY queue_seq) AS queue_position
                FROM core.v_refund WHERE status='AWAITING_FUNDS') q ON q.id=r.id
            LEFT JOIN LATERAL (SELECT status, attempts FROM core.v_refund_command_summary
                WHERE refund_id=r.id ORDER BY created_at DESC, command_id DESC LIMIT 1) c ON true
            """;
    private static final RowMapper<Refund> REFUND = (rs, row) -> new Refund(
            rs.getString("id"),
            rs.getString("payment_id"),
            rs.getString("merchant_refund_ref"),
            rs.getLong("amount"),
            rs.getString("status"),
            rs.getTimestamp("accepted_at").toInstant(),
            rs.getLong("queue_seq"),
            rs.getObject("queue_position", Long.class),
            rs.getString("processor_refund_ref"),
            rs.getString("command_status"),
            rs.getObject("command_attempts", Integer.class),
            optionalTime(rs, "submitted_at"),
            optionalTime(rs, "resolved_at"),
            rs.getString("cancel_reason"),
            rs.getString("failure_reason"));

    // Caller owns the snapshot, so payment details never combine observations from two transactions.
    List<Refund> forPayment(String id) {
        return snapshot.jdbc
                .sql(REFUNDS + " WHERE r.payment_id=:id ORDER BY r.queue_seq")
                .param("id", id)
                .query(REFUND)
                .list();
    }

    Detail detail(String id) {
        return snapshot.read(asOf -> {
            var refund = snapshot.jdbc
                    .sql(REFUNDS + " WHERE r.id=:id")
                    .param("id", id)
                    .query(REFUND)
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
            var attempts = snapshot.jdbc
                    .sql("""
                    SELECT * FROM core.v_refund_attempt_summary WHERE refund_id=:id
                    ORDER BY command_id, attempt_no
                    """)
                    .param("id", id)
                    .query((rs, row) -> new Attempt(
                            rs.getString("command_id"),
                            rs.getInt("attempt_no"),
                            rs.getTimestamp("sent_at").toInstant(),
                            rs.getTimestamp("finished_at").toInstant(),
                            rs.getTimestamp("recorded_at").toInstant(),
                            rs.getObject("response_status", Integer.class),
                            rs.getString("result_code")))
                    .list();
            var entries = snapshot.jdbc
                    .sql("""
                    SELECT * FROM ledger.v_journal_entry WHERE subject_type='refund' AND subject_id=:id ORDER BY seq
                    """)
                    .param("id", id)
                    .query(QueryModels.entry(json))
                    .list();
            return new Detail(
                    refund,
                    history,
                    commands,
                    attempts,
                    entries,
                    workbench.postings(
                            entries.stream().map(QueryModels.Entry::id).toList()),
                    asOf);
        });
    }

    private static @Nullable Instant optionalTime(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
