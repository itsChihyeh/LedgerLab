package dev.ledgerlab.refunds;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payments.PaymentRefunds;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Narrow batch entry point; lookup outside a transaction is only a preparation hint. */
@Component
public final class RefundSettlement {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PaymentRefunds payments;
    private final RefundEvidence evidence;

    public RefundSettlement(JdbcClient jdbc, Ledger ledger, PaymentRefunds payments, RefundEvidence evidence) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.evidence = evidence;
    }

    public Optional<Refund> find(String processorRef, @Nullable String refundId) {
        var known = read("r.processor_refund_ref = :key", processorRef);
        return known.isPresent() || refundId == null ? known : read("r.id = :key", refundId);
    }

    public Optional<Refund> lock(String processorRef, @Nullable String refundId) {
        ledger.assertFundsLocked();
        var found = find(processorRef, refundId);
        if (found.isEmpty()) return found;
        Refund refund = found.orElseThrow();
        if (payments.lock(refund.paymentId()).isEmpty()) throw new IllegalStateException("Refund payment missing");
        jdbc.sql("SELECT id FROM core.refund WHERE id=:id FOR UPDATE")
                .param("id", refund.id())
                .query(String.class)
                .single();
        return read("r.id = :key", refund.id());
    }

    public void execute(ProcessorRefundFact fact) {
        ledger.assertFundsLocked();
        if (evidence.apply(fact) == RefundEvidence.Result.CONFLICT)
            throw new IllegalStateException("Validated batch refund changed under funds lock");
    }

    public void attach(String refundId, String processorRef, String batchId) {
        ledger.assertFundsLocked();
        int changed = jdbc.sql("""
                UPDATE core.refund SET batch_id=:batch, processor_refund_ref=coalesce(processor_refund_ref,:ref)
                    WHERE id=:id AND status='SUCCEEDED' AND (processor_refund_ref IS NULL OR processor_refund_ref=:ref)
                    AND (batch_id IS NULL OR batch_id=:batch)
                """)
                .param("id", refundId)
                .param("ref", processorRef)
                .param("batch", batchId)
                .update();
        if (changed != 1) throw new IllegalStateException("Refund batch association changed under funds lock");
    }

    private Optional<Refund> read(String predicate, String key) {
        return jdbc.sql("""
                SELECT r.id, r.payment_id, r.processor_refund_ref, r.amount, r.status, r.batch_id,
                    p.processor_payment_ref FROM core.refund r JOIN core.payment p ON p.id=r.payment_id
                WHERE
                """ + predicate)
                .param("key", key)
                .query((rs, row) -> new Refund(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7)))
                .optional();
    }

    public record Refund(
            String id,
            String paymentId,
            @Nullable String processorRef,
            long amount,
            String status,
            @Nullable String batchId,
            String chargeRef) {}
}
