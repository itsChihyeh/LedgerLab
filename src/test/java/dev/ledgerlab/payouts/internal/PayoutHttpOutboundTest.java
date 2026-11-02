package dev.ledgerlab.payouts.internal;

import static dev.ledgerlab.payouts.internal.BankHttpServer.await;
import static dev.ledgerlab.payouts.internal.BankHttpServer.payment;
import static dev.ledgerlab.payouts.internal.BankHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import dev.ledgerlab.payouts.PayoutException;
import dev.ledgerlab.refunds.RefundQueue;
import dev.ledgerlab.refunds.internal.RefundFixture;
import dev.ledgerlab.testsupport.Sql;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PayoutHttpOutboundTest {
    private RefundFixture f;
    private BankHttpServer bank;
    private JdbcPayouts payouts;
    private PayoutCommandHandler handler;
    private String id;
    private String originalPayment;
    private OutboundProperties properties;

    @BeforeEach
    void setup() throws Exception {
        f = new RefundFixture();
        originalPayment = f.s3();
        f.clock.set(Instant.parse("2026-11-02T15:00:00Z"));
        bank = new BankHttpServer();
        payouts = new JdbcPayouts(f.jdbc, f.ledger, f.clock, f.ids, f.mapper, f.manager);
        id = payouts.reserve(9680, "MP-1", "test-fingerprint").payoutId();
        handler = handler(f.queue);
        properties = new OutboundProperties(
                "bank-test",
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                10,
                Duration.ofHours(20));
    }

    private PayoutCommandHandler handler(RefundQueue queue) {
        return new PayoutCommandHandler(f.jdbc, bank.gateway(f.clock), evidence(queue), f.clock);
    }

    private PayoutCommandEvidence evidence(RefundQueue queue) {
        return new PayoutCommandEvidence(f.jdbc, f.ledger, queue, f.issues, f.mapper, f.clock);
    }

    @AfterEach
    void cleanup() {
        bank.close();
        try {
            f.invariants();
            assertThat(f.number("""
                    SELECT balance - (SELECT coalesce(sum(amount),0) FROM core.payout
                        WHERE status IN ('RESERVED','SUBMITTED','UNKNOWN','ACCEPTED'))
                    FROM ledger.account WHERE code=2104
                    """)).as("INV-9 payout in transit").isZero();
            assertThat(f.number("""
                    SELECT count(*) FROM (SELECT subject_id FROM ledger.journal_entry WHERE subject_type='payout'
                    GROUP BY subject_id HAVING count(*) FILTER (WHERE entry_type='PAYOUT_RESERVED')>1
                        OR count(*) FILTER (WHERE entry_type IN ('PAYOUT_FAILED','PAYOUT_PAID'))>1) duplicates
                    """)).as("INV-10").isZero();
        } finally {
            f.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "EXECUTED"})
    void ac_7_1_apiReceiptAcceptsWithoutMovingCash(String status) {
        Instant received = f.clock.instant().plusSeconds(5);
        bank.handler = e -> {
            f.clock.set(received);
            respond(e, 201, payment(id, status));
        };
        worker(new OutboundHooks() {
                    @Override
                    public void beforeWriteback(CommandSnapshot command) {
                        advance(Duration.ofSeconds(10));
                    }
                })
                .processAvailable();
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(commandStatus()).isEqualTo("DONE");
        held();
        assertThat(f.count("io.outbound_attempt")).isEqualTo(1);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM core.payout WHERE bank_ack_ref='bpm_only' AND bank_execution_date='2026-11-03'"))
                .isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT accepted_at FROM core.payout")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(received);
        assertThat(f.jdbc.sql("SELECT occurred_at FROM core.payout_status_history WHERE to_status='ACCEPTED'")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(received);
        assertThat(bank.mapper.readTree(bank.requests.getFirst().body()))
                .isEqualTo(bank.mapper.readTree("{\"requestId\":\"" + id
                        + "\",\"amount\":9680,\"currency\":\"CAD\",\"destination\":\"MERCHANT-001\"}"));
        assertThat(worker().processAvailable()).isZero();
    }

    @Test
    void ac_7_6_realReadTimeoutNeverReleasesFunds() {
        bank.handler = bank::stall;
        worker().processAvailable();
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(commandStatus()).isEqualTo("UNKNOWN");
        held();
        assertThat(bank.payments).isEmpty();
        assertThat(
                        f.number(
                                "SELECT count(*) FROM io.outbound_attempt WHERE error='TRANSPORT_ERROR' AND response_status IS NULL"))
                .isEqualTo(1);
        assertThatThrownBy(() -> payouts.reserve(100, "MP-2", "second"))
                .isInstanceOf(PayoutException.class)
                .hasMessageContaining("INSUFFICIENT_FUNDS");
        assertThat(f.count("core.payout")).isEqualTo(1);
    }

    @Test
    void ac_7_7_tenUnavailableCallsAndFiveBusinessDaysNeverFailOrRelease() throws Exception {
        bank.handler = e -> respond(e, 503, "{\"code\":\"UNAVAILABLE\"}");
        for (int i = 0; i < 10; i++) {
            worker().processAvailable();
            assertThat(status()).isEqualTo("UNKNOWN");
            held();
            advance(Duration.ofMinutes(1));
        }
        // Monday Nov 2 to Monday Nov 9: five bank business days, no holidays.
        f.clock.set(Instant.parse("2026-11-09T15:00:00Z"));
        assertThat(worker().processAvailable()).isZero();
        assertThat(status()).isEqualTo("UNKNOWN");
        held();
        assertThat(bank.requests).hasSize(10);
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method))
                .containsExactly("POST", "GET", "GET", "GET", "GET", "GET", "GET", "GET", "GET", "GET");
        assertThat(f.number("SELECT count(*) FROM core.payout_status_history WHERE to_status='UNKNOWN'"))
                .isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE next_attempt_at='infinity'"))
                .isEqualTo(1);
        assertThat(detail().path("commands").path(0).path("attempts").asInt()).isEqualTo(10);
    }

    @Test
    void ac_7_8_executeDropResponseRecoversOriginalPaymentByQuery() {
        bank.handler = e -> {
            bank.payments.putIfAbsent(id, payment(id, "EXECUTED"));
            e.sendResponseHeaders(201, 500);
            e.close();
        };
        worker().processAvailable();
        assertThat(status()).isEqualTo("UNKNOWN");
        held();
        var first = firstSent();
        advance(Duration.ofHours(25));
        bank.handler = bank::normal;
        worker().processAvailable();
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(bank.payments).hasSize(1);
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("POST", "GET");
        assertThat(firstSent()).isEqualTo(first);
        assertThat(f.count("io.outbound_attempt")).isEqualTo(2);
        held();
    }

    @Test
    void ac_7_9_rejectOnQueryReleasesAndRecalculatesRefundQueueAtomically() {
        String refund = f.accept(originalPayment, 800).refundId();
        assertThat(f.status(refund)).isEqualTo("AWAITING_FUNDS");
        bank.handler = e -> {
            bank.payments.put(id, payment(id, "ACCEPTED"));
            bank.stall(e);
        };
        worker().processAvailable();
        assertThat(status()).isEqualTo("UNKNOWN");
        bank.payments.put(id, payment(id, "REJECTED"));
        bank.handler = bank::normal;
        advance(Duration.ofMinutes(1));
        worker().processAvailable();
        failed(8880);
        assertThat(f.status(refund)).isEqualTo("READY");
        assertThat(f.balance(2103)).isEqualTo(800);
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND' AND status='PENDING'"))
                .isEqualTo(1);
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("POST", "GET");
    }

    @Test
    void ac_7_22_explicitFirstRejectionRestoresAvailableWithoutReversal() {
        bank.handler = e -> {
            bank.payments.put(id, payment(id, "REJECTED"));
            respond(e, 201, bank.payments.get(id));
        };
        worker().processAvailable();
        failed(9680);
        assertThat(bank.payments.values()).containsExactly(payment(id, "REJECTED"));
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_PAID'"))
                .isZero();
        assertThat(worker().processAvailable()).isZero();
    }

    @Test
    void ac_7_8_retry404ResubmitsSamePermanentIdAndArchivesBothOperations() {
        bank.handler = e -> respond(e, 503, "{\"code\":\"UNAVAILABLE\"}");
        worker().processAvailable();
        Instant first = firstSent();
        advance(Duration.ofHours(25));
        bank.handler = bank::normal;
        worker().processAvailable();
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("POST", "GET", "POST");
        assertThat(bank.requests.stream().map(BankHttpServer.Request::id)).containsOnly(id);
        assertThat(bank.payments).hasSize(1);
        assertThat(f.count("io.outbound_attempt")).isEqualTo(3);
        assertThat(firstSent()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_7_16_bothCrashPointsRecoverByQueryBeforeSend(boolean afterHttp) {
        var hooks = new OutboundHooks() {
            @Override
            public void afterClaim(CommandSnapshot command) {
                if (!afterHttp) throw new IllegalStateException("crash");
            }

            @Override
            public void beforeWriteback(CommandSnapshot command) {
                if (afterHttp) throw new IllegalStateException("crash");
            }
        };
        assertThatThrownBy(() -> worker(hooks).processAvailable()).hasMessage("crash");
        assertThat(status()).isEqualTo("RESERVED");
        assertThat(commandStatus()).isEqualTo("IN_FLIGHT");
        assertThat(bank.payments).hasSize(afterHttp ? 1 : 0);
        advance(Duration.ofSeconds(61));
        worker().processAvailable();
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(bank.payments).hasSize(1);
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method))
                .containsExactlyElementsOf(afterHttp ? List.of("POST", "GET") : List.of("GET", "POST"));
        assertThat(f.count("io.outbound_attempt")).isEqualTo(2);
    }

    @Test
    void ac_7_16_oldLeaseLateRejectionCannotUndoAcceptedOrReleaseQueuedRefund() throws Exception {
        String refund = f.accept(originalPayment, 800).refundId();
        var received = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        bank.handler = e -> {
            received.countDown();
            await(resume);
            respond(e, 201, payment(id, "REJECTED"));
        };
        var oldHandler = new PayoutCommandHandler(
                f.jdbc, bank.gateway(f.clock, Duration.ofSeconds(20)), evidence(f.queue), f.clock);
        var old = new OutboundWorker(
                f.jdbc,
                f.manager,
                List.of(oldHandler),
                f.clock,
                properties,
                new Random(7),
                new OutboundHooks() {},
                f.mapper);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(old::processAvailable);
            try {
                await(received);
                advance(Duration.ofSeconds(61));
                bank.payments.put(id, payment(id, "ACCEPTED"));
                bank.handler = bank::normal;
                worker().processAvailable();
                assertThat(status()).isEqualTo("ACCEPTED");
            } finally {
                resume.countDown();
            }
            result.get(10, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(f.status(refund)).isEqualTo("AWAITING_FUNDS");
        assertThat(f.balance(2102)).isEqualTo(-800);
        assertThat(f.balance(2104)).isEqualTo(9680);
        assertThat(f.count("io.outbound_attempt")).isEqualTo(2);
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM core.payout_status_history WHERE to_status='FAILED'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND'"))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "EXECUTED", "REJECTED", "UNKNOWN"})
    void ac_7_1_ac_7_22_paidDuringHttpIsNeverOverwrittenOrReleased(String reply) {
        bank.handler = e -> respond(
                e,
                reply.equals("UNKNOWN") ? 503 : 201,
                reply.equals("UNKNOWN") ? "{\"code\":\"UNAVAILABLE\"}" : payment(id, reply));
        worker(new OutboundHooks() {
                    @Override
                    public void beforeWriteback(CommandSnapshot command) {
                        booked();
                    }
                })
                .processAvailable();
        assertThat(status()).isEqualTo("PAID");
        assertThat(f.balance(1103)).isZero();
        assertThat(f.balance(2104)).isZero();
        assertThat(f.balance(2102)).isZero();
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT'"))
                .isEqualTo(reply.equals("REJECTED") ? 1 : 0);
        assertThat(f.count("io.outbound_attempt")).isEqualTo(1);
    }

    @Test
    void ac_7_22_requestConflictCompletesCommandAndOpensIssueWithoutChangingPayout() {
        bank.handler = e -> respond(e, 409, "{\"code\":\"REQUEST_CONFLICT\"}");
        worker().processAvailable();
        assertThat(status()).isEqualTo("RESERVED");
        assertThat(commandStatus()).isEqualTo("DONE");
        held();
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT' AND subject_id='"
                        + id + "'"))
                .isEqualTo(1);
    }

    @Test
    void ac_7_9_failureWritebackRollsBackPayoutLedgerHistoryAndQueueTogether() {
        String refund = f.accept(originalPayment, 800).refundId();
        bank.handler = e -> respond(e, 201, payment(id, "REJECTED"));
        handler = handler(() -> {
            f.queue.release();
            throw new IllegalStateException("queue crash");
        });
        assertThatThrownBy(() -> worker().processAvailable()).hasMessage("queue crash");
        assertThat(status()).isEqualTo("RESERVED");
        assertThat(commandStatus()).isEqualTo("IN_FLIGHT");
        assertThat(f.balance(2104)).isEqualTo(9680);
        assertThat(f.balance(2102)).isEqualTo(-800);
        assertThat(f.status(refund)).isEqualTo("AWAITING_FUNDS");
        assertThat(f.number("SELECT count(*) FROM core.payout_status_history WHERE to_status='FAILED'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND'"))
                .isZero();
        assertThat(f.count("io.outbound_attempt")).isEqualTo(1);
        handler = handler(f.queue);
        bank.payments.put(id, payment(id, "REJECTED"));
        bank.handler = bank::normal;
        advance(Duration.ofSeconds(61));
        worker().processAvailable();
        failed(8880);
        assertThat(f.status(refund)).isEqualTo("READY");
    }

    @Test
    void ac_7_1_detailReadsOnlyRedactedViewsAndDatabaseRejectsPrivilegeEscalation() throws Exception {
        bank.handler = e -> respond(e, 201, payment(id, "ACCEPTED").replace("}", ",\"private\":\"secret-body\"}"));
        worker().processAvailable();
        var json = detail();
        assertThat(json.path("commands").size()).isEqualTo(1);
        assertThat(json.path("commands").path(0).path("lastOutcome").asString()).isEqualTo("PAYOUT_ACCEPTED");
        assertThat(json.path("attempts").path(0).path("resultCode").asString()).isEqualTo("HTTP_201");
        assertThat(json.toString()).doesNotContain("secret-body", "responseBody", "requestSummary", "leaseOwner");
        assertThat(Sql.queryLong(
                        f.db.migrator(),
                        "SELECT count(*) FROM flyway.flyway_schema_history WHERE version='22' AND success"))
                .isEqualTo(1);
        assertThat(
                        Sql.queryLong(
                                f.db.migrator(),
                                "SELECT count(*) FROM pg_views WHERE viewowner='migrator' AND viewname IN ('v_payout_command_summary','v_payout_attempt_summary')"))
                .isEqualTo(2);
        for (String view : List.of("core.v_payout_command_summary", "core.v_payout_attempt_summary")) {
            assertThat(Sql.queryLong(f.db.readonly(), "SELECT count(*) FROM " + view))
                    .isEqualTo(1);
            Sql.assertRejected("42501", f.db.app(), "SELECT * FROM " + view);
            // PostgreSQL rejects a join view as non-updatable before checking UPDATE privileges.
            Sql.assertRejected(
                    view.endsWith("attempt_summary") ? "55000" : "42501",
                    f.db.readonly(),
                    "UPDATE " + view + " SET command_id='hidden'");
            assertThat(Sql.queryLong(
                            f.db.readonly(), "SELECT has_table_privilege(current_user, '" + view + "', 'UPDATE')::int"))
                    .isZero();
        }
        Sql.assertRejected("42501", f.db.readonly(), "SELECT * FROM io.outbound_command");
        Sql.assertRejected("42501", f.db.readonly(), "SELECT * FROM io.outbound_attempt");
    }

    @Test
    void ac_7_16_missingGatewayRegistersNoHandlerAndLeavesCommandPending() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("jdbc", f.jdbc);
            context.getBeanFactory().registerSingleton("clock", f.clock);
            context.getBeanFactory().registerSingleton("evidence", evidence(f.queue));
            context.register(PayoutCommandConfiguration.class);
            context.refresh();
            var handlers = context.getBeansOfType(CommandHandler.class).values().stream()
                    .toList();
            assertThat(handlers).isEmpty();
            assertThat(new OutboundWorker(
                                    f.jdbc,
                                    f.manager,
                                    handlers,
                                    f.clock,
                                    properties,
                                    new Random(7),
                                    new OutboundHooks() {},
                                    f.mapper)
                            .processAvailable())
                    .isZero();
        }
        assertThat(commandStatus()).isEqualTo("PENDING");
        assertThat(bank.requests).isEmpty();
    }

    private tools.jackson.databind.JsonNode detail() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                        new PayoutQueryController(new PayoutQueries(f.db.readonly(), f.clock)))
                .build();
        var response = mvc.perform(get("/api/payouts/" + id)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return f.mapper.readTree(response.getContentAsString());
    }

    /** Ledger-backed T05 boundary fixture: commits booked bank evidence during the HTTP/writeback gap. */
    private void booked() {
        f.tx.executeWithoutResult(s -> {
            f.ledger.lockFunds();
            f.jdbc.sql(
                            "UPDATE core.payout SET status='PAID', bank_txn_id='bt_fixture', paid_at=:now, updated_at=:now WHERE id=:id")
                    .param("id", id)
                    .param("now", f.clock.instant().atOffset(ZoneOffset.UTC))
                    .update();
            f.ledger.post(new EntryRequest(
                    EntryType.PAYOUT_PAID,
                    "bank_txn:bt_fixture",
                    f.clock.instant(),
                    SubjectType.PAYOUT,
                    id,
                    List.of(
                            PostingLine.debit(Account.PAYOUT_IN_TRANSIT, 9680),
                            PostingLine.credit(Account.BANK_CASH, 9680))));
            f.jdbc.sql("""
                    INSERT INTO core.payout_status_history(payout_id,from_status,to_status,evidence_type,evidence_id,occurred_at,recorded_at)
                    VALUES (:id,'RESERVED','PAID','bank_txn','bt_fixture',:now,:now)
                    """)
                    .param("id", id)
                    .param("now", f.clock.instant().atOffset(ZoneOffset.UTC))
                    .update();
        });
    }

    private void held() {
        assertThat(f.balance(1103)).isEqualTo(9680);
        assertThat(f.balance(2102)).isZero();
        assertThat(f.balance(2104)).isEqualTo(9680);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE entry_type IN ('PAYOUT_FAILED','PAYOUT_PAID')"))
                .isZero();
    }

    private void failed(long available) {
        assertThat(status()).isEqualTo("FAILED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(f.balance(1103)).isEqualTo(9680);
        assertThat(f.balance(2102)).isEqualTo(available);
        assertThat(f.balance(2104)).isZero();
        assertThat(
                        f.number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED' AND reverses_entry_id IS NULL"))
                .isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM core.payout WHERE failure_reason='ACCOUNT_CLOSED'"))
                .isEqualTo(1);
    }

    private void advance(Duration duration) {
        f.clock.set(f.clock.instant().plus(duration));
    }

    private String status() {
        return f.jdbc.sql("SELECT status FROM core.payout").query(String.class).single();
    }

    private String commandStatus() {
        return f.jdbc.sql("SELECT status FROM io.outbound_command WHERE kind='SUBMIT_PAYOUT'")
                .query(String.class)
                .single();
    }

    private Instant firstSent() {
        return f.jdbc.sql("SELECT first_sent_at FROM io.outbound_command WHERE kind='SUBMIT_PAYOUT'")
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();
    }

    private OutboundWorker worker() {
        return worker(new OutboundHooks() {});
    }

    private OutboundWorker worker(OutboundHooks hooks) {
        return new OutboundWorker(
                f.jdbc, f.manager, List.of(handler), f.clock, properties, new Random(7), hooks, f.mapper);
    }
}
