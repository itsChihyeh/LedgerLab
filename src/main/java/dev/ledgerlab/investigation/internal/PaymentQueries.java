package dev.ledgerlab.investigation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

@Service
final class PaymentQueries {
    private static final String SUMMARY = """
            SELECT id, processor_payment_ref, amount, status, expected_fee FROM core.v_payment
            """;
    private static final RowMapper<Payment> PAYMENT = (rs, row) -> new Payment(
            rs.getString("id"),
            rs.getString("processor_payment_ref"),
            rs.getLong("amount"),
            "CAD",
            rs.getString("status"),
            rs.getLong("expected_fee"));
    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;

    PaymentQueries(@Qualifier("investigationDataSource") DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
        snapshot = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    PaymentDetail detail(String ref) {
        return Objects.requireNonNull(snapshot.execute(tx -> {
            AsOf asOf = asOf();
            Payment payment = jdbc.sql(SUMMARY + " WHERE id = :ref OR processor_payment_ref = :ref")
                    .param("ref", ref)
                    .query(PAYMENT)
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
            List<Posting> postings = jdbc.sql("""
                    SELECT e.id AS entry_id, e.entry_type, p.line_no, p.account_code, a.name, p.side, p.amount
                    FROM ledger.v_journal_entry e JOIN ledger.v_posting p ON p.entry_id = e.id
                    JOIN ledger.v_account a ON a.code = p.account_code
                    WHERE e.subject_type = 'payment' AND e.subject_id = :id
                    ORDER BY e.seq, p.line_no
                    """)
                    .param("id", payment.id())
                    .query((rs, row) -> new Posting(
                            rs.getString("entry_id"),
                            rs.getString("entry_type"),
                            rs.getInt("line_no"),
                            rs.getInt("account_code"),
                            rs.getString("name"),
                            rs.getString("side"),
                            rs.getLong("amount")))
                    .list();
            List<Delivery> deliveries = jdbc.sql("""
                    SELECT event_id, event_type, outcome, status, result, received_at
                    FROM core.v_payment_delivery WHERE processor_payment_ref = :ref
                    ORDER BY received_at, id
                    """)
                    .param("ref", payment.processorPaymentRef())
                    .query((rs, row) -> new Delivery(
                            rs.getString("event_id"),
                            rs.getString("event_type"),
                            rs.getString("outcome"),
                            rs.getString("status"),
                            rs.getString("result"),
                            rs.getTimestamp("received_at").toInstant()))
                    .list();
            return new PaymentDetail(
                    payment.id(),
                    payment.processorPaymentRef(),
                    payment.amount(),
                    payment.currency(),
                    payment.status(),
                    payment.expectedFee(),
                    postings,
                    deliveries,
                    asOf);
        }));
    }

    PaymentList recent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return Objects.requireNonNull(snapshot.execute(tx -> {
            AsOf asOf = asOf();
            List<Payment> payments = jdbc.sql(SUMMARY + " ORDER BY recorded_at DESC, id DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(PAYMENT)
                    .list();
            return new PaymentList(payments, asOf);
        }));
    }

    private AsOf asOf() {
        return jdbc.sql("SELECT coalesce(max(seq), 0) AS seq, statement_timestamp() AS at FROM ledger.v_journal_entry")
                .query((rs, row) ->
                        new AsOf(rs.getLong("seq"), rs.getTimestamp("at").toInstant()))
                .single();
    }

    record Payment(
            String id, String processorPaymentRef, long amount, String currency, String status, long expectedFee) {}

    record PaymentDetail(
            String id,
            String processorPaymentRef,
            long amount,
            String currency,
            String status,
            long expectedFee,
            List<Posting> postings,
            List<Delivery> deliveries,
            AsOf asOf) {}

    record PaymentList(List<Payment> payments, AsOf asOf) {}

    record Posting(
            String entryId,
            String entryType,
            int lineNo,
            int accountCode,
            String accountName,
            String side,
            long amount) {}

    record Delivery(
            String eventId,
            String eventType,
            String outcome,
            String status,
            @Nullable String result,
            Instant receivedAt) {}

    record AsOf(long seq, Instant at) {}
}
