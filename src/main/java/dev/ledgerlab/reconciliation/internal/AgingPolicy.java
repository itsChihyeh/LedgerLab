package dev.ledgerlab.reconciliation.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AgingPolicy(int paymentBusinessDays, int depositBusinessDays, int heldBatchBusinessDays) {
    public AgingPolicy(
            @Value("${ledgerlab.recon.aging.payment-business-days:2}") int paymentBusinessDays,
            @Value("${ledgerlab.recon.aging.deposit-business-days:2}") int depositBusinessDays,
            @Value("${ledgerlab.recon.aging.held-batch-business-days:2}") int heldBatchBusinessDays) {
        if (paymentBusinessDays <= 0 || depositBusinessDays <= 0 || heldBatchBusinessDays <= 0)
            throw new IllegalArgumentException("Aging business-day thresholds must be positive");
        this.paymentBusinessDays = paymentBusinessDays;
        this.depositBusinessDays = depositBusinessDays;
        this.heldBatchBusinessDays = heldBatchBusinessDays;
    }
}
