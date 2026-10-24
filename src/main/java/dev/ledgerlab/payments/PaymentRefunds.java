package dev.ledgerlab.payments;

import dev.ledgerlab.ledger.Ledger;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Narrow payment-row access for refunds, always under the caller's funds lock. */
@Service
public final class PaymentRefunds {
    private final JdbcClient jdbc;
    private final Ledger ledger;

    public PaymentRefunds(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public Optional<Payment> lock(String id) {
        ledger.assertFundsLocked();
        return jdbc.sql("SELECT id, amount, refunded_amount, status FROM core.payment WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, row) -> new Payment(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)))
                .optional();
    }

    /** The caller has locked and checked the payment; the database CHECK is a second line of defense. */
    public void reserve(Payment payment, long amount) {
        ledger.assertFundsLocked();
        if (amount < 1 || amount > Math.subtractExact(payment.amount(), payment.refundedAmount())) {
            throw new IllegalArgumentException("Refund exceeds the locked payment's remaining amount");
        }
        jdbc.sql("UPDATE core.payment SET refunded_amount = refunded_amount + :amount WHERE id = :id")
                .param("amount", amount)
                .param("id", payment.id())
                .update();
    }

    public void restore(Payment payment, long amount) {
        ledger.assertFundsLocked();
        if (amount < 1 || Math.subtractExact(payment.refundedAmount(), amount) < 0) {
            throw new IllegalArgumentException("Cannot restore more than the reserved refund amount");
        }
        jdbc.sql("UPDATE core.payment SET refunded_amount = refunded_amount - :amount WHERE id = :id")
                .param("amount", amount)
                .param("id", payment.id())
                .update();
    }

    public record Payment(String id, long amount, long refundedAmount, String status) {}
}
