package dev.ledgerlab.reconciliation.internal;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AgingPolicy(
        int paymentBusinessDays,
        int depositBusinessDays,
        int heldBatchBusinessDays,
        Duration parkedEvent,
        int refundStatementBusinessDays,
        int refundAwaitingFundsBusinessDays,
        int refundExecutionBusinessDays) {
    public AgingPolicy(
            @Value("${ledgerlab.recon.aging.payment-business-days:2}") int paymentBusinessDays,
            @Value("${ledgerlab.recon.aging.deposit-business-days:2}") int depositBusinessDays,
            @Value("${ledgerlab.recon.aging.held-batch-business-days:2}") int heldBatchBusinessDays,
            @Value("${ledgerlab.recon.aging.parked-event:PT1H}") Duration parkedEvent,
            @Value("${ledgerlab.recon.aging.refund-statement-business-days:2}") int refundStatementBusinessDays,
            @Value("${ledgerlab.recon.aging.refund-awaiting-funds-business-days:5}")
                    int refundAwaitingFundsBusinessDays,
            @Value("${ledgerlab.recon.aging.refund-execution-business-days:3}") int refundExecutionBusinessDays) {
        if (paymentBusinessDays <= 0
                || depositBusinessDays <= 0
                || heldBatchBusinessDays <= 0
                || refundStatementBusinessDays <= 0
                || refundAwaitingFundsBusinessDays <= 0
                || refundExecutionBusinessDays <= 0)
            throw new IllegalArgumentException("Aging business-day thresholds must be positive");
        if (parkedEvent.isZero() || parkedEvent.isNegative())
            throw new IllegalArgumentException("Parked event threshold must be positive");
        this.paymentBusinessDays = paymentBusinessDays;
        this.depositBusinessDays = depositBusinessDays;
        this.heldBatchBusinessDays = heldBatchBusinessDays;
        this.parkedEvent = parkedEvent;
        this.refundStatementBusinessDays = refundStatementBusinessDays;
        this.refundAwaitingFundsBusinessDays = refundAwaitingFundsBusinessDays;
        this.refundExecutionBusinessDays = refundExecutionBusinessDays;
    }
}
