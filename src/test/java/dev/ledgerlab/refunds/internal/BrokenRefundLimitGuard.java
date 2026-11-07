package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.refunds.RefundException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The AC-9.2 fault: the same lost-update refund limit as the AC-3.13 mutant, switchable for an end-to-end run
 * (section 6 of demo-and-delivery.md). It reads the refunded amount with no payment-row lock and no funds lock,
 * waits for a second request to arrive at the same point, then writes its stale total and only afterwards takes
 * the funds lock the ledger insists on. Two concurrent refunds that together exceed the payment are therefore
 * both accepted, and the live INV-7 check has to catch it.
 *
 * <p>It lives in the test source set and needs {@code ledgerlab.test.break-refund-lock=true}, so no production
 * or demo artifact contains it and no default or demo context creates it.
 */
@Component
@Primary
@ConditionalOnProperty(name = "ledgerlab.test.break-refund-lock", havingValue = "true")
final class BrokenRefundLimitGuard implements RefundLimitGuard {
    /** Long enough for two requests sent together to meet, short enough that a lone refund barely notices. */
    private static final long MEET_MILLIS = 2_000;

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final CyclicBarrier bothRead = new CyclicBarrier(2);

    BrokenRefundLimitGuard(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Override
    public void reserve(String paymentId, long amount) {
        long[] row = jdbc.sql("SELECT amount, refunded_amount FROM core.payment WHERE id = :id")
                .param("id", paymentId)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .optional()
                .orElseThrow(() -> new RefundException("PAYMENT_NOT_FOUND"));
        if (amount > Math.subtractExact(row[0], row[1])) throw new RefundException("REFUND_EXCEEDS_REMAINING");
        meet();
        jdbc.sql("UPDATE core.payment SET refunded_amount = :stale WHERE id = :id")
                .param("stale", Math.addExact(row[1], amount))
                .param("id", paymentId)
                .update();
        ledger.lockFunds();
    }

    private void meet() {
        try {
            bothRead.await(MEET_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | BrokenBarrierException alone) {
            bothRead.reset();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
