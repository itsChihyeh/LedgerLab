package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostedEntry;
import dev.ledgerlab.refunds.RefundException;
import dev.ledgerlab.refunds.RefundResult;
import dev.ledgerlab.testsupport.RefundAssertions;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundServiceTest {
    private RefundFixture f;
    private boolean mutant;

    @BeforeEach
    void setup() {
        f = new RefundFixture();
    }

    @AfterEach
    void cleanup() {
        try {
            if (!mutant) f.invariants();
        } finally {
            f.close();
        }
    }

    @Test
    void ac_3_2_concurrentSixThousandsAcceptExactlyOne() throws Exception {
        String payment = f.s3();
        long postings = f.count("ledger.posting");
        var results = concurrent(f.refunds, payment, 6000);
        assertThat(results.stream().filter(RefundResult.class::isInstance)).hasSize(1);
        assertThat(results).contains("REFUND_EXCEEDS_REMAINING");
        assertThat(f.count("core.refund")).isEqualTo(1);
        assertThat(f.count("ledger.posting") - postings).isEqualTo(2);
        balances(3680, 6000, 9680);
        assertThat(f.count("io.outbound_command")).isEqualTo(1);
        assertCommandDefaults();
    }

    @Test
    void ac_3_3_twoFivesWaitThenBatchReleasesAtomically() throws Exception {
        String payment = f.s3();
        var refunds = concurrent(f.refunds, payment, 5000).stream()
                .map(RefundResult.class::cast)
                .sorted(Comparator.comparingLong(RefundResult::queueSeq))
                .toList();
        assertThat(refunds).extracting(RefundResult::status).containsExactly("READY", "AWAITING_FUNDS");
        balances(-320, 10000, 9680);
        assertThat(f.count("io.outbound_command")).isEqualTo(1);
        f.payment("ch_p2", 5000);
        var batch = f.batchEvidence("po_b2", "ch_p2", 5000, 175);
        f.tx.executeWithoutResult(tx -> {
            f.batches.process(batch);
            assertThat(f.status(refunds.get(1).refundId())).isEqualTo("READY");
            assertThat(f.count("io.outbound_command")).isEqualTo(2);
            tx.setRollbackOnly();
        });
        assertThat(f.status(refunds.get(1).refundId())).isEqualTo("AWAITING_FUNDS");
        assertThat(f.count("io.outbound_command")).isEqualTo(1);
        balances(-320, 10000, 9680);
        f.tx.executeWithoutResult(tx -> f.batches.process(batch));
        balances(4505, 10000, 9680);
        assertThat(f.status(refunds.get(1).refundId())).isEqualTo("READY");
        assertThat(f.count("io.outbound_command")).isEqualTo(2);
        f.tx.executeWithoutResult(tx -> {
            f.ledger.lockFunds();
            f.queue.release();
            f.queue.release();
        });
        assertThat(f.count("io.outbound_command")).isEqualTo(2);
        assertCommandDefaults();
    }

    @Test
    void ac_3_4_existingRefundLeavesOnlyOneConcurrentFive() throws Exception {
        String payment = f.s3();
        f.accept(payment, 2000);
        var results = concurrent(f.refunds, payment, 5000);
        assertThat(results.stream().filter(RefundResult.class::isInstance)).hasSize(1);
        assertThat(results).contains("REFUND_EXCEEDS_REMAINING");
        balances(2680, 7000, 9680);
        assertThat(f.count("core.refund")).isEqualTo(2);
    }

    @Test
    void ac_3_5_excessSingleRefundWritesNothing() {
        String payment = f.s3();
        f.accept(payment, 2000);
        var before = counts();
        error("REFUND_EXCEEDS_REMAINING", () -> f.accept(payment, 9000));
        assertThat(counts()).isEqualTo(before);
        balances(7680, 2000, 9680);
    }

    @Test
    void ac_3_6_capturedPaymentWaitsUntilItsBatchSettles() {
        String payment = f.s1();
        var refund = f.accept(payment, 2000);
        assertThat(refund.status()).isEqualTo("AWAITING_FUNDS");
        balances(-2000, 2000, 0);
        assertThat(f.count("io.outbound_command")).isZero();
        f.batch("po_b1", "ch_p1", 10000, 320);
        balances(7680, 2000, 0);
        assertThat(f.status(refund.refundId())).isEqualTo("READY");
        assertThat(f.count("io.outbound_command")).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT initial_result->'body'->>'status' FROM core.refund")
                        .query(String.class)
                        .single())
                .isEqualTo("AWAITING_FUNDS");
    }

    @Test
    void ac_3_7_smallerNewRefundCannotJumpTheHead() {
        String payment = available1500();
        var head = f.accept(payment, 2000);
        var tail = f.accept(payment, 1000);
        assertThat(List.of(head.status(), tail.status())).containsOnly("AWAITING_FUNDS");
        balances(-1500, 3000, 0);
        f.payment("ch_topup", 1100);
        f.batch("po_topup", "ch_topup", 1100, 100);
        balances(-500, 3000, 0);
        assertThat(f.status(head.refundId())).isEqualTo("READY");
        assertThat(f.status(tail.refundId())).isEqualTo("AWAITING_FUNDS");
        assertThat(f.count("io.outbound_command")).isEqualTo(1);
    }

    @Test
    void ac_3_9_cancelHeadRestoresLimitAndReleasesTailInSameTransaction() {
        String payment = available1500();
        var head = f.accept(payment, 2000);
        var tail = f.accept(payment, 1000);
        f.clock.set(f.clock.instant().plusSeconds(60));
        f.tx.executeWithoutResult(tx -> {
            assertThat(f.refunds.cancel(head.refundId(), "customer withdrew").status())
                    .isEqualTo("CANCELLED");
            assertThat(f.status(tail.refundId())).isEqualTo("READY");
            assertThat(f.count("io.outbound_command")).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(f.status(head.refundId())).isEqualTo("AWAITING_FUNDS");
        assertThat(f.status(tail.refundId())).isEqualTo("AWAITING_FUNDS");
        assertThat(f.count("io.outbound_command")).isZero();
        f.refunds.cancel(head.refundId(), "customer withdrew");
        balances(500, 1000, 0);
        assertThat(f.number("SELECT refunded_amount FROM core.payment WHERE processor_payment_ref='ch_p1'"))
                .isEqualTo(1000);
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='REFUND_CANCELLED'"))
                .isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT cancel_reason FROM core.refund WHERE id=:id")
                        .param("id", head.refundId())
                        .query(String.class)
                        .single())
                .isEqualTo("customer withdrew");
        assertThat(f.jdbc.sql("SELECT resolved_at FROM core.refund WHERE id=:id")
                        .param("id", head.refundId())
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(f.clock.instant());
        var before = counts();
        error("REFUND_NOT_CANCELLABLE", () -> f.refunds.cancel(tail.refundId(), "too late"));
        error("REFUND_NOT_CANCELLABLE", () -> f.refunds.cancel(head.refundId(), "again"));
        error("INVALID_CANCEL_REASON", () -> f.refunds.cancel(tail.refundId(), " \t "));
        error("REFUND_NOT_FOUND", () -> f.refunds.cancel("rfd_missing", "missing"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void ac_3_12_faultAfterRefundInsertRollsBackEveryWrite() {
        String payment = f.s3();
        var before = counts();
        var crashing = f.service(f.ledger, new LockedRefundLimitGuard(f.ledger, f.payments), () -> {
            assertThat(f.count("core.refund")).isEqualTo(1);
            assertThat(f.count("ledger.posting")).isEqualTo(before.get(1));
            throw new IllegalStateException("injected before posting");
        });
        assertThatThrownBy(() -> crashing.accept(payment, "crash", 6000, "fp"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected before posting");
        assertThat(counts()).isEqualTo(before);
        assertThat(f.count("core.refund_transition")).isZero();
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isZero();
        balances(9680, 0, 9680);
        assertThat(f.refunds.accept(payment, "crash", 6000, "fp").status()).isEqualTo("READY");
    }

    @Test
    void ac_3_13_lostUpdateMutantMustOverRefundWhileProductionRejects() throws Exception {
        String payment = f.s3();
        CyclicBarrier bothRead = new CyclicBarrier(2);
        RefundLimitGuard broken = (id, amount) -> {
            long read = f.jdbc.sql("SELECT refunded_amount FROM core.payment WHERE id=:id")
                    .param("id", id)
                    .query(Long.class)
                    .single();
            long captured = f.jdbc.sql("SELECT amount FROM core.payment WHERE id=:id")
                    .param("id", id)
                    .query(Long.class)
                    .single();
            if (amount > Math.subtractExact(captured, read)) throw new RefundException("REFUND_EXCEEDS_REMAINING");
            await(bothRead);
            f.jdbc.sql("UPDATE core.payment SET refunded_amount=:stale WHERE id=:id")
                    .param("stale", Math.addExact(read, amount))
                    .param("id", id)
                    .update();
        };
        // Only the test adapter acquires the ledger lock late, so journal integrity still holds.
        // Neither the guard's SELECT nor its stale UPDATE is protected by the funds or payment lock.
        Ledger lateLock = new Ledger() {
            @Override
            public void lockFunds() {
                f.ledger.lockFunds();
            }

            @Override
            public void assertFundsLocked() {
                f.ledger.assertFundsLocked();
            }

            @Override
            public PostedEntry post(EntryRequest request) {
                f.ledger.lockFunds();
                return f.ledger.post(request);
            }
        };
        var results = concurrent(f.service(lateLock, broken, () -> {}), payment, 6000);
        assertThat(results).allMatch(RefundResult.class::isInstance);
        assertThat(f.count("core.refund")).isEqualTo(2);
        assertThat(f.number("SELECT sum(amount)::bigint FROM core.refund")).isEqualTo(12000);
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isEqualTo(6000);
        assertThatThrownBy(f::invariants).isInstanceOf(AssertionError.class).hasMessageContaining("INV-7");
        assertThat(f.audit.check()).isEmpty();
        RefundAssertions.noBankCashPostings(f.jdbc);
        mutant = true;
        try (var correct = new RefundFixture()) {
            var production = concurrent(correct.refunds, correct.s3(), 6000);
            assertThat(production.stream().filter(RefundResult.class::isInstance))
                    .hasSize(1);
            assertThat(production).contains("REFUND_EXCEEDS_REMAINING");
            correct.invariants();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 100000000, Long.MIN_VALUE, Long.MAX_VALUE})
    void ac_3_16_invalidAmountNeverWrites(long amount) {
        String payment = f.s1();
        var before = counts();
        error("INVALID_AMOUNT", () -> f.accept(payment, amount));
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void ac_3_16_remainingFortyNineCentsIsRefundable() {
        String payment = f.payment("ch_small", 100);
        f.accept(payment, 51);
        assertThat(f.accept(payment, 49).amount()).isEqualTo(49);
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isEqualTo(100);
    }

    @Test
    void ac_3_16_upperBoundAndOneCentAreValid() {
        String payment = f.payment("ch_max", 99999999);
        assertThat(f.accept(payment, 99999999).amount()).isEqualTo(99999999);
        String small = f.payment("ch_min", 50);
        assertThat(f.accept(small, 1).amount()).isEqualTo(1);
    }

    @Test
    void ac_3_6_missingVoidedAndDuplicateReferencesAreExplicitErrors() {
        error("PAYMENT_NOT_FOUND", () -> f.accept("pay_missing", 1));
        String payment = f.s1();
        f.tx.executeWithoutResult(
                tx -> f.jdbc.sql("UPDATE core.payment SET status='VOIDED'").update());
        error("PAYMENT_NOT_REFUNDABLE", () -> f.accept(payment, 1));
        f.tx.executeWithoutResult(
                tx -> f.jdbc.sql("UPDATE core.payment SET status='CAPTURED'").update());
        var refund = f.refunds.accept(payment, "same", 2000, "fingerprint");
        var before = counts();
        error("DUPLICATE_REFUND_REFERENCE", () -> f.refunds.accept(payment, "same", 2000, "fingerprint"));
        error("INVALID_MERCHANT_REFUND_REF", () -> f.refunds.accept(payment, " ", 1, "fp"));
        error("INVALID_MERCHANT_REFUND_REF", () -> f.refunds.accept(payment, "x".repeat(65), 1, "fp"));
        assertThat(counts()).isEqualTo(before);
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isEqualTo(2000);
        var initial = f.jdbc.sql("SELECT initial_result::text FROM core.refund")
                .query(String.class)
                .single();
        var body = f.mapper.readTree(initial);
        assertThat(body.path("status").asInt()).isEqualTo(201);
        assertThat(body.path("body"))
                .isEqualTo(f.mapper.valueToTree(java.util.Map.of(
                        "refund_id",
                        refund.refundId(),
                        "status",
                        "AWAITING_FUNDS",
                        "amount",
                        2000,
                        "payment_ref",
                        payment,
                        "merchant_refund_ref",
                        "same",
                        "accepted_at",
                        f.clock.instant().toString())));
        assertThat(refund.queueSeq()).isPositive();
    }

    @Test
    void ac_3_7_releaseChecksButNeverAcquiresFundsLock() {
        assertThatThrownBy(f.ledger::assertFundsLocked).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(f.queue::release).isInstanceOf(IllegalStateException.class);
        f.tx.executeWithoutResult(tx -> {
            assertThatThrownBy(f.ledger::assertFundsLocked).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(f.queue::release).isInstanceOf(IllegalStateException.class);
            f.ledger.lockFunds();
            f.ledger.assertFundsLocked();
            f.queue.release();
        });
        f.tx.executeWithoutResult(
                tx -> assertThatThrownBy(f.ledger::assertFundsLocked).isInstanceOf(IllegalStateException.class));
    }

    private String available1500() {
        String payment = f.s1();
        f.batch("po_b1", "ch_p1", 10000, 8500);
        assertThat(f.balance(2102)).isEqualTo(1500);
        return payment;
    }

    private void assertCommandDefaults() {
        assertThat(f.jdbc.sql("""
                SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND' AND status='PENDING'
                    AND request_id=subject_id AND attempts=0 AND lease_epoch=0 AND first_sent_at IS NULL
                    AND next_attempt_at=created_at AND recorded_at=created_at
                """).query(Long.class).single()).isEqualTo(f.count("io.outbound_command"));
        assertThat(f.jdbc.sql("SELECT accepted_at=recorded_at FROM core.refund")
                        .query(Boolean.class)
                        .list())
                .containsOnly(true);
    }

    private List<Long> counts() {
        return List.of(
                f.count("core.refund"),
                f.count("ledger.posting"),
                f.count("io.outbound_command"),
                f.count("core.refund_transition"),
                f.count("ledger.journal_entry"));
    }

    private void balances(long available, long payable, long cash) {
        assertThat(f.balance(2102)).isEqualTo(available);
        assertThat(f.balance(2103)).isEqualTo(payable);
        assertThat(f.balance(1103)).isEqualTo(cash);
    }

    static void error(String code, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        RefundException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }

    static List<Object> concurrent(JdbcRefunds service, String payment, long amount) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> acceptAtBarrier(service, start, payment, "concurrent-a", amount));
            var b = workers.submit(() -> acceptAtBarrier(service, start, payment, "concurrent-b", amount));
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private static Object acceptAtBarrier(
            JdbcRefunds service, CyclicBarrier start, String payment, String ref, long amount) {
        await(start);
        try {
            return service.accept(payment, ref, amount, "fp-" + ref);
        } catch (RefundException e) {
            return e.code();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
