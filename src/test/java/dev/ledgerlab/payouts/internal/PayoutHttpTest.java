package dev.ledgerlab.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class PayoutHttpTest {
    private TestDatabase.Handle db;
    private JdbcClient jdbc;
    private JdbcTransactionManager manager;
    private TransactionTemplate tx;
    private Ledger ledger;
    private JdbcPayouts payouts;
    private PayoutApiProperties properties;
    private MockMvc mvc;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T15:00:00Z"));
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        db = TestDatabase.freshMigrated();
        var ds = db.app();
        jdbc = JdbcClient.create(ds);
        manager = new JdbcTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        var ids = IdGenerator.seeded(clock, 303);
        ledger = new JdbcLedger(ds, clock, ids);
        payouts = new JdbcPayouts(jdbc, ledger, clock, ids, mapper, manager);
        properties = new PayoutApiProperties();
        mvc = controller(() -> {});
    }

    @AfterEach
    void cleanup() {
        db.drop();
    }

    private MockMvc controller(Runnable beforeCommit) {
        var api = new PayoutApi(jdbc, ledger, payouts, clock, mapper, manager, properties, beforeCommit);
        return MockMvcBuilders.standaloneSetup(
                        new PayoutController(api, mapper),
                        new PayoutQueryController(new PayoutQueries(db.readonly(), clock)))
                .build();
    }

    private void seed(long cash, long available, long receivable, long unapplied, long inTransit) {
        // Ledger-only fixture; all balances are derived from sealed postings, never SQL balance updates.
        var lines = new java.util.ArrayList<PostingLine>();
        if (cash > 0) lines.add(PostingLine.debit(Account.BANK_CASH, cash));
        if (inTransit > 0) lines.add(PostingLine.debit(Account.SETTLEMENT_IN_TRANSIT, inTransit));
        if (receivable < 0) lines.add(PostingLine.credit(Account.PROCESSOR_RECEIVABLE, -receivable));
        if (receivable > 0) lines.add(PostingLine.debit(Account.PROCESSOR_RECEIVABLE, receivable));
        lines.add(PostingLine.credit(Account.MERCHANT_AVAILABLE, available));
        if (unapplied > 0) lines.add(PostingLine.credit(Account.UNAPPLIED_CASH, unapplied));
        long difference = Math.subtractExact(
                Math.addExact(Math.addExact(cash, inTransit), receivable), Math.addExact(available, unapplied));
        assertThat(difference).as("balanced opening fixture").isZero();
        tx.executeWithoutResult(s -> {
            ledger.lockFunds();
            ledger.post(new EntryRequest(
                    EntryType.PAYMENT_CAPTURED,
                    "fixture:opening",
                    clock.instant(),
                    SubjectType.PAYMENT,
                    "pay_fixture",
                    lines));
        });
    }

    private void s3() {
        seed(9680, 9680, 0, 0, 0);
    }

    private MockHttpServletResponse accept(MockMvc target, @Nullable String key, String body) throws Exception {
        var request =
                post("/api/payouts").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return target.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse accept(String key, long amount, String ref) throws Exception {
        return accept(mvc, key, "{\"amount\":" + amount + ",\"merchantPayoutRef\":\"" + ref + "\"}");
    }

    private long number(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long balance(int code) {
        return number("SELECT balance FROM ledger.account WHERE code=" + code);
    }

    private List<Long> snapshot() {
        return List.of(
                number("SELECT count(*) FROM core.payout"),
                number("SELECT count(*) FROM core.payout_status_history"),
                number("SELECT count(*) FROM ledger.journal_entry"),
                number("SELECT count(*) FROM ledger.posting"),
                number("SELECT count(*) FROM io.outbound_command"),
                number("SELECT count(*) FROM io.idempotency_record"),
                balance(1101),
                balance(1103),
                balance(2102),
                balance(2104),
                balance(2105));
    }

    private void error(MockHttpServletResponse response, int status, String code, @Nullable String reason)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        var json = mapper.readTree(response.getContentAsString());
        assertThat(json.path("code").asString()).isEqualTo(code);
        if (reason != null) assertThat(json.path("reason").asString()).isEqualTo(reason);
    }

    @Test
    void ac_7_1_reservesAtomicallyWithHistoryPostingAndPendingCommand() throws Exception {
        s3();
        var first = accept("K1", 9680, "MP-1");
        assertThat(first.getStatus()).isEqualTo(201);
        var body = mapper.readTree(first.getContentAsString());
        String id = body.path("payoutId").asString();
        assertThat(id).matches("mpo_[0-7][0-9A-HJKMNP-TV-Z]{25}");
        assertThat(body.path("bankRequestId").asString()).isEqualTo(id);
        assertThat(body.path("status").asString()).isEqualTo("RESERVED");
        assertThat(body.path("reservedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(balance(2102)).isZero();
        assertThat(balance(2104)).isEqualTo(9680);
        assertThat(balance(1103)).isEqualTo(9680);
        assertThat(jdbc.sql(
                                "SELECT kind || ':' || status || ':' || request_id || ':' || subject_id FROM io.outbound_command")
                        .query(String.class)
                        .single())
                .isEqualTo("SUBMIT_PAYOUT:PENDING:" + id + ":" + id);
        assertThat(jdbc.sql("SELECT evidence_type || ':' || to_status FROM core.payout_status_history")
                        .query(String.class)
                        .single())
                .isEqualTo("api:RESERVED");
        assertThat(
                        number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_RESERVED' AND reverses_entry_id IS NULL"))
                .isEqualTo(1);
        var before = snapshot();
        var replay = accept("K1", 9680, "MP-1");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot()).isEqualTo(before);
        var detail = mvc.perform(get("/api/payouts/" + id)).andReturn().getResponse();
        assertThat(detail.getStatus()).isEqualTo(200);
        var read = mapper.readTree(detail.getContentAsString());
        assertThat(read.path("payout").path("payoutId").asString()).isEqualTo(id);
        assertThat(read.path("history").size()).isEqualTo(1);
        assertThat(read.path("entries").size()).isEqualTo(1);
        assertThat(read.path("postings").size()).isEqualTo(2);
        assertThat(detail.getContentAsString()).doesNotContain("requestFingerprint", "initialResult");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_19_detailLinksOnlyThisPayoutIssuesThroughReadonlyView() throws Exception {
        s3();
        String id = mapper.readTree(accept("page-key", 100, "page-ref").getContentAsString())
                .path("payoutId")
                .asString();
        assertThat(new PayoutQueries(db.readonly(), clock).detail(id).issues()).isEmpty();
        var ids = IdGenerator.seeded(clock, 1010);
        String issue = ids.next("iss");
        for (String subject : List.of(id, "mpo_other")) {
            jdbc.sql("""
                    INSERT INTO recon.issue (id, category, subject_type, subject_id, detected_by,
                        status, evidence_refs, recorded_at, updated_at)
                    VALUES (?, 'PAYOUT_STATE_CONFLICT', 'payout', ?, 'INGESTION', 'OPEN', '[]', ?, ?)
                    """)
                    .params(
                            subject.equals(id) ? issue : ids.next("iss"),
                            subject,
                            clock.instant().atOffset(ZoneOffset.UTC),
                            clock.instant().atOffset(ZoneOffset.UTC))
                    .update();
        }
        var detail = mvc.perform(get("/api/payouts/" + id)).andReturn().getResponse();
        assertThat(detail.getStatus()).isEqualTo(200);
        var issues = mapper.readTree(detail.getContentAsString()).path("issues");
        assertThat(issues.size()).isEqualTo(1);
        assertThat(issues.get(0).path("id").asString()).isEqualTo(issue);
        assertThat(issues.get(0).path("category").asString()).isEqualTo("PAYOUT_STATE_CONFLICT");
        assertThat(issues.get(0).path("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void ac_7_2_bothGatesFailMerchantReasonWinsAndNoRowsRemain() throws Exception {
        s3();
        var before = snapshot();
        error(accept("K1", 9681, "MP-1"), 409, "INSUFFICIENT_FUNDS", "MERCHANT_BALANCE");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(accept("K1", 9680, "MP-1").getStatus()).isEqualTo(201);
    }

    @Test
    void ac_7_3_settlementInTransitIsNotFreeCash() throws Exception {
        seed(0, 9680, 0, 0, 9680);
        var before = snapshot();
        error(accept("K1", 100, "MP-1"), 409, "INSUFFICIENT_FUNDS", "FREE_CASH");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_4_negativeProcessorReceivableReservesCash() throws Exception {
        // CASH=3854, MA=3854, PR=-3000, SIT=3000 is a balanced ledger-level fixture.
        seed(3854, 3854, -3000, 0, 3000);
        var before = snapshot();
        error(accept("K1", 855, "MP-1"), 409, "INSUFFICIENT_FUNDS", "FREE_CASH");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(accept("K1", 854, "MP-1").getStatus()).isEqualTo(201);
    }

    @Test
    void ac_7_5_unappliedCashCannotBeWithdrawn() throws Exception {
        seed(19360, 9680, 0, 9680, 0);
        assertThat(accept("K1", 9680, "MP-1").getStatus()).isEqualTo(201);
        var before = snapshot();
        error(accept("K2", 1, "MP-2"), 409, "INSUFFICIENT_FUNDS", "MERCHANT_BALANCE");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_21_allCashUnappliedFailsFreeCashIndependently() throws Exception {
        seed(1000, 5000, 0, 1000, 5000);
        var before = snapshot();
        error(accept("K1", 1, "MP-1"), 409, "INSUFFICIENT_FUNDS", "FREE_CASH");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_15_barrierConcurrentPayoutsCannotSpendTheSameCash() throws Exception {
        s3();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return accept("K1", 6000, "MP-1");
            });
            var two = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return accept("K2", 6000, "MP-2");
            });
            var responses = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertThat(responses.stream().map(MockHttpServletResponse::getStatus))
                    .containsExactlyInAnyOrder(201, 409);
            error(
                    responses.stream()
                            .filter(r -> r.getStatus() == 409)
                            .findFirst()
                            .orElseThrow(),
                    409,
                    "INSUFFICIENT_FUNDS",
                    "MERCHANT_BALANCE");
        }
        assertThat(balance(2104)).isEqualTo(6000);
        assertThat(balance(2102)).isEqualTo(3680);
        assertThat(number("SELECT count(*) FROM core.payout")).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM io.idempotency_record")).isEqualTo(1);
    }

    @Test
    void ac_7_17_expiredCacheAndChangedStatusStillReturnPermanentInitialResult() throws Exception {
        s3();
        var first = accept("K1", 100, "MP-1");
        clock.set(clock.instant().plus(Duration.ofHours(25)));
        var later = clock.instant();
        jdbc.sql("DELETE FROM io.idempotency_record WHERE expires_at <= ?")
                .param(later.atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("UPDATE core.payout SET status='ACCEPTED'").update();
        var before = snapshot();
        var replay = accept("K9", 100, "MP-1");
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot().subList(0, 5)).isEqualTo(before.subList(0, 5));
        before = snapshot();
        error(accept("K10", 101, "MP-1"), 409, "PAYOUT_REF_CONFLICT", null);
        error(accept("K9", 101, "MP-1"), 422, "IDEMPOTENCY_KEY_REUSED", null);
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"100.5", "\"20.00\"", "0", "-1", "100000000", "null", "1e2", "9223372036854775808", "missing"})
    void ac_7_20_invalidAmountsLeaveNoRows(String amount) throws Exception {
        s3();
        var before = snapshot();
        String body = "{" + (amount.equals("missing") ? "" : "\"amount\":" + amount + ",")
                + "\"merchantPayoutRef\":\"MP-1\"}";
        error(accept(mvc, "K1", body), 400, "INVALID_AMOUNT", null);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_1_failureBeforeCommitRollsBackAllEffects() {
        s3();
        var before = snapshot();
        var failing = controller(() -> {
            throw new IllegalStateException("commit fault");
        });
        assertThatThrownBy(() -> accept(failing, "K1", "{\"amount\":100,\"merchantPayoutRef\":\"MP-1\"}"))
                .hasRootCauseMessage("commit fault");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_3_existingReservationReducesFreeCashIndependentlyOfMerchantBalance() throws Exception {
        seed(5000, 10000, 0, 0, 5000);
        assertThat(accept("K1", 4000, "MP-1").getStatus()).isEqualTo(201);
        var before = snapshot();
        error(accept("K2", 1001, "MP-2"), 409, "INSUFFICIENT_FUNDS", "FREE_CASH");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(accept("K2", 1000, "MP-2").getStatus()).isEqualTo(201);
    }

    @Test
    void ac_7_20_validAmountBoundariesAndVisibleAsciiReference() throws Exception {
        seed(99999999, 99999999, 0, 0, 0);
        assertThat(accept("K1", 99999999, "X".repeat(64)).getStatus()).isEqualTo(201);
        var before = snapshot();
        for (String ref : List.of("X".repeat(65), "MP 1", "提现", "\n")) {
            String body = mapper.writeValueAsString(java.util.Map.of("amount", 1, "merchantPayoutRef", ref));
            assertThat(accept(mvc, "K2", body).getStatus()).isEqualTo(400);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_17_canonicalRequestAndInvalidKeyOrJsonDoNotWrite() throws Exception {
        s3();
        var before = snapshot();
        error(
                accept(mvc, null, "{\"amount\":1,\"merchantPayoutRef\":\"MP-1\"}"),
                400,
                "IDEMPOTENCY_KEY_REQUIRED",
                null);
        for (String body : List.of(
                "{}",
                "[]",
                "null",
                "{",
                "{\"amount\":1,\"amount\":2,\"merchantPayoutRef\":\"MP-1\"}",
                "{\"amount\":1,\"merchantPayoutRef\":\"MP-1\",\"extra\":1}",
                "{\"amount\":1}",
                "{\"amount\":1,\"merchantPayoutRef\":3}",
                "{\"amount\":1,\"merchantPayoutRef\":\"MP-1\"} {}")) {
            assertThat(accept(mvc, "K1", body).getStatus()).isEqualTo(400);
        }
        assertThat(snapshot()).isEqualTo(before);
        var first = accept("K1", 1, "MP-1");
        assertThat(first.getStatus()).isEqualTo(201);
        before = snapshot();
        var replay = accept(mvc, "K1", " { \"merchantPayoutRef\" : \"MP-1\", \"amount\":1 }");
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_1_listPaginationUsesReservationOrderAndReadErrorsDoNotWrite() throws Exception {
        s3();
        accept("K1", 1, "MP-1");
        clock.set(clock.instant().plusSeconds(1));
        accept("K2", 1, "MP-2");
        var before = snapshot();
        var expected = jdbc.sql("SELECT id FROM core.payout ORDER BY reserved_at DESC, id DESC")
                .query(String.class)
                .list();
        for (int offset = 0; offset < 2; offset++) {
            var response = mvc.perform(get("/api/payouts?limit=1&offset=" + offset))
                    .andReturn()
                    .getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            var list = mapper.readTree(response.getContentAsString()).path("payouts");
            assertThat(list.size()).isEqualTo(1);
            assertThat(list.get(0).path("payoutId").asString()).isEqualTo(expected.get(offset));
        }
        for (String params : List.of("limit=0", "limit=101", "offset=-1", "limit=1.5", "offset=2147483648"))
            assertThat(mvc.perform(get("/api/payouts?" + params))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(400);
        assertThat(mvc.perform(get("/api/payouts/missing"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_7_17_sameKeyInFlightTimesOutThenReplays() throws Exception {
        s3();
        properties.setIdempotencyLockTimeout(Duration.ofMillis(100));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slow = controller(() -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> accept(slow, "K1", "{\"amount\":100,\"merchantPayoutRef\":\"MP-1\"}"));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                error(accept("K1", 100, "MP-1"), 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", null);
            } finally {
                release.countDown();
            }
            assertThat(accept("K1", 100, "MP-1").getContentAsString())
                    .isEqualTo(first.get(10, TimeUnit.SECONDS).getContentAsString());
        }
    }
}
