package dev.ledgerlab.investigation.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
final class PaymentQueries {
    private static final String SUMMARY = """
            SELECT id, processor_payment_ref, amount, status, expected_fee, actual_fee, batch_id FROM core.v_payment
            """;
    private static final RowMapper<Payment> PAYMENT = (rs, row) -> new Payment(
            rs.getString("id"),
            rs.getString("processor_payment_ref"),
            rs.getLong("amount"),
            "CAD",
            rs.getString("status"),
            rs.getLong("expected_fee"));
    private final JdbcClient jdbc;
    private final QuerySnapshot snapshot;
    private final WorkbenchQueries workbench;

    PaymentQueries(QuerySnapshot snapshot, WorkbenchQueries workbench) {
        this.jdbc = snapshot.jdbc;
        this.snapshot = snapshot;
        this.workbench = workbench;
    }

    private record PaymentState(
            Payment payment,
            @Nullable Long actualFee,
            @Nullable String batchId) {}

    PaymentDetail detail(String ref) {
        return snapshot.read(asOf -> {
            PaymentState state = jdbc.sql(SUMMARY + " WHERE id = :ref OR processor_payment_ref = :ref")
                    .param("ref", ref)
                    .query((rs, row) -> new PaymentState(
                            PAYMENT.mapRow(rs, row), rs.getObject("actual_fee", Long.class), rs.getString("batch_id")))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
            Payment payment = state.payment();
            QueryModels.Batch batch = state.batchId() == null ? null : workbench.batchById(state.batchId());
            var banks = state.batchId() == null
                    ? List.<QueryModels.BankLine>of()
                    : workbench.matchedBankLines(state.batchId());
            BankDeposit deposit = banks.isEmpty()
                    ? null
                    : new BankDeposit(
                            banks.getFirst().bankTxnId(),
                            banks.getFirst().amount(),
                            banks.getFirst().bookingDate());
            List<Posting> postings = jdbc.sql("""
                    SELECT e.id AS entry_id, e.entry_type, p.line_no, p.account_code, a.name, p.side, p.amount
                    FROM ledger.v_journal_entry e JOIN ledger.v_posting p ON p.entry_id = e.id
                    JOIN ledger.v_account a ON a.code = p.account_code
                    WHERE (e.subject_type = 'payment' AND e.subject_id = :id)
                       OR (e.subject_type = 'batch' AND e.subject_id = :batch)
                       OR (e.subject_type = 'bank_txn' AND e.subject_id IN
                           (SELECT bank_txn_id FROM recon.v_bank_line WHERE matched_batch_id = :batch))
                    ORDER BY e.seq, p.line_no
                    """)
                    .param("id", payment.id())
                    .param("batch", state.batchId())
                    .query((rs, row) -> new Posting(
                            rs.getString("entry_id"),
                            rs.getString("entry_type"),
                            rs.getInt("line_no"),
                            rs.getInt("account_code"),
                            rs.getString("name"),
                            rs.getString("side"),
                            rs.getLong("amount")))
                    .list();
            List<EventQueries.Delivery> deliveries = jdbc.sql("""
                    SELECT id, event_id, event_type, outcome, status, result, received_at, 'payment' AS subject
                    FROM core.v_payment_delivery WHERE processor_payment_ref = :ref
                    UNION ALL
                    SELECT id, event_id, event_type, outcome, status, result, received_at, 'batch' AS subject
                    FROM core.v_batch_delivery WHERE batch_id = :batch
                    ORDER BY received_at, id
                    """)
                    .param("ref", payment.processorPaymentRef())
                    .param("batch", state.batchId())
                    .query(EventQueries.DELIVERY)
                    .list();
            return new PaymentDetail(
                    payment.id(),
                    payment.processorPaymentRef(),
                    payment.amount(),
                    payment.currency(),
                    payment.status(),
                    payment.expectedFee(),
                    state.actualFee(),
                    state.actualFee() == null ? null : Math.subtractExact(payment.amount(), state.actualFee()),
                    batch == null
                            ? null
                            : new PaymentBatch(
                                    batch.batchId(), batch.status(), batch.settlementDate(), batch.arrivalDate()),
                    deposit,
                    postings,
                    deliveries,
                    asOf);
        });
    }

    PaymentList recent(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        return snapshot.read(asOf -> {
            List<Payment> payments = jdbc.sql(SUMMARY + " ORDER BY recorded_at DESC, id DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(PAYMENT)
                    .list();
            return new PaymentList(payments, asOf);
        });
    }

    record PaymentBatch(
            String batchId,
            String status,
            @Nullable LocalDate settlementDate,
            @Nullable LocalDate arrivalDate) {}

    record BankDeposit(String bankTxnId, long amount, LocalDate bookingDate) {}

    record Payment(
            String id, String processorPaymentRef, long amount, String currency, String status, long expectedFee) {}

    record PaymentDetail(
            String id,
            String processorPaymentRef,
            long amount,
            String currency,
            String status,
            long expectedFee,
            @Nullable Long actualFee,
            @Nullable Long net,
            @Nullable PaymentBatch batch,
            @Nullable BankDeposit bankDeposit,
            List<Posting> postings,
            List<EventQueries.Delivery> deliveries,
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

    record AsOf(long seq, Instant at) {}
}
