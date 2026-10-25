package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.investigation.internal.RefundQueryHarness;
import dev.ledgerlab.refunds.Refunds;
import dev.ledgerlab.testsupport.Sql;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

class RefundHttpTest {
    private static final String CANCEL = "{\"reason\":\"customer request\"}";
    private RefundFixture f;
    private RefundApiProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        f = new RefundFixture();
        properties = new RefundApiProperties();
        mvc = controller(f.refunds, () -> {}, () -> {});
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private MockMvc controller(Refunds refunds, Runnable before, Runnable after) {
        var api =
                new RefundApi(f.jdbc, f.ledger, f.payments, refunds, f.clock, f.mapper, f.manager, properties, before);
        return MockMvcBuilders.standaloneSetup(new RefundController(api, f.mapper, after))
                .build();
    }

    private static String request(long amount, String ref) {
        return "{\"payment_ref\":\"ch_p1\",\"amount\":" + amount + ",\"merchant_refund_ref\":\"" + ref + "\"}";
    }

    private MockHttpServletResponse postTo(MockMvc target, String path, String key, String body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return target.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse accept(String key, long amount, String ref) throws Exception {
        return postTo(mvc, "/api/refunds", key, request(amount, ref));
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return f.mapper.readTree(response.getContentAsString());
    }

    private String refundId(MockHttpServletResponse response) throws Exception {
        return json(response).path("refund_id").asString();
    }

    private void error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(json(response).path("code").asString()).isEqualTo(code);
    }

    // Include all durable effects in write/error/retry assertions.
    private record Snapshot(
            long refunds,
            long postings,
            long commands,
            long idempotency,
            long entries,
            long history,
            long reserved,
            long available,
            long payable) {}

    private Snapshot snapshot() {
        return new Snapshot(
                f.count("core.refund"),
                f.count("ledger.posting"),
                f.count("io.outbound_command"),
                f.count("io.idempotency_record"),
                f.count("ledger.journal_entry"),
                f.count("core.refund_transition"),
                f.number("SELECT coalesce(sum(refunded_amount), 0) FROM core.payment"),
                f.balance(2102),
                f.balance(2103));
    }

    private void accepted(Snapshot before, long amount, boolean ready, long cacheRows) {
        assertThat(snapshot())
                .isEqualTo(new Snapshot(
                        before.refunds() + 1,
                        before.postings() + 2,
                        before.commands() + (ready ? 1 : 0),
                        before.idempotency() + cacheRows,
                        before.entries() + 1,
                        before.history() + (ready ? 2 : 1),
                        before.reserved() + amount,
                        before.available() - amount,
                        before.payable() + amount));
        f.invariants();
    }

    @Test
    void ac_2_10_sameKeyReturnsOriginalBytesAndCanonicalizesInput() throws Exception {
        String payment = f.s3();
        var before = snapshot();
        var first = accept("K1", 2000, "MR-1");
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(json(first).path("status").asString()).isEqualTo("READY");
        assertThat(json(first).path("payment_ref").asString()).isEqualTo(payment);
        assertThat(json(first).path("accepted_at").asString())
                .isEqualTo(f.clock.instant().toString());
        accepted(before, 2000, true, 1);
        var after = snapshot();
        var retry = postTo(mvc, "/api/refunds", "K1", """
                { "merchant_refund_ref" : "MR-1", "amount" : 2000, "payment_ref" : "ch_p1" }
                """);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot()).isEqualTo(after);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM io.idempotency_record WHERE state='COMPLETED' AND expires_at-created_at=interval '24 hours'"))
                .isEqualTo(1);
    }

    @Test
    void ac_2_11_changedAmountOrEndpointReusesKeyWithoutWrites() throws Exception {
        f.s1();
        var before = snapshot();
        var first = accept("K1", 2000, "MR-1");
        accepted(before, 2000, false, 1);
        var after = snapshot();
        error(accept("K1", 2001, "MR-1"), 422, "IDEMPOTENCY_KEY_REUSED");
        error(postTo(mvc, "/api/refunds/" + refundId(first) + "/cancel", "K1", CANCEL), 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(snapshot()).isEqualTo(after);
    }

    @Test
    void ac_2_12_concurrentFirstRequestsCreateExactlyOneRefund() throws Exception {
        f.s3();
        var before = snapshot();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return accept("K2", 2000, "MR-1");
            });
            var second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return accept("K2", 2000, "MR-1");
            });
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.getStatus()).isEqualTo(201);
            assertThat(b.getStatus()).isEqualTo(201);
            assertThat(b.getContentAsString()).isEqualTo(a.getContentAsString());
        }
        accepted(before, 2000, true, 1);
    }

    @Test
    void ac_2_12_lockTimeoutReturnsConflictAndRetrySucceeds() throws Exception {
        f.s3();
        properties.setIdempotencyLockTimeout(Duration.ofMillis(100));
        var before = snapshot();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> f.tx.executeWithoutResult(tx -> {
                f.ledger.lockFunds();
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                error(accept("K2", 2000, "MR-1"), 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
                assertThat(snapshot()).isEqualTo(before);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(accept("K2", 2000, "MR-1").getStatus()).isEqualTo(201);
        accepted(before, 2000, true, 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test control");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "has space", "non-ascii-é", "has\tcontrol"})
    void ac_2_13_acceptAndCancelRequireVisibleAsciiKey(String key) throws Exception {
        f.s1();
        var before = snapshot();
        error(postTo(mvc, "/api/refunds", key, request(2000, "MR-1")), 400, "IDEMPOTENCY_KEY_REQUIRED");
        error(
                postTo(mvc, "/api/refunds/rfd_01JB0000000000000000000001/cancel", key, CANCEL),
                400,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_2_13_keyLengthBoundariesApplyToAcceptAndCancel() throws Exception {
        f.s1();
        var before = snapshot();
        error(postTo(mvc, "/api/refunds", "k".repeat(256), request(2000, "MR-1")), 400, "IDEMPOTENCY_KEY_REQUIRED");
        error(
                postTo(mvc, "/api/refunds/rfd_01JB0000000000000000000001/cancel", "k".repeat(256), CANCEL),
                400,
                "IDEMPOTENCY_KEY_REQUIRED");
        assertThat(snapshot()).isEqualTo(before);
        var first = accept("k".repeat(255), 2000, "MR-1");
        assertThat(first.getStatus()).isEqualTo(201);
        accepted(before, 2000, false, 1);
        var retry = accept("!", 2000, "MR-1");
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        accepted(before, 2000, false, 2);
    }

    @Test
    void ac_2_14_expiredBusinessReplayReturnsInitialAwaitingFundsAfterRelease() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        assertThat(json(first).path("status").asString()).isEqualTo("AWAITING_FUNDS");
        f.batch("po_b1", "ch_p1", 10000, 320);
        assertThat(f.status(refundId(first))).isEqualTo("READY");
        f.clock.set(f.clock.instant().plus(Duration.ofHours(25)));
        var before = snapshot();
        assertThat(cleaner().cleanBatch()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(withCache(before, 0));
        var replay = accept("K9", 2000, "MR-1");
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    private static Snapshot withCache(Snapshot before, long count) {
        return new Snapshot(
                before.refunds(),
                before.postings(),
                before.commands(),
                count,
                before.entries(),
                before.history(),
                before.reserved(),
                before.available(),
                before.payable());
    }

    private IdempotencyCleanup cleaner() {
        return new IdempotencyCleanup(f.jdbc, f.clock, properties, f.manager, f.ledger);
    }

    @Test
    void ac_2_14_cleanupUsesInclusiveClockBoundaryAndBoundedBatches() throws Exception {
        f.s1();
        var start = f.clock.instant();
        accept("K1", 1, "MR-1");
        accept("K2", 1, "MR-1");
        f.clock.set(start.plusSeconds(1));
        accept("K3", 1, "MR-1");
        var before = snapshot();
        properties.setIdempotencyCleanupBatchSize(1);
        f.clock.set(start.plus(Duration.ofHours(24)).minusNanos(1000));
        assertThat(cleaner().cleanBatch()).isZero();
        assertThat(snapshot()).isEqualTo(before);
        f.clock.set(start.plus(Duration.ofHours(24)));
        assertThat(cleaner().cleanBatch()).isEqualTo(1);
        assertThat(cleaner().cleanBatch()).isEqualTo(1);
        assertThat(cleaner().cleanBatch()).isZero();
        assertThat(snapshot()).isEqualTo(withCache(before, 1));
        assertThat(f.number("SELECT count(*) FROM io.idempotency_record WHERE idem_key='K3'"))
                .isEqualTo(1);
    }

    @Test
    void ac_2_15_businessConflictRollsBackCacheAndSameKeyCanBeCorrected() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        var before = snapshot();
        error(accept("K2", 2001, "MR-1"), 409, "BUSINESS_REF_CONFLICT");
        assertThat(snapshot()).isEqualTo(before);
        var retry = accept("K2", 2000, "MR-1");
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot()).isEqualTo(withCache(before, 2));
    }

    @Test
    void ac_3_5_excessRefundWritesNothingAndDoesNotCacheError() throws Exception {
        f.s3();
        accept("K1", 2000, "MR-1");
        var before = snapshot();
        error(accept("K2", 9000, "MR-2"), 409, "REFUND_EXCEEDS_REMAINING");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(accept("K2", 1000, "MR-2").getStatus()).isEqualTo(201);
        accepted(before, 1000, true, 1);
    }

    @Test
    void ac_3_5_missingAndVoidedPaymentsDoNotLeaveIdempotencyRecords() throws Exception {
        var before = snapshot();
        error(accept("K1", 2000, "MR-1"), 404, "PAYMENT_NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
        f.s1();
        // Deliberate state fixture; this test introduces no P4 correction implementation.
        f.jdbc.sql("UPDATE core.payment SET status='VOIDED'").update();
        before = snapshot();
        error(accept("K1", 2000, "MR-1"), 409, "PAYMENT_NOT_REFUNDABLE");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_3_9_cancelRestoresQuotaAndSameKeyReplaysWithoutExtraRows() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        String path = "/api/refunds/" + refundId(first) + "/cancel";
        var before = snapshot();
        var cancelled = postTo(mvc, path, "C1", CANCEL);
        assertThat(cancelled.getStatus()).isEqualTo(200);
        assertThat(json(cancelled).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(snapshot())
                .isEqualTo(new Snapshot(
                        1, before.postings() + 2, 0, 2, before.entries() + 1, before.history() + 1, 0, 0, 0));
        f.invariants();
        var after = snapshot();
        var retry = postTo(mvc, path, "C1", CANCEL);
        assertThat(retry.getStatus()).isEqualTo(200);
        assertThat(retry.getContentAsString()).isEqualTo(cancelled.getContentAsString());
        error(postTo(mvc, path, "C1", "{\"reason\":\"changed\"}"), 422, "IDEMPOTENCY_KEY_REUSED");
        error(postTo(mvc, path, "C2", CANCEL), 409, "REFUND_NOT_CANCELLABLE");
        assertThat(snapshot()).isEqualTo(after);
        assertThat(accept("K2", 10000, "MR-2").getStatus()).isEqualTo(201);
        accepted(after, 10000, false, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "SUBMITTED", "UNKNOWN", "SUCCEEDED", "FAILED"})
    void ac_3_9_readyAndLaterStatesCannotBeCancelled(String state) throws Exception {
        f.s3();
        var first = accept("K1", 2000, "MR-1");
        f.jdbc.sql(
                        "UPDATE core.refund SET status=:state, resolved_at=CASE WHEN :state IN ('SUCCEEDED','FAILED') THEN accepted_at ELSE NULL END")
                .param("state", state)
                .update();
        var before = snapshot();
        error(postTo(mvc, "/api/refunds/" + refundId(first) + "/cancel", "C1", CANCEL), 409, "REFUND_NOT_CANCELLABLE");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_3_9_missingRefundAndMalformedIdAreRejectedWithoutWrites() throws Exception {
        var before = snapshot();
        error(postTo(mvc, "/api/refunds/rfd_01JB0000000000000000000001/cancel", "C1", CANCEL), 404, "REFUND_NOT_FOUND");
        error(postTo(mvc, "/api/refunds/pay_bad/cancel", "C1", CANCEL), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "100.5",
                "\"20.00\"",
                "0",
                "-1",
                "null",
                "100000000",
                "9223372036854775808",
                "-9223372036854775809",
                "1e2",
                "1.0",
                "true",
                "{}",
                "[]"
            })
    void ac_3_16_invalidAmountsNeverCoerceOrWrite(String amount) throws Exception {
        f.s1();
        var before = snapshot();
        error(
                postTo(
                        mvc,
                        "/api/refunds",
                        "K1",
                        "{\"payment_ref\":\"ch_p1\",\"merchant_refund_ref\":\"MR-1\",\"amount\":" + amount + "}"),
                400,
                "INVALID_AMOUNT");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_3_16_missingAmountIsInvalidAndRefundsBelowFiftyCentsAreAllowed() throws Exception {
        f.payment("ch_p1", 100);
        var before = snapshot();
        error(
                postTo(mvc, "/api/refunds", "K1", "{\"payment_ref\":\"ch_p1\",\"merchant_refund_ref\":\"MR-1\"}"),
                400,
                "INVALID_AMOUNT");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(accept("K1", 51, "MR-1").getStatus()).isEqualTo(201);
        accepted(before, 51, false, 1);
        before = snapshot();
        assertThat(accept("K2", 49, "MR-2").getStatus()).isEqualTo(201);
        accepted(before, 49, false, 1);
    }

    @Test
    void ac_3_16_maximumAmountIsAccepted() throws Exception {
        f.payment("ch_p1", 99999999);
        var before = snapshot();
        assertThat(accept("K1", 99999999, "MR-1").getStatus()).isEqualTo(201);
        accepted(before, 99999999, false, 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "null",
                "[]",
                "true",
                "{",
                "{} {}",
                "{\"amount\":1}",
                "{\"amount\":1,\"payment_ref\":\"pay_bad\",\"merchant_refund_ref\":\"MR-1\"}",
                "{\"amount\":1,\"payment_ref\":\"ch_p1\",\"merchant_refund_ref\":\" \"}",
                "{\"amount\":1,\"payment_ref\":null,\"merchant_refund_ref\":\"MR-1\"}",
                "{\"amount\":1,\"payment_ref\":\"ch_p1\",\"merchant_refund_ref\":17}",
                "{\"amount\":1,\"amount\":2}",
                "{\"unknown\":1}"
            })
    void ac_3_16_malformedRequestFieldsWriteNothing(String raw) throws Exception {
        var before = snapshot();
        error(postTo(mvc, "/api/refunds", "K1", raw), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_3_16_businessReferenceLengthUsesUnicodeCodePoints() throws Exception {
        f.s1();
        var before = snapshot();
        error(accept("K1", 1, "x".repeat(65)), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(accept("K1", 1, "😀".repeat(64)).getStatus()).isEqualTo(201);
        accepted(before, 1, false, 1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{}",
                "{\"reason\":null}",
                "{\"reason\":\" \"}",
                "{\"reason\":1}",
                "{\"reason\":\"x\",\"unknown\":1}",
                "{\"reason\":\"\\u0000\"}"
            })
    void ac_3_9_invalidCancellationReasonWritesNothing(String raw) throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        var before = snapshot();
        error(postTo(mvc, "/api/refunds/" + refundId(first) + "/cancel", "C1", raw), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_3_12_failureAfterRefundInsertRollsBackEveryApiWrite() throws Exception {
        f.s3();
        var before = snapshot();
        var broken = f.service(f.ledger, new LockedRefundLimitGuard(f.ledger, f.payments), () -> {
            throw new IllegalStateException("after refund insert");
        });
        var failedMvc = controller(broken, () -> {}, () -> {});
        assertThatThrownBy(() -> postTo(failedMvc, "/api/refunds", "K1", request(2000, "MR-1")))
                .hasRootCauseMessage("after refund insert");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
        assertThat(accept("K1", 2000, "MR-1").getStatus()).isEqualTo(201);
        accepted(before, 2000, true, 1);
    }

    @Test
    void ac_4_8_failureAfterCacheCompletionBeforeCommitRollsBackAndRetries() throws Exception {
        f.s3();
        var before = snapshot();
        var failedMvc = controller(
                f.refunds,
                () -> {
                    assertThat(f.number("SELECT count(*) FROM io.idempotency_record WHERE state='COMPLETED'"))
                            .isEqualTo(1);
                    assertThat(f.count("io.outbound_command")).isEqualTo(1);
                    throw new IllegalStateException("before commit");
                },
                () -> {});
        assertThatThrownBy(() -> postTo(failedMvc, "/api/refunds", "K4", request(2000, "MR-1")))
                .hasRootCauseMessage("before commit");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
        assertThat(accept("K4", 2000, "MR-1").getStatus()).isEqualTo(201);
        accepted(before, 2000, true, 1);
    }

    @Test
    void ac_4_7_responseFailureAfterCommitRetriesStoredResult() throws Exception {
        f.s3();
        var before = snapshot();
        var failedMvc = controller(f.refunds, () -> {}, () -> {
            // A separate connection sees committed data before response writing fails.
            assertThat(f.count("core.refund")).isEqualTo(1);
            assertThat(f.count("io.idempotency_record")).isEqualTo(1);
            throw new IllegalStateException("response lost");
        });
        assertThatThrownBy(() -> postTo(failedMvc, "/api/refunds", "K3", request(2000, "MR-1")))
                .hasRootCauseMessage("response lost");
        accepted(before, 2000, true, 1);
        var after = snapshot();
        String original = f.jdbc.sql("SELECT response_body::text FROM io.idempotency_record")
                .query(String.class)
                .single();
        var retry = accept("K3", 2000, "MR-1");
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(json(retry)).isEqualTo(f.mapper.readTree(original));
        assertThat(snapshot()).isEqualTo(after);
    }

    @Test
    void ac_2_10_clientIdentityComesFromConfigurationNotHeaders() throws Exception {
        f.s1();
        properties.setClientId("configured-merchant");
        var before = snapshot();
        var first = mvc.perform(post("/api/refunds")
                        .header("Idempotency-Key", "K1")
                        .header("Client-Id", "untrusted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(2000, "MR-1")))
                .andReturn()
                .getResponse();
        assertThat(first.getStatus()).isEqualTo(201);
        accepted(before, 2000, false, 1);
        assertThat(f.jdbc.sql("SELECT client_id FROM io.idempotency_record")
                        .query(String.class)
                        .single())
                .isEqualTo("configured-merchant");
        var after = snapshot();
        assertThat(accept("K1", 2000, "MR-1").getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot()).isEqualTo(after);
        properties.setClientId("second-configured-client");
        assertThat(accept("K1", 2000, "MR-1").getContentAsString()).isEqualTo(first.getContentAsString());
        accepted(before, 2000, false, 2);
    }

    @Test
    void ac_2_14_readonlyDetailReturnsCurrentStateHistoryAndRedactedCommands() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        var queries = RefundQueryHarness.mvc(f.db.readonly(), f.clock);
        String path = "/api/refunds/" + refundId(first);
        var initial = queries.perform(get(path)).andReturn().getResponse();
        assertThat(initial.getStatus()).isEqualTo(200);
        assertThat(json(initial).path("refund").path("status").asString()).isEqualTo("AWAITING_FUNDS");
        assertThat(json(initial).path("commands").size()).isZero();
        f.batch("po_b1", "ch_p1", 10000, 320);
        var before = snapshot();
        var current = queries.perform(get(path)).andReturn().getResponse();
        assertThat(json(current).path("refund").path("status").asString()).isEqualTo("READY");
        assertThat(json(current).path("history").size()).isEqualTo(2);
        assertThat(json(current).path("commands").size()).isEqualTo(1);
        assertThat(json(current).path("commands").get(0).path("status").asString())
                .isEqualTo("PENDING");
        assertThat(current.getContentAsString())
                .doesNotContain(
                        "response_body", "request_summary", "lease_owner", "initial_result", "request_fingerprint");
        assertThat(queries.perform(get("/api/refunds/rfd_missing"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(Sql.queryLong(
                        f.db.migrator(),
                        "SELECT count(*) FROM flyway.flyway_schema_history WHERE version='16' AND success"))
                .isEqualTo(1);
        assertThat(Sql.queryLong(f.db.readonly(), "SELECT count(*) FROM core.v_refund_command_summary"))
                .isEqualTo(1);
        Sql.assertRejected("42501", f.db.readonly(), "SELECT * FROM io.outbound_command");
        Sql.assertRejected("42501", f.db.readonly(), "UPDATE core.v_refund_command_summary SET status='DONE'");
        Sql.assertRejected("42501", f.db.app(), "SELECT * FROM core.v_refund_command_summary");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_2_14_readonlyDetailShowsCancellationTimesAndReason() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        String id = refundId(first);
        postTo(mvc, "/api/refunds/" + id + "/cancel", "C1", CANCEL);
        var before = snapshot();
        var response = RefundQueryHarness.mvc(f.db.readonly(), f.clock)
                .perform(get("/api/refunds/" + id))
                .andReturn()
                .getResponse();
        assertThat(json(response).path("refund").path("resolved_at").asString())
                .isEqualTo(f.clock.instant().toString());
        assertThat(json(response).path("refund").path("cancel_reason").asString())
                .isEqualTo("customer request");
        assertThat(json(response).path("history").get(1).path("to_status").asString())
                .isEqualTo("CANCELLED");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_2_12_reservedInProgressStateCannotBeTreatedAsCompleted() throws Exception {
        f.s1();
        String fingerprint = RefundJson.fingerprint(f.mapper, "/api/refunds", f.mapper.readTree(request(2000, "MR-1")));
        f.jdbc.sql("""
                INSERT INTO io.idempotency_record(client_id, idem_key, request_fingerprint, state,
                    locked_until, created_at, expires_at, recorded_at)
                VALUES ('demo-merchant', 'K1', :fingerprint, 'IN_PROGRESS', :now, :now, :now, :now)
                """)
                .param("fingerprint", fingerprint)
                .param("now", f.clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .update();
        var before = snapshot();
        error(accept("K1", 2000, "MR-1"), 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_2_12_configurationRejectsUnboundedTimeoutAndInvalidCleanupBatch() {
        for (Duration value : List.of(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1), Duration.ofDays(100)))
            assertThatThrownBy(() -> properties.setIdempotencyLockTimeout(value))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setIdempotencyCleanupBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setClientId(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_2_14_cleanupWaitsForInFlightReplayWithoutDeletingItsResult() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        f.clock.set(f.clock.instant().plus(Duration.ofHours(25)));
        var before = snapshot();
        var completed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cleaning = new CountDownLatch(1);
        var heldMvc = controller(
                f.refunds,
                () -> {
                    completed.countDown();
                    await(release);
                },
                () -> {});
        try (var pool = Executors.newFixedThreadPool(2)) {
            var replay = pool.submit(() -> postTo(heldMvc, "/api/refunds", "K9", request(2000, "MR-1")));
            try {
                assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
                var cleanup = pool.submit(() -> {
                    cleaning.countDown();
                    return cleaner().cleanBatch();
                });
                assertThat(cleaning.await(10, TimeUnit.SECONDS)).isTrue();
                // The expired original remains visible until the replay transaction releases the funds lock.
                assertThat(f.count("io.idempotency_record")).isEqualTo(1);
                release.countDown();
                assertThat(replay.get(10, TimeUnit.SECONDS).getContentAsString())
                        .isEqualTo(first.getContentAsString());
                assertThat(cleanup.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                release.countDown();
            }
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(f.number("SELECT count(*) FROM io.idempotency_record WHERE idem_key='K9' AND state='COMPLETED'"))
                .isEqualTo(1);
    }

    @Test
    void ac_4_8_cancellationBeforeCommitFailureRestoresOriginalStateAndQuota() throws Exception {
        f.s1();
        var first = accept("K1", 2000, "MR-1");
        var before = snapshot();
        String path = "/api/refunds/" + refundId(first) + "/cancel";
        var failed = controller(
                f.refunds,
                () -> {
                    throw new IllegalStateException("cancel before commit");
                },
                () -> {});
        assertThatThrownBy(() -> postTo(failed, path, "C1", CANCEL)).hasRootCauseMessage("cancel before commit");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(f.status(refundId(first))).isEqualTo("AWAITING_FUNDS");
        assertThat(postTo(mvc, path, "C1", CANCEL).getStatus()).isEqualTo(200);
        assertThat(snapshot())
                .isEqualTo(new Snapshot(
                        1, before.postings() + 2, 0, 2, before.entries() + 1, before.history() + 1, 0, 0, 0));
        f.invariants();
    }

    @Test
    void ac_4_8_nonLockDatabaseFailureIsNotMisreportedAsInProgress() {
        f.s3();
        var before = snapshot();
        var failed = controller(
                f.refunds, () -> f.jdbc.sql("SELECT 1 / 0").query(Integer.class).single(), () -> {});
        Sql.assertSqlState(
                "22012",
                org.assertj.core.api.Assertions.catchThrowable(
                        () -> postTo(failed, "/api/refunds", "K1", request(2000, "MR-1"))));
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }
}
