package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayoutRefundConcurrencyTest {
    @Test
    void ac_7_14_fiftyBarrierRacesOnlyProduceTheTwoLegalOutcomes() throws Exception {
        int refundFirst = 0;
        int payoutFirst = 0;
        for (int iteration = 0; iteration < 50; iteration++) {
            try (var s = funded();
                    var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CyclicBarrier(2);
                var refund = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return refund(s.app);
                });
                var payout = pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return payout(s.app);
                });
                if (check(s.app, refund.get(20, TimeUnit.SECONDS), payout.get(20, TimeUnit.SECONDS))) payoutFirst++;
                else refundFirst++;
            }
        }
        System.out.println("AC-7.14 barrier races=50 refund-first=" + refundFirst + " payout-first=" + payoutFirst);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_7_14_controlBothOrdersWhileCompetitorWaitsForTheRealFundsLock(boolean payoutFirst) throws Exception {
        try (var s = funded();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var acquired = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var contender = new CountDownLatch(1);
            var calls = new AtomicInteger();
            s.app.funds.before = () -> {
                if (calls.incrementAndGet() == 2) contender.countDown();
            };
            s.app.funds.after = () -> {
                if (acquired.getCount() > 0) {
                    acquired.countDown();
                    await(release);
                }
            };
            try {
                var first = pool.submit(() -> payoutFirst ? payout(s.app) : refund(s.app));
                await(acquired);
                var second = pool.submit(() -> payoutFirst ? refund(s.app) : payout(s.app));
                await(contender);
                // Observe the actual PostgreSQL lock wait, not a scheduling delay or a sleep.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                do {
                    blocked = s.app.jdbc
                            .sql(
                                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND cardinality(pg_blocking_pids(pid))>0)")
                            .query(Boolean.class)
                            .single();
                } while (!blocked && System.nanoTime() < deadline);
                assertThat(blocked)
                        .as("competitor blocked on first transaction")
                        .isTrue();
                s.app.funds.after = () -> {};
                release.countDown();
                var a = first.get(20, TimeUnit.SECONDS);
                var b = second.get(20, TimeUnit.SECONDS);
                assertThat(check(s.app, payoutFirst ? b : a, payoutFirst ? a : b))
                        .isEqualTo(payoutFirst);
            } finally {
                release.countDown();
                s.app.funds.before = () -> {};
                s.app.funds.after = () -> {};
            }
        }
    }

    private static P3SamplesTest.Sample funded() {
        var s = new P3SamplesTest.Sample();
        s.charge("g", 1061);
        s.batch("g", 1000, "g", 1061, 61, null, 0);
        s.bank("g", 1000);
        s.check(0, 0, 1000, 0, 1000, 0, 0, 1000);
        return s;
    }

    private static ModelApplication.ApiResponse refund(ModelApplication app) {
        return app.api(
                "/api/refunds",
                "g-refund",
                "{\"payment_ref\":\"ch_sample_g\",\"merchant_refund_ref\":\"g-refund\",\"amount\":800}");
    }

    private static ModelApplication.ApiResponse payout(ModelApplication app) {
        return app.api("/api/payouts", "g-payout", "{\"merchantPayoutRef\":\"g-payout\",\"amount\":1000}");
    }

    private static boolean check(
            ModelApplication app, ModelApplication.ApiResponse refund, ModelApplication.ApiResponse payout) {
        assertThat(refund.status()).as(refund.body()).isEqualTo(201);
        String status = app.jdbc
                .sql("SELECT status FROM core.refund")
                .query(String.class)
                .single();
        boolean paid = payout.status() == 201;
        assertThat(payout.status()).as(payout.body()).isIn(201, 409);
        assertThat(status).isEqualTo(paid ? "AWAITING_FUNDS" : "READY");
        if (!paid) assertThat(payout.body()).contains("MERCHANT_BALANCE");
        assertThat(app.balances())
                .containsEntry(2102, paid ? -800L : 200L)
                .containsEntry(2104, paid ? 1000L : 0L)
                .containsEntry(2103, 800L)
                .containsEntry(1103, 1000L);
        assertThat(app.jdbc
                        .sql("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND'")
                        .query(Long.class)
                        .single())
                .isEqualTo(paid ? 0 : 1);
        assertThat(app.jdbc
                        .sql("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='REFUND_EXECUTED'")
                        .query(Long.class)
                        .single())
                .isZero();
        dev.ledgerlab.testsupport.RefundAssertions.invariants(app.jdbc, app.audit);
        return paid;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
