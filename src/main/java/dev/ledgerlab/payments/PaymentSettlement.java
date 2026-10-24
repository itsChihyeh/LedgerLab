package dev.ledgerlab.payments;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Narrow settlement access; callers take the funds lock before locking payments. */
@Service
public final class PaymentSettlement {
    private final JdbcClient jdbc;

    public PaymentSettlement(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Payment> lock(String processorRef) {
        requireTransaction();
        return jdbc.sql(
                        "SELECT id, amount, status, batch_id FROM core.payment WHERE processor_payment_ref = :ref FOR UPDATE")
                .param("ref", processorRef)
                .query((rs, row) -> new Payment(
                        rs.getString("id"), rs.getLong("amount"), rs.getString("status"), rs.getString("batch_id")))
                .optional();
    }

    public void settle(
            Payment payment,
            long actualFee,
            String batchId,
            String evidenceType,
            String evidenceId,
            Instant occurredAt,
            Instant recordedAt) {
        requireTransaction();
        int changed = jdbc.sql("""
                UPDATE core.payment SET status = 'SETTLED', actual_fee = :fee, batch_id = :batch
                WHERE id = :id AND status = 'CAPTURED' AND batch_id IS NULL
                """)
                .param("fee", actualFee)
                .param("batch", batchId)
                .param("id", payment.id())
                .update();
        if (changed != 1) throw new IllegalStateException("Payment is no longer available for settlement");
        jdbc.sql("""
                INSERT INTO core.payment_transition
                    (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at)
                VALUES (:id, 'CAPTURED', 'SETTLED', :type, :evidence, :occurred, :recorded)
                """)
                .param("id", payment.id())
                .param("type", evidenceType)
                .param("evidence", evidenceId)
                .param("occurred", occurredAt.atOffset(ZoneOffset.UTC))
                .param("recorded", recordedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment settlement requires the caller's transaction");
        }
    }

    public record Payment(
            String id, long amount, String status, @Nullable String batchId) {}
}
