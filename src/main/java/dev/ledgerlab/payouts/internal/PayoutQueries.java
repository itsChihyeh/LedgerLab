package dev.ledgerlab.payouts.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Readonly views, one repeatable-read snapshot per response. */
@Service
final class PayoutQueries {
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PayoutQueries(@Qualifier("investigationDataSource") DataSource dataSource, Clock clock) {
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    record Payout(
            String payoutId,
            String merchantPayoutRef,
            long amount,
            String currency,
            String status,
            String bankRequestId,
            @Nullable String bankAckRef,
            @Nullable LocalDate bankExecutionDate,
            @Nullable String bankTxnId,
            @Nullable String returnBankTxnId,
            @Nullable String failureReason,
            @Nullable String returnReason,
            Instant reservedAt,
            @Nullable Instant submittedAt,
            @Nullable Instant unknownAt,
            @Nullable Instant acceptedAt,
            @Nullable Instant paidAt,
            @Nullable Instant failedAt,
            @Nullable Instant returnedAt,
            Instant updatedAt,
            Instant recordedAt) {}

    record History(
            long id,
            @Nullable String fromStatus,
            String toStatus,
            String evidenceType,
            String evidenceId,
            Instant occurredAt,
            Instant recordedAt) {}

    record Entry(
            String id,
            long seq,
            String entryType,
            String sourceKey,
            Instant occurredAt,
            Instant recordedAt,
            String subjectType,
            String subjectId,
            @Nullable String reversesEntryId,
            @Nullable String correctionId) {}

    record Posting(String entryId, int lineNo, int accountCode, String side, long amount) {}

    record AsOf(long seq, Instant at) {}

    record PayoutList(List<Payout> payouts, int limit, int offset, AsOf asOf) {}

    record Command(
            String commandId,
            String kind,
            String status,
            int attempts,
            Instant nextAttemptAt,
            @Nullable Instant firstSentAt,
            Instant createdAt,
            Instant recordedAt,
            @Nullable String lastOutcome) {}

    record Attempt(
            String commandId,
            int attemptNo,
            Instant sentAt,
            Instant finishedAt,
            Instant recordedAt,
            @Nullable Integer responseStatus,
            String resultCode) {}

    record Detail(
            Payout payout,
            List<History> history,
            List<Command> commands,
            List<Attempt> attempts,
            List<Entry> entries,
            List<Posting> postings,
            AsOf asOf) {}

    private static @Nullable Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Payout> PAYOUT = (rs, row) -> new Payout(
            rs.getString("id"),
            rs.getString("merchant_payout_ref"),
            rs.getLong("amount"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getString("id"),
            rs.getString("bank_ack_ref"),
            rs.getObject("bank_execution_date", LocalDate.class),
            rs.getString("bank_txn_id"),
            rs.getString("return_bank_txn_id"),
            rs.getString("failure_reason"),
            rs.getString("return_reason"),
            rs.getTimestamp("reserved_at").toInstant(),
            instant(rs, "submitted_at"),
            instant(rs, "unknown_at"),
            instant(rs, "accepted_at"),
            instant(rs, "paid_at"),
            instant(rs, "failed_at"),
            instant(rs, "returned_at"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("recorded_at").toInstant());

    PayoutList list(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
        return read(() -> new PayoutList(
                jdbc.sql("SELECT * FROM core.v_payout ORDER BY reserved_at DESC, id DESC LIMIT :limit OFFSET :offset")
                        .param("limit", limit)
                        .param("offset", offset)
                        .query(PAYOUT)
                        .list(),
                limit,
                offset,
                asOf()));
    }

    Detail detail(String id) {
        return read(() -> {
            var payout = jdbc.sql("SELECT * FROM core.v_payout WHERE id=:id")
                    .param("id", id)
                    .query(PAYOUT)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payout not found"));
            var history = jdbc.sql("SELECT * FROM core.v_payout_status_history WHERE payout_id=:id ORDER BY id")
                    .param("id", id)
                    .query((rs, row) -> new History(
                            rs.getLong("id"),
                            rs.getString("from_status"),
                            rs.getString("to_status"),
                            rs.getString("evidence_type"),
                            rs.getString("evidence_id"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getTimestamp("recorded_at").toInstant()))
                    .list();
            var entries = jdbc.sql(
                            "SELECT * FROM ledger.v_journal_entry WHERE subject_type='payout' AND subject_id=:id ORDER BY seq")
                    .param("id", id)
                    .query((rs, row) -> new Entry(
                            rs.getString("id"),
                            rs.getLong("seq"),
                            rs.getString("entry_type"),
                            rs.getString("source_key"),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getTimestamp("recorded_at").toInstant(),
                            rs.getString("subject_type"),
                            rs.getString("subject_id"),
                            rs.getString("reverses_entry_id"),
                            rs.getString("correction_id")))
                    .list();
            var postings = jdbc.sql("""
                    SELECT p.* FROM ledger.v_posting p JOIN ledger.v_journal_entry e ON e.id=p.entry_id
                    WHERE e.subject_type='payout' AND e.subject_id=:id ORDER BY e.seq, p.line_no
                    """)
                    .param("id", id)
                    .query((rs, row) -> new Posting(
                            rs.getString("entry_id"),
                            rs.getInt("line_no"),
                            rs.getInt("account_code"),
                            rs.getString("side"),
                            rs.getLong("amount")))
                    .list();
            var commands = jdbc.sql("""
                    SELECT * FROM core.v_payout_command_summary WHERE payout_id=:id ORDER BY created_at, command_id
                    """)
                    .param("id", id)
                    .query((rs, row) -> new Command(
                            rs.getString("command_id"),
                            rs.getString("kind"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            rs.getTimestamp("next_attempt_at").toInstant(),
                            instant(rs, "first_sent_at"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("recorded_at").toInstant(),
                            rs.getString("last_outcome")))
                    .list();
            var attempts = jdbc.sql("""
                    SELECT * FROM core.v_payout_attempt_summary WHERE payout_id=:id ORDER BY command_id, attempt_no
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
            return new Detail(payout, history, commands, attempts, entries, postings, asOf());
        });
    }

    private AsOf asOf() {
        return new AsOf(
                jdbc.sql("SELECT coalesce(max(seq),0) FROM ledger.v_journal_entry")
                        .query(Long.class)
                        .single(),
                clock.instant());
    }

    private <T> T read(Supplier<T> query) {
        return Objects.requireNonNull(transaction.execute(tx -> query.get()));
    }
}
