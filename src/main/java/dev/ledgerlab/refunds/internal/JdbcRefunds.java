package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.refunds.RefundException;
import dev.ledgerlab.refunds.RefundQueue;
import dev.ledgerlab.refunds.RefundResult;
import dev.ledgerlab.refunds.Refunds;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public final class JdbcRefunds implements Refunds {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PaymentRefunds payments;
    private final RefundLimitGuard limits;
    private final RefundQueue queue;
    private final RefundHistory history;
    private final Clock clock;
    private final IdGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Runnable refundInserted;

    @Autowired
    public JdbcRefunds(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentRefunds payments,
            RefundQueue queue,
            RefundHistory history,
            Clock clock,
            IdGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this(
                jdbc,
                ledger,
                payments,
                new LockedRefundLimitGuard(ledger, payments),
                queue,
                history,
                clock,
                ids,
                mapper,
                manager,
                () -> {});
    }

    /** Package-local controls for rollback and lost-update tests; production has no fault flag. */
    JdbcRefunds(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentRefunds payments,
            RefundLimitGuard limits,
            RefundQueue queue,
            RefundHistory history,
            Clock clock,
            IdGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            Runnable refundInserted) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.limits = limits;
        this.queue = queue;
        this.history = history;
        this.clock = clock;
        this.ids = ids;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.refundInserted = refundInserted;
    }

    @Override
    public RefundResult accept(String paymentId, String merchantRefundRef, long amount, String requestFingerprint) {
        if (amount < 1 || amount > 99999999) throw new RefundException("INVALID_AMOUNT");
        if (merchantRefundRef.isBlank() || merchantRefundRef.codePointCount(0, merchantRefundRef.length()) > 64) {
            throw new RefundException("INVALID_MERCHANT_REFUND_REF");
        }
        return Objects.requireNonNull(transactions.execute(tx -> {
            limits.reserve(paymentId, amount);
            Instant now = clock.instant();
            String id = ids.next("rfd");
            // Newest FIFO member is covered iff the post-acceptance available balance is nonnegative.
            // Persist the actual first response at INSERT: initial_result is immutable for app (V15).
            long available = jdbc.sql("SELECT balance FROM ledger.account WHERE code = 2102")
                    .query(Long.class)
                    .single();
            String initialStatus = Math.subtractExact(available, amount) >= 0 ? "READY" : "AWAITING_FUNDS";
            var initialBody = Map.of(
                    "refund_id", id,
                    "status", initialStatus,
                    "amount", amount,
                    "payment_ref", paymentId,
                    "merchant_refund_ref", merchantRefundRef,
                    "accepted_at", now.toString());
            long seq;
            try {
                seq = jdbc.sql("""
                        INSERT INTO core.refund (id, payment_id, merchant_refund_ref, amount, status,
                            initial_result, request_fingerprint, accepted_at, recorded_at)
                        VALUES (:id, :payment, :ref, :amount, 'AWAITING_FUNDS',
                            CAST(:result AS jsonb), :fingerprint, :now, :now)
                        RETURNING queue_seq
                        """)
                        .param("id", id)
                        .param("payment", paymentId)
                        .param("ref", merchantRefundRef)
                        .param("amount", amount)
                        .param("result", mapper.writeValueAsString(Map.of("status", 201, "body", initialBody)))
                        .param("fingerprint", requestFingerprint)
                        .param("now", now.atOffset(ZoneOffset.UTC))
                        .query(Long.class)
                        .single();
            } catch (DuplicateKeyException e) {
                throw new RefundException("DUPLICATE_REFUND_REFERENCE", e);
            }
            history.append(id, null, "AWAITING_FUNDS", "API", now);
            refundInserted.run();
            post(id, amount, EntryType.REFUND_ACCEPTED, "accepted", now);
            queue.release();
            RefundResult result = result(id);
            if (!result.equals(new RefundResult(id, initialStatus, amount, seq)))
                throw new IllegalStateException("Initial refund result differs from FIFO release");
            return result;
        }));
    }

    @Override
    public RefundResult cancel(String refundId, String reason) {
        if (reason.isBlank()) throw new RefundException("INVALID_CANCEL_REASON");
        return Objects.requireNonNull(transactions.execute(tx -> {
            ledger.lockFunds();
            String paymentId = jdbc.sql("SELECT payment_id FROM core.refund WHERE id = :id")
                    .param("id", refundId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new RefundException("REFUND_NOT_FOUND"));
            var payment = payments.lock(paymentId).orElseThrow();
            jdbc.sql("SELECT id FROM core.refund WHERE id = :id FOR UPDATE")
                    .param("id", refundId)
                    .query(String.class)
                    .single();
            RefundResult refund = result(refundId);
            if (!refund.status().equals("AWAITING_FUNDS")) throw new RefundException("REFUND_NOT_CANCELLABLE");
            Instant now = clock.instant();
            jdbc.sql(
                            "UPDATE core.refund SET status = 'CANCELLED', cancel_reason = :reason, resolved_at = :now WHERE id = :id")
                    .param("id", refundId)
                    .param("reason", reason)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
            history.append(refundId, "AWAITING_FUNDS", "CANCELLED", "OPERATOR", now);
            payments.restore(payment, refund.amount());
            post(refundId, refund.amount(), EntryType.REFUND_CANCELLED, "cancelled", now);
            queue.release();
            return result(refundId);
        }));
    }

    private RefundResult result(String id) {
        return jdbc.sql("SELECT id, status, amount, queue_seq FROM core.refund WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new RefundResult(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)))
                .single();
    }

    private void post(String id, long amount, EntryType type, String action, Instant now) {
        var lines = type == EntryType.REFUND_ACCEPTED
                ? List.of(
                        PostingLine.debit(Account.MERCHANT_AVAILABLE, amount),
                        PostingLine.credit(Account.REFUND_PAYABLE, amount))
                : List.of(
                        PostingLine.debit(Account.REFUND_PAYABLE, amount),
                        PostingLine.credit(Account.MERCHANT_AVAILABLE, amount));
        ledger.post(new EntryRequest(type, "refund:" + id + ":" + action, now, SubjectType.REFUND, id, lines));
    }
}
