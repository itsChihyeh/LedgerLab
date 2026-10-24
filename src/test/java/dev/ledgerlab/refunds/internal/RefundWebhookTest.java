package dev.ledgerlab.refunds.internal;

import static dev.ledgerlab.ingestion.internal.RefundHttpServer.await;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ingestion.internal.RefundInbox;
import dev.ledgerlab.ingestion.internal.WorkerHooks;
import dev.ledgerlab.outbound.CommandExecution;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import dev.ledgerlab.refunds.ProcessorRefundFact.Status;
import dev.ledgerlab.refunds.RefundEvidence;
import dev.ledgerlab.testsupport.InboxHistoryAssertions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class RefundWebhookTest {
    private RefundFixture f;
    private MemoryRefundGateway gateway;
    private JdbcRefundEvidence evidence;
    private RefundCommandHandler handler;
    private OutboundProperties properties;
    private RefundInbox inbox;
    private int next;

    @BeforeEach
    void setup() {
        f = new RefundFixture();
        gateway = new MemoryRefundGateway();
        evidence =
                new JdbcRefundEvidence(f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        inbox = inbox(evidence);
        properties = new OutboundProperties(
                "webhook-test",
                1,
                Duration.ofSeconds(60),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                20,
                Duration.ofHours(20));
        handler = new RefundCommandHandler(f.jdbc, f.payments, gateway, evidence, f.clock, properties);
    }

    @AfterEach
    void close() {
        try {
            f.invariants();
            InboxHistoryAssertions.assertConsistent(f.jdbc);
            assertThat(f.number("""
                    SELECT count(*) FROM io.inbox_event e JOIN LATERAL
                    (SELECT result FROM io.inbox_status_history WHERE event_id=e.event_id ORDER BY id DESC LIMIT 1) h ON true
                    WHERE e.result IS DISTINCT FROM h.result
                    """)).isZero();
        } finally {
            f.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"refund.updated", "refund.created"})
    void ac_4_2_ac_3_17_webhookBeforeResponseWinsAndCommandFinishes(String type) {
        String id = ready(2000);
        var event = event(id, type, "succeeded", 2000);
        gateway.afterCreate = () -> {
            assertThat(f.status(id)).isEqualTo("READY");
            inbox.receive(event);
            inbox.drain();
            assertThat(f.status(id)).isEqualTo("SUCCEEDED");
            assertThat(commandStatus()).isEqualTo("IN_FLIGHT");
        };
        outbound().processAvailable();
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT occurred_at FROM ledger.journal_entry WHERE entry_type='REFUND_EXECUTED'")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(Instant.ofEpochSecond(event.path("created").longValue()));
        assertThat(f.jdbc.sql("SELECT evidence_source FROM core.refund_transition WHERE to_status='SUCCEEDED'")
                        .query(String.class)
                        .single())
                .isEqualTo("WEBHOOK");
        outcome(event, "APPLIED");
    }

    @Test
    void ac_2_9_responseThenBothEventsAndRedeliveryExecuteOnce() {
        String id = ready(2000);
        outbound().processAvailable();
        var created = event(id, "refund.created", "succeeded", 2000);
        inbox.receive(created);
        inbox.receive(created);
        var updated = event(id, "refund.updated", "succeeded", 2000);
        inbox.receive(updated);
        var reissued = created.deepCopy().put("id", "evt_refund_reissued");
        inbox.receive(reissued);
        inbox.drain();
        outcome(created, "DUPLICATE_FACT");
        outcome(updated, "DUPLICATE_FACT");
        outcome(reissued, "DUPLICATE_FACT");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM io.webhook_delivery WHERE outcome='DUPLICATE'"))
                .isEqualTo(1);
    }

    @Test
    void ac_2_9_threeConcurrentEvidenceTransactionsExecuteOnce() throws Exception {
        String id = ready(2000);
        var created = event(id, "refund.created", "succeeded", 2000);
        var updated = event(id, "refund.updated", "succeeded", 2000);
        inbox.receive(created);
        inbox.receive(updated);
        var barrier = new CyclicBarrier(3);
        var hooks = new WorkerHooks() {
            @Override
            public void afterFetch(String eventId) {
                cross(barrier);
            }
        };
        var command = outbound(handler, new OutboundHooks() {
            @Override
            public void beforeWriteback(CommandSnapshot snapshot) {
                cross(barrier);
            }
        });
        try (var pool = Executors.newFixedThreadPool(3)) {
            var a = pool.submit(inbox.worker(hooks)::processAvailable);
            var b = pool.submit(inbox.worker(hooks)::processAvailable);
            var c = pool.submit(command::processAvailable);
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(c.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
        assertThat(f.number(
                        "SELECT count(*) FROM io.inbox_event WHERE event_type LIKE 'refund.%' AND status='PROCESSED'"))
                .isEqualTo(2);
    }

    @Test
    void ac_3_18_submittedR2ExecutesAfterP4AndUsesEventTime() {
        String id = f.accept(f.s3(), 3000).refundId();
        gateway.status = Status.pending;
        outbound().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(f.balance(1101)).isZero();
        assertThat(f.balance(2103)).isEqualTo(3000);
        f.payment("ch_p4", 5000);
        var event = event(id, "refund.updated", "succeeded", 3000);
        inbox.receive(event);
        inbox.drain();
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(f.balance(1101)).isEqualTo(2000);
        assertThat(f.balance(2103)).isZero();
        outcome(event, "APPLIED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_4_9_unknownAndInboxSuccessSerializeInBothOrders(boolean timeoutFirst) throws Exception {
        String id = ready(2000);
        gateway.timeoutBefore = true;
        var event = event(id, "refund.updated", "succeeded", 2000);
        inbox.receive(event);
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
        RefundInbox controlledInbox = inbox(fact -> {
            if (!timeoutFirst) {
                f.ledger.lockFunds();
                firstLocked.countDown();
                await(release);
            } else secondStarted.countDown();
            return evidence.apply(fact);
        });
        Runnable webhook = controlledInbox::drain;
        Runnable command = () -> outbound(controlled, new OutboundHooks() {}).processAvailable();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(timeoutFirst ? command : webhook);
            try {
                await(firstLocked);
                var b = pool.submit(timeoutFirst ? webhook : command);
                await(secondStarted);
                release.countDown();
                a.get(20, TimeUnit.SECONDS);
                b.get(20, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
        outcome(event, "APPLIED");
    }

    @ParameterizedTest
    @CsvSource({"refund.failed,failed", "refund.updated,canceled", "refund.created,failed"})
    void ac_3_8_failureUsesObjectStatusAndReleasesQueue(String type, String status) {
        String payment = f.s3();
        String id = f.accept(payment, 5000).refundId();
        String waiting = f.accept(payment, 5000).refundId();
        var event = event(id, type, status, 5000);
        object(event).put("failure_reason", "lost_or_stolen_card");
        inbox.receive(event);
        inbox.drain();
        assertThat(f.status(id)).isEqualTo("FAILED");
        assertThat(f.status(waiting)).isEqualTo("READY");
        assertThat(f.balance(2102)).isEqualTo(4680);
        assertThat(f.balance(2103)).isEqualTo(5000);
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isEqualTo(5000);
        assertThat(entries("REFUND_FAILED")).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT occurred_at FROM ledger.journal_entry WHERE entry_type='REFUND_FAILED'")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(Instant.ofEpochSecond(event.path("created").longValue()));
        assertThat(f.jdbc.sql("SELECT failure_reason FROM core.refund WHERE id=?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("lost_or_stolen_card");
        outcome(event, "APPLIED");
    }

    @Test
    void ac_2_9_pendingOnlyChangesHistoryAndDoesNotRegressTerminalState() {
        String id = ready(2000);
        var first = event(id, "refund.failed", "pending", 2000);
        inbox.receive(first);
        inbox.drain();
        outcome(first, "APPLIED");
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(entries("REFUND_EXECUTED")).isZero();
        assertThat(entries("REFUND_FAILED")).isZero();
        var second = event(id, "refund.updated", "pending", 2000);
        inbox.receive(second);
        inbox.drain();
        outcome(second, "DUPLICATE_FACT");
        var success = event(id, "refund.failed", "succeeded", 2000);
        inbox.receive(success);
        inbox.drain();
        outcome(success, "APPLIED");
        var late = event(id, "refund.created", "pending", 2000);
        inbox.receive(late);
        inbox.drain();
        outcome(late, "DUPLICATE_FACT");
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "foreign", "unknown", "number"})
    void ac_2_9_unknownMetadataRejectsWithoutParkingOrProcessorIdFallback(String kind) {
        String id = ready(2000);
        outbound().processAvailable();
        var event = event(id, "refund.created", "succeeded", 2000);
        var metadata = object(event).putObject("metadata");
        if (kind.equals("foreign")) metadata.put("ledgerlab_refund_id", "foreign");
        if (kind.equals("unknown")) metadata.put("ledgerlab_refund_id", "rfd_unknown");
        if (kind.equals("number")) metadata.put("ledgerlab_refund_id", 123);
        inbox.receive(event);
        inbox.drain();
        outcome(event, "UNKNOWN_REFUND");
        assertThat(f.jdbc.sql("SELECT category, subject_type, subject_id FROM recon.issue")
                        .query()
                        .singleRow())
                .containsEntry("category", "MISSING_IN_LEDGER")
                .containsEntry("subject_type", "refund")
                .containsEntry("subject_id", "re_memory_1");
        assertThat(f.jdbc.sql("SELECT actual->>'charge' FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("ch_p1");
        assertThat(f.number("SELECT (actual->>'amount')::bigint FROM recon.issue"))
                .isEqualTo(2000);
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "charge", "terminal"})
    void ac_2_9_conflictIsRecordedOnceWithoutChangingRefund(String mismatch) {
        String id = ready(2000);
        if (mismatch.equals("terminal")) outbound().processAvailable();
        String before = f.status(id);
        long postings = f.count("ledger.posting");
        long history = f.count("core.refund_transition");
        var event = event(id, "refund.updated", mismatch.equals("terminal") ? "failed" : "succeeded", 2000);
        if (mismatch.equals("amount")) object(event).put("amount", 2001);
        if (mismatch.equals("charge")) object(event).put("charge", "ch_other");
        inbox.receive(event);
        inbox.drain();
        outcome(event, "BUSINESS_CONFLICT");
        assertThat(f.status(id)).isEqualTo(before);
        assertThat(f.count("core.refund_transition")).isEqualTo(history);
        assertThat(f.count("ledger.posting")).isEqualTo(postings);
        assertThat(f.count("recon.issue")).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT category FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("REFUND_STATE_CONFLICT");
    }

    @ParameterizedTest
    @CsvSource({
        "amount,null,INVALID_AMOUNT",
        "amount,1.5,INVALID_AMOUNT",
        "amount,1e3,INVALID_AMOUNT",
        "amount,\"2000\",INVALID_AMOUNT",
        "amount,0,INVALID_AMOUNT",
        "amount,-1,INVALID_AMOUNT",
        "amount,100000000,INVALID_AMOUNT",
        "amount,9223372036854775808,INVALID_AMOUNT",
        "amount,true,INVALID_AMOUNT",
        "amount,[],INVALID_AMOUNT",
        "amount,MISSING,INVALID_AMOUNT",
        "currency,\"usd\",UNSUPPORTED_CURRENCY",
        "currency,MISSING,UNSUPPORTED_CURRENCY",
        "id,MISSING,UNSUPPORTED_TYPE",
        "charge,MISSING,UNSUPPORTED_TYPE",
        "status,MISSING,UNSUPPORTED_TYPE",
        "status,\"unknown\",UNSUPPORTED_TYPE",
        "failure_reason,{},UNSUPPORTED_TYPE"
    })
    void ac_2_9_invalidObjectsAreRejectedNotRetried(String field, String json, String result) {
        String id = ready(2000);
        var event = event(id, "refund.updated", "succeeded", 2000);
        if (json.equals("MISSING")) object(event).remove(field);
        else object(event).set(field, f.mapper.readTree(json));
        inbox.receive(event);
        inbox.drain();
        outcome(event, result);
        assertThat(f.status(id)).isEqualTo("READY");
        assertThat(f.jdbc.sql("SELECT category, subject_type, subject_id FROM recon.issue")
                        .query()
                        .singleRow())
                .containsEntry("category", "UNSUPPORTED_EVENT")
                .containsEntry("subject_type", "event")
                .containsEntry("subject_id", event.path("id").asString());
        assertThat(f.number("SELECT sum(attempts) FROM io.inbox_event")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_8_2_liveHasPriorityOverInvalidAmountAndUnknownMetadata(boolean envelope) {
        var event = event("unknown", "refund.created", "succeeded", 0);
        (envelope ? event : object(event)).put("livemode", true);
        inbox.receive(event);
        inbox.drain();
        outcome(event, "LIVEMODE");
        assertThat(f.jdbc.sql("SELECT category FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("LIVEMODE_EVENT");
        assertThat(f.count("ledger.posting")).isZero();
    }

    @Test
    void ac_4_6_businessCrashRollsBackRefundAndHistoryThenRetriesOnce() {
        String id = ready(2000);
        var event = event(id, "refund.updated", "succeeded", 2000);
        inbox.receive(event);
        inbox.worker(new WorkerHooks() {
                    @Override
                    public void afterBusiness(String eventId) {
                        throw new IllegalStateException("crash after refund");
                    }
                })
                .processAvailable();
        assertThat(f.status(id)).isEqualTo("READY");
        assertThat(entries("REFUND_EXECUTED")).isZero();
        assertThat(f.number("SELECT count(*) FROM core.refund_transition WHERE evidence_source='WEBHOOK'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM io.inbox_status_history WHERE event_id='"
                        + event.path("id").asString() + "'"))
                .isEqualTo(1);
        f.clock.set(f.clock.instant().plusSeconds(2));
        inbox.drain();
        outcome(event, "APPLIED");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
    }

    private String ready(long amount) {
        String payment = f.s3();
        f.payment("ch_p2", 5000);
        String id = f.accept(payment, amount).refundId();
        inbox.drain();
        return id;
    }

    private ObjectNode event(String id, String type, String status, long amount) {
        ObjectNode event = f.mapper
                .createObjectNode()
                .put("id", "evt_refund_" + ++next)
                .put("object", "event")
                .put("api_version", "2026-09-30.endive")
                .put("type", type)
                .put("livemode", false)
                .put("created", f.clock.instant().minusSeconds(10).getEpochSecond());
        var refund = event.putObject("data").putObject("object");
        refund.put("id", "re_memory_1")
                .put("object", "refund")
                .put("status", status)
                .put("amount", amount)
                .put("charge", "ch_p1")
                .put("currency", "cad")
                .put("created", f.clock.instant().minusSeconds(500).getEpochSecond());
        refund.putObject("metadata").put("ledgerlab_refund_id", id);
        return event;
    }

    private static ObjectNode object(ObjectNode event) {
        return (ObjectNode) event.path("data").path("object");
    }

    private void outcome(ObjectNode event, String result) {
        assertThat(f.jdbc.sql("SELECT status, result, parked_on FROM io.inbox_event WHERE event_id=?")
                        .param(event.path("id").asString())
                        .query()
                        .singleRow())
                .containsEntry(
                        "status", List.of("APPLIED", "DUPLICATE_FACT").contains(result) ? "PROCESSED" : "REJECTED")
                .containsEntry("result", result)
                .containsEntry("parked_on", null);
    }

    private RefundInbox inbox(RefundEvidence target) {
        return new RefundInbox(f.jdbc, f.manager, f.capture, f.issues, f.mapper, f.clock, f.batches, target);
    }

    private OutboundWorker outbound() {
        return outbound(handler, new OutboundHooks() {});
    }

    private OutboundWorker outbound(CommandHandler target, OutboundHooks hooks) {
        return new OutboundWorker(
                f.jdbc, f.manager, List.of(target), f.clock, properties, new Random(9), hooks, f.mapper);
    }

    private String commandStatus() {
        return f.jdbc.sql("SELECT status FROM io.outbound_command")
                .query(String.class)
                .single();
    }

    private long entries(String type) {
        return f.jdbc.sql("SELECT count(*) FROM ledger.journal_entry WHERE entry_type=?")
                .param(type)
                .query(Long.class)
                .single();
    }

    private static void cross(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
