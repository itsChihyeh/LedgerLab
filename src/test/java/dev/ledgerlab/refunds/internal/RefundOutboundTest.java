package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.outbound.CommandExecution;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.ProcessorRefundFact.Source;
import dev.ledgerlab.refunds.ProcessorRefundFact.Status;
import dev.ledgerlab.refunds.RefundEvidence.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RefundOutboundTest {
    private RefundFixture f;
    private MemoryRefundGateway gateway;
    private JdbcRefundEvidence evidence;
    private OutboundProperties properties;
    private RefundCommandHandler handler;

    @BeforeEach
    void setup() {
        f = new RefundFixture();
        gateway = new MemoryRefundGateway();
        evidence =
                new JdbcRefundEvidence(f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        properties = properties(20);
        handler = new RefundCommandHandler(f.jdbc, f.payments, gateway, evidence, f.clock, properties);
    }

    @AfterEach
    void cleanup() {
        try {
            f.invariants();
        } finally {
            f.close();
        }
    }

    @Test
    void ac_3_1_succeededResponsePostsSampleASixAndCompletesCommand() {
        String refund = ready();
        Instant sent = f.clock.instant();
        assertThat(worker().processAvailable()).isEqualTo(1);
        assertThat(f.status(refund)).isEqualTo("SUCCEEDED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(f.balance(2103)).isZero();
        assertThat(f.balance(1101)).isEqualTo(3000);
        assertThat(f.balance(2102)).isEqualTo(7680);
        assertThat(firstSent()).isEqualTo(sent);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM core.refund WHERE submitted_at=(SELECT first_sent_at FROM io.outbound_command)"))
                .isEqualTo(1);
        assertThat(f.count("io.outbound_attempt")).isEqualTo(1);
        assertThat(worker().processAvailable()).isZero();
        assertThat(apply(refund, "re_memory_1", Status.succeeded, Source.WEBHOOK))
                .isEqualTo(Result.DUPLICATE_FACT);
        assertThat(apply(refund, "re_memory_1", Status.succeeded, Source.BATCH_LINE))
                .isEqualTo(Result.DUPLICATE_FACT);
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"failed", "canceled"})
    void ac_3_8_failedOrCanceledRestoresFundsAndReleasesFifo(Status status) {
        String payment = f.s3();
        String first = f.accept(payment, 5000).refundId();
        String second = f.accept(payment, 5000).refundId();
        assertThat(f.status(second)).isEqualTo("AWAITING_FUNDS");
        gateway.status = status;
        worker().processAvailable();
        assertThat(f.status(first)).isEqualTo("FAILED");
        assertThat(f.status(second)).isEqualTo("READY");
        assertThat(f.balance(2102)).isEqualTo(4680);
        assertThat(f.balance(2103)).isEqualTo(5000);
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isEqualTo(5000);
        assertThat(entries("REFUND_FAILED")).isEqualTo(1);
        assertThat(entries("REVERSAL")).isZero();
        assertThat(f.count("io.outbound_command")).isEqualTo(2);
        assertThat(apply(first, "re_memory_1", status, Source.WEBHOOK)).isEqualTo(Result.DUPLICATE_FACT);
    }

    @Test
    void ac_3_8_explicitRejectionReleasesButDisconnectDoesNot() {
        String id = ready();
        gateway.rejected = true;
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("FAILED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(f.jdbc.sql("SELECT failure_reason FROM core.refund")
                        .query(String.class)
                        .single())
                .isEqualTo("charge_already_refunded");
        assertThat(gateway.created).isZero();
    }

    @Test
    void ac_3_10_tenTimeoutsAcrossSevenDaysKeepFundsAndRequestIdentity() {
        String id = ready();
        gateway.timeoutBefore = true;
        Instant start = f.clock.instant();
        for (int i = 0; i < 10; i++) {
            f.clock.set(start.plus(Duration.ofDays(7).multipliedBy(i).dividedBy(9)));
            worker().processAvailable();
            assertThat(f.status(id)).isEqualTo("UNKNOWN");
            assertThat(commandStatus()).isEqualTo("UNKNOWN");
            assertThat(f.balance(2103)).isEqualTo(2000);
            f.invariants();
        }
        assertThat(gateway.requests).hasSize(10).containsOnly(id);
        assertThat(gateway.created).isZero();
        assertThat(entries("REFUND_FAILED")).isZero();
        assertThat(
                        f.number(
                                "SELECT count(*) FROM io.outbound_attempt WHERE request_summary->>'decision'='COMPLETE_NOT_FOUND_AFTER_THRESHOLD'"))
                .isEqualTo(8);
    }

    @Test
    void ac_3_10_createdBeforeTimeoutAndIncompleteQueryNeverResendAfterKeyExpiry() {
        String id = ready();
        gateway.timeoutAfter = true;
        worker().processAvailable();
        gateway.keys.clear();
        gateway.incomplete = true;
        for (int i = 0; i < 9; i++) {
            advance(Duration.ofDays(1));
            worker().processAvailable();
            assertThat(f.status(id)).isEqualTo("UNKNOWN");
            f.invariants();
        }
        assertThat(gateway.created).isEqualTo(1);
        assertThat(gateway.requests).containsExactly(id);
        gateway.incomplete = false;
        advance(Duration.ofDays(1));
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(gateway.created).isEqualTo(1);
    }

    @Test
    void ac_3_11_crashAfterCreateIsRecoveredByQuery() {
        String id = ready();
        gateway.status = Status.pending;
        var crashed = worker(new OutboundHooks() {
            @Override
            public void beforeWriteback(CommandSnapshot command) {
                throw new IllegalStateException("crash");
            }
        });
        assertThatThrownBy(crashed::processAvailable).hasMessage("crash");
        assertThat(commandStatus()).isEqualTo("IN_FLIGHT");
        assertThat(f.status(id)).isEqualTo("READY");
        Instant first = firstSent();
        advance(Duration.ofSeconds(61));
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(gateway.calls).containsExactly("CREATE", "LIST_ALL");
        assertThat(gateway.created).isEqualTo(1);
        assertThat(firstSent()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_3_11_crashBeforeSendingLeavesFirstSentEmptyAndTakeoverQueries(boolean afterClaim) {
        ready();
        var hooks = new OutboundHooks() {
            @Override
            public void afterClaim(CommandSnapshot command) {
                if (afterClaim) throw new IllegalStateException("crash");
            }

            @Override
            public void beforeHttp(CommandSnapshot command) {
                if (!afterClaim) throw new IllegalStateException("crash");
            }
        };
        assertThatThrownBy(() -> worker(hooks).processAvailable()).hasMessage("crash");
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE first_sent_at IS NULL"))
                .isEqualTo(1);
        assertThat(gateway.calls).isEmpty();
        advance(Duration.ofSeconds(61));
        worker().processAvailable();
        assertThat(gateway.calls).containsExactly("LIST_ALL", "CREATE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_4_9_timeoutAndWebhookSerializeInBothOrders(boolean timeoutFirst) throws Exception {
        String id = ready();
        gateway.timeoutBefore = true;
        var firstLocked = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CommandHandler controlled = new CommandHandler() {
            @Override
            public String kind() {
                return handler.kind();
            }

            @Override
            public CommandResult execute(CommandSnapshot c, CommandExecution e) {
                return handler.execute(c, e);
            }

            @Override
            public void applyInTransaction(CommandSnapshot c, CommandResult r) {
                if (timeoutFirst) {
                    f.ledger.lockFunds();
                    firstLocked.countDown();
                    await(release);
                } else secondStarted.countDown();
                handler.applyInTransaction(c, r);
            }
        };
        Runnable webhook = () -> f.tx.executeWithoutResult(tx -> {
            if (!timeoutFirst) {
                f.ledger.lockFunds();
                firstLocked.countDown();
                await(release);
            } else secondStarted.countDown();
            evidence.apply(fact(id, "re_race", Status.succeeded, Source.WEBHOOK));
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(
                    timeoutFirst
                            ? () -> worker(controlled, new OutboundHooks() {}).processAvailable()
                            : () -> {
                                webhook.run();
                                return 1;
                            });
            await(firstLocked);
            var second = pool.submit(
                    timeoutFirst
                            ? () -> {
                                webhook.run();
                                return 1;
                            }
                            : () -> worker(controlled, new OutboundHooks() {}).processAvailable());
            try {
                await(secondStarted);
            } finally {
                release.countDown();
            }
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
    }

    @Test
    void ac_3_11_lateOldLeaseResponseOnlyLeavesAttempt() throws Exception {
        String id = ready();
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var old = worker(new OutboundHooks() {
            @Override
            public void beforeWriteback(CommandSnapshot c) {
                waiting.countDown();
                await(release);
            }
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(old::processAvailable);
            try {
                await(waiting);
                assertThat(worker().processAvailable()).isZero();
                gateway.refunds.put(
                        id,
                        new dev.ledgerlab.refunds.ProcessorRefundGateway.Found("re_memory_1", Status.pending, null));
                advance(Duration.ofSeconds(61));
                worker().processAvailable();
                assertThat(f.status(id)).isEqualTo("SUBMITTED");
            } finally {
                release.countDown();
            }
            result.get(15, TimeUnit.SECONDS);
        }
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(entries("REFUND_EXECUTED")).isZero();
        assertThat(f.count("io.outbound_attempt")).isEqualTo(2);
        assertThat(f.number("SELECT lease_epoch FROM io.outbound_command")).isEqualTo(2);
    }

    @Test
    void ac_3_11_supersededBeforeHttpCannotSend() throws Exception {
        ready();
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var old = worker(new OutboundHooks() {
            @Override
            public void beforeHttp(CommandSnapshot c) {
                waiting.countDown();
                await(release);
            }
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(old::processAvailable);
            try {
                await(waiting);
                advance(Duration.ofSeconds(61));
                worker().processAvailable();
            } finally {
                release.countDown();
            }
            result.get(15, TimeUnit.SECONDS);
        }
        assertThat(gateway.calls).containsExactly("LIST_ALL", "CREATE");
        assertThat(gateway.created).isEqualTo(1);
    }

    @Test
    void ac_3_10_retryUsesKnownReferenceAndDisconnectIsUnknown() {
        String id = ready();
        gateway.throwTransport = true;
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("UNKNOWN");
        apply(id, "re_known", Status.pending, Source.WEBHOOK);
        gateway.refunds.put(
                id, new dev.ledgerlab.refunds.ProcessorRefundGateway.Found("re_known", Status.pending, null));
        advance(Duration.ofSeconds(10));
        worker().processAvailable();
        assertThat(gateway.calls).containsExactly("CREATE", "RETRIEVE");
        assertThat(commandStatus()).isEqualTo("DONE");
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 20, 21})
    void ac_3_10_completeNotFoundAllowsSameKeyResendOnBothSidesOfThreshold(int hours) {
        String id = ready();
        gateway.timeoutBefore = true;
        worker().processAvailable();
        Instant first = firstSent();
        advance(Duration.ofHours(hours));
        gateway.timeoutBefore = false;
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(gateway.requests).containsExactly(id, id);
        assertThat(gateway.calls).containsExactly("CREATE", "LIST_ALL", "CREATE");
        assertThat(firstSent()).isEqualTo(first);
        assertThat(f.jdbc.sql("SELECT request_summary->>'decision' FROM io.outbound_attempt WHERE attempt_no=3")
                        .query(String.class)
                        .single())
                .isEqualTo(hours > 20 ? "COMPLETE_NOT_FOUND_AFTER_THRESHOLD" : "COMPLETE_NOT_FOUND_WITHIN_THRESHOLD");
    }

    @Test
    void ac_3_1_workerSuspendsCallerTransactionAndNoAdapterLeavesCommandsPending() {
        String id = ready();
        var empty = new OutboundWorker(
                f.jdbc, f.manager, List.of(), f.clock, properties, new Random(7), new OutboundHooks() {}, f.mapper);
        assertThat(empty.processAvailable()).isZero();
        assertThat(commandStatus()).isEqualTo("PENDING");
        f.tx.executeWithoutResult(tx -> {
            worker().processAvailable();
            tx.setRollbackOnly();
        });
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(commandStatus()).isEqualTo("DONE");
    }

    @Test
    void ac_3_11_referenceOwnedByAnotherRefundIsConflictAndUnknownIdentityFails() {
        String payment = f.s3();
        String first = f.accept(payment, 1000).refundId();
        String second = f.accept(payment, 1000).refundId();
        apply(first, "re_shared", Status.pending, Source.WEBHOOK);
        assertThat(apply(second, "re_shared", Status.pending, Source.WEBHOOK)).isEqualTo(Result.CONFLICT);
        assertThat(f.status(second)).isEqualTo("READY");
        assertThatThrownBy(() -> apply(null, "re_absent", Status.succeeded, Source.WEBHOOK))
                .hasMessage("REFUND_NOT_FOUND");
        assertThatThrownBy(() -> evidence.apply(fact(first, "re_shared", Status.succeeded, Source.WEBHOOK)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ac_3_10_limitStopsAutomaticRetryWithoutReleasingFunds() {
        String id = ready();
        properties = properties(2);
        gateway.timeoutBefore = true;
        worker().processAvailable();
        advance(Duration.ofSeconds(10));
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("UNKNOWN");
        assertThat(commandStatus()).isEqualTo("UNKNOWN");
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE next_attempt_at='infinity'::timestamptz"))
                .isEqualTo(1);
        advance(Duration.ofDays(7));
        assertThat(worker().processAvailable()).isZero();
        assertThat(gateway.calls).containsExactly("CREATE", "LIST_ALL");
    }

    @ParameterizedTest
    @EnumSource(
            value = Status.class,
            names = {"succeeded", "failed"})
    void ac_3_8_terminalContradictionsOpenOneConflict(Status terminal) {
        String id = ready();
        assertThat(apply(id, "re_final", terminal, Source.WEBHOOK)).isEqualTo(Result.APPLIED);
        Status opposite = terminal == Status.succeeded ? Status.failed : Status.succeeded;
        assertThat(apply(id, "re_final", opposite, Source.COMMAND_QUERY)).isEqualTo(Result.CONFLICT);
        assertThat(apply(id, "re_final", opposite, Source.BATCH_LINE)).isEqualTo(Result.CONFLICT);
        assertThat(apply(id, "re_final", Status.pending, Source.WEBHOOK)).isEqualTo(Result.DUPLICATE_FACT);
        assertThat(f.count("recon.issue")).isEqualTo(1);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM recon.issue WHERE category='REFUND_STATE_CONFLICT' AND detected_by='INGESTION' AND subject_type='refund'"))
                .isEqualTo(1);
    }

    @Test
    void ac_3_11_pendingReferenceDiscoveryAndMismatchedIdentity() {
        String id = ready();
        assertThat(apply(id, null, Status.pending, Source.WEBHOOK)).isEqualTo(Result.APPLIED);
        long history = f.count("core.refund_transition");
        assertThat(apply(id, "re_known", Status.pending, Source.COMMAND_QUERY)).isEqualTo(Result.APPLIED);
        assertThat(f.count("core.refund_transition")).isEqualTo(history);
        assertThat(apply(null, "re_known", Status.pending, Source.WEBHOOK)).isEqualTo(Result.DUPLICATE_FACT);
        assertThat(apply(id, "re_different", Status.succeeded, Source.WEBHOOK)).isEqualTo(Result.CONFLICT);
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
    }

    @Test
    void ac_3_9_waitingAndCancelledRejectProcessorEvidence() {
        String payment = f.s1();
        String id = f.accept(payment, 2000).refundId();
        for (Status status : Status.values())
            assertThat(apply(id, "re_unexpected", status, Source.WEBHOOK)).isEqualTo(Result.CONFLICT);
        assertThat(f.status(id)).isEqualTo("AWAITING_FUNDS");
        f.refunds.cancel(id, "not sent");
        for (Status status : Status.values())
            assertThat(apply(id, "re_unexpected", status, Source.WEBHOOK)).isEqualTo(Result.CONFLICT);
        assertThat(f.status(id)).isEqualTo("CANCELLED");
    }

    @Test
    void ac_3_11_businessRollbackKeepsAttemptAndRecovers() {
        String id = ready();
        CommandHandler failing = new CommandHandler() {
            @Override
            public String kind() {
                return handler.kind();
            }

            @Override
            public CommandResult execute(CommandSnapshot c, CommandExecution e) {
                return handler.execute(c, e);
            }

            @Override
            public void applyInTransaction(CommandSnapshot c, CommandResult r) {
                handler.applyInTransaction(c, r);
                throw new IllegalStateException("rollback");
            }
        };
        assertThatThrownBy(() -> worker(failing, new OutboundHooks() {}).processAvailable())
                .hasMessage("rollback");
        assertThat(f.status(id)).isEqualTo("READY");
        assertThat(f.count("io.outbound_attempt")).isEqualTo(1);
        assertThat(entries("REFUND_EXECUTED")).isZero();
        advance(Duration.ofSeconds(61));
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
    }

    @Test
    void ac_3_1_occurrenceUsesEvidenceAndCallerRollbackIsAtomic() {
        String id = ready();
        Instant occurred = f.clock.instant();
        advance(Duration.ofDays(3));
        var fact = new ProcessorRefundFact(id, "re_time", Status.succeeded, null, Source.WEBHOOK, "evt_time", occurred);
        f.tx.executeWithoutResult(tx -> {
            evidence.apply(fact);
            tx.setRollbackOnly();
        });
        assertThat(f.status(id)).isEqualTo("READY");
        f.tx.executeWithoutResult(tx -> evidence.apply(fact));
        assertThat(f.jdbc.sql("SELECT occurred_at FROM ledger.journal_entry WHERE entry_type='REFUND_EXECUTED'")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(occurred);
        assertThat(f.jdbc.sql("SELECT evidence_ref FROM core.refund_transition WHERE to_status='SUCCEEDED'")
                        .query(String.class)
                        .single())
                .isEqualTo("evt_time");
    }

    @Test
    void ac_3_10_backoffIsSeededCappedAndNotDueCommandsStayIdle() {
        ready();
        gateway.timeoutBefore = true;
        worker().processAvailable();
        assertThat(worker().processAvailable()).isZero();
        var one = new Random(17);
        var two = new Random(17);
        for (int i = 1; i < 40; i++) {
            Duration delay = properties.backoff(i, one);
            assertThat(delay).isEqualTo(properties.backoff(i, two)).isBetween(properties.base(), properties.cap());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_3_1_gatewayDefinedAfterHandlerConfigurationStillRegistersHandler(boolean adapterPresent) {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class, () -> f.jdbc);
            context.registerBean(dev.ledgerlab.payments.PaymentRefunds.class, () -> f.payments);
            context.registerBean(JdbcRefundEvidence.class, () -> evidence);
            context.registerBean(java.time.Clock.class, () -> f.clock);
            context.registerBean(OutboundProperties.class, () -> properties);
            context.register(RefundCommandConfiguration.class);
            if (adapterPresent) context.register(LateGatewayConfiguration.class);
            context.refresh();
            assertThat(context.getBeansOfType(CommandHandler.class)).hasSize(adapterPresent ? 1 : 0);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class LateGatewayConfiguration {
        @org.springframework.context.annotation.Bean
        dev.ledgerlab.refunds.ProcessorRefundGateway testGateway() {
            return new MemoryRefundGateway();
        }
    }

    private String ready() {
        f.s3();
        return f.accept(f.payment("ch_p2", 5000), 2000).refundId();
    }

    private OutboundProperties properties(int max) {
        return new OutboundProperties(
                "test",
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                max,
                Duration.ofHours(20));
    }

    private OutboundWorker worker() {
        return worker(new OutboundHooks() {});
    }

    private OutboundWorker worker(OutboundHooks hooks) {
        return worker(handler, hooks);
    }

    private OutboundWorker worker(CommandHandler target, OutboundHooks hooks) {
        return new OutboundWorker(
                f.jdbc, f.manager, List.of(target), f.clock, properties, new Random(7), hooks, f.mapper);
    }

    private String commandStatus() {
        return f.jdbc.sql("SELECT status FROM io.outbound_command")
                .query(String.class)
                .single();
    }

    private Instant firstSent() {
        return f.jdbc.sql("SELECT first_sent_at FROM io.outbound_command")
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();
    }

    private long entries(String type) {
        return f.jdbc.sql("SELECT count(*) FROM ledger.journal_entry WHERE entry_type=:type")
                .param("type", type)
                .query(Long.class)
                .single();
    }

    private void advance(Duration duration) {
        f.clock.set(f.clock.instant().plus(duration));
    }

    private ProcessorRefundFact fact(
            @org.jspecify.annotations.Nullable String id,
            @org.jspecify.annotations.Nullable String ref,
            Status status,
            Source source) {
        return new ProcessorRefundFact(
                id,
                ref,
                status,
                status == Status.failed ? "failed" : null,
                source,
                "evidence_" + source,
                f.clock.instant());
    }

    private Result apply(
            @org.jspecify.annotations.Nullable String id,
            @org.jspecify.annotations.Nullable String ref,
            Status status,
            Source source) {
        return f.tx.execute(tx -> evidence.apply(fact(id, ref, status, source)));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
