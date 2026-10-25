package dev.ledgerlab.payments;

import dev.ledgerlab.ledger.Ledger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Narrow correction access; the caller owns the funds lock and transaction. */
@Service
public final class PaymentCorrections {
    private final JdbcClient jdbc;
    private final Ledger ledger;

    public PaymentCorrections(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public Optional<Payment> lock(String id) {
        ledger.assertFundsLocked();
        return jdbc.sql(
                        "SELECT id, amount, expected_fee, refunded_amount, status, batch_id FROM core.payment WHERE id=? FOR UPDATE")
                .param(id)
                .query((rs, row) -> new Payment(
                        rs.getString("id"),
                        rs.getLong("amount"),
                        rs.getLong("expected_fee"),
                        rs.getLong("refunded_amount"),
                        rs.getString("status"),
                        rs.getString("batch_id")))
                .optional();
    }

    public void voidCapture(String paymentId, String correctionId, Instant at) {
        ledger.assertFundsLocked();
        int changed = jdbc.sql("""
                UPDATE core.payment SET status='VOIDED'
                WHERE id=? AND status='CAPTURED' AND batch_id IS NULL AND refunded_amount=0
                """).param(paymentId).update();
        if (changed != 1) throw new IllegalStateException("Payment is no longer eligible for voiding");
        var now = at.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO core.payment_transition
                    (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at)
                VALUES (?, 'CAPTURED', 'VOIDED', 'correction', ?, ?, ?)
                """).params(paymentId, correctionId, now, now).update();
    }

    public record Payment(
            String id,
            long amount,
            long expectedFee,
            long refundedAmount,
            String status,
            @Nullable String batchId) {}
}
