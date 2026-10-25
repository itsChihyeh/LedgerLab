package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.refunds.RefundException;
import org.springframework.stereotype.Component;

@Component
final class LockedRefundLimitGuard implements RefundLimitGuard {
    private final Ledger ledger;
    private final PaymentRefunds payments;

    LockedRefundLimitGuard(Ledger ledger, PaymentRefunds payments) {
        this.ledger = ledger;
        this.payments = payments;
    }

    @Override
    public void reserve(String paymentId, long amount) {
        ledger.lockFunds();
        var payment = payments.lock(paymentId).orElseThrow(() -> new RefundException("PAYMENT_NOT_FOUND"));
        if (payment.status().equals("VOIDED")) throw new RefundException("PAYMENT_NOT_REFUNDABLE");
        if (amount > Math.subtractExact(payment.amount(), payment.refundedAmount())) {
            throw new RefundException("REFUND_EXCEEDS_REMAINING");
        }
        payments.reserve(payment, amount);
    }
}
