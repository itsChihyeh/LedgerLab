package dev.ledgerlab.payouts.internal;

import static dev.ledgerlab.payouts.internal.BankHttpServer.await;
import static dev.ledgerlab.payouts.internal.BankHttpServer.payment;
import static dev.ledgerlab.payouts.internal.BankHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import dev.ledgerlab.refunds.RefundQueue;
import dev.ledgerlab.refunds.internal.RefundFixture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.shared.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
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
import org.springframework.test.web.servlet.MockMvc;

class PayoutOperatorQueryTest {
    private RefundFixture f;
    private BankHttpServer bank;
    private String id;
    private String originalPayment;
    private PayoutOperatorQueries queries;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        f = new RefundFixture();
        originalPayment = f.s3();
        f.clock.set(Instant.parse("2026-11-02T15:00:00Z"));
        bank = new BankHttpServer();
        id = new JdbcPayouts(f.jdbc, f.ledger, f.clock, f.ids, f.mapper, f.manager)
                .reserve(9680, "MP-QUERY", "query-fixture")
                .payoutId();
        queries = new PayoutOperatorQueries(f.jdbc, f.ledger, f.ids, f.clock, f.manager);
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new PayoutOperatorController(queries))
                .build();
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
                    """)).isZero();
        } finally {
            f.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "EXECUTED", "RETURNED"})
    void ac_7_23_queryOnlyAcceptsWithoutCashPostingAndCanBeQueriedAgain(String response) throws Exception {
        unknown();
        bank.payments.put(id, payment(id, response));
        long entries = f.count("ledger.journal_entry");
        String command = enqueue();
        assertThat(enqueue()).isEqualTo(command);
        assertThat(worker(true).processAvailable()).isEqualTo(1);
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        held();
        assertThat(enqueue()).isNotEqualTo(command);
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='QUERY_PAYOUT'"))
                .isEqualTo(2);
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("GET");
        assertThat(bank.requests.stream().map(BankHttpServer.Request::id)).containsOnly(id);
        assertThat(f.number("""
                SELECT count(*) FROM io.outbound_attempt a JOIN io.outbound_command c ON c.id=a.command_id
                WHERE c.kind='QUERY_PAYOUT' AND a.request_summary->>'operation'='QUERY'
                """)).isEqualTo(1);
    }

    @Test
    void ac_7_23_submittedIsQueryable() throws Exception {
        f.jdbc.sql("UPDATE core.payout SET status='SUBMITTED' WHERE id=:id")
                .param("id", id)
                .update();
        bank.payments.put(id, payment(id, "ACCEPTED"));
        enqueue();
        worker(true).processAvailable();
        assertThat(status()).isEqualTo("ACCEPTED");
        held();
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("GET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "PAID", "FAILED", "RETURNED"})
    void ac_7_23_nonQueryableStatesReturn409WithoutWriting(String state) throws Exception {
        if (state.equals("FAILED")) {
            bank.handler = e -> respond(e, 201, payment(id, "REJECTED"));
            worker(false).processAvailable();
        } else if (state.equals("PAID") || state.equals("RETURNED")) {
            statement(false);
            if (state.equals("RETURNED")) statement(true);
        }
        long commands = f.count("io.outbound_command");
        long histories = f.count("core.payout_status_history");
        var response =
                mvc.perform(post("/api/payouts/{id}/query", id)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(f.mapper.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo("PAYOUT_NOT_QUERYABLE");
        assertThat(f.count("io.outbound_command")).isEqualTo(commands);
        assertThat(f.count("core.payout_status_history")).isEqualTo(histories);
    }

    @Test
    void ac_7_23_missingPayoutReturns404WithoutWriting() throws Exception {
        var response =
                mvc.perform(post("/api/payouts/mpo_missing/query")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(f.mapper.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo("PAYOUT_NOT_FOUND");
        assertThat(f.count("io.outbound_command")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_7_9_ac_7_23_rejectionReleasesFundsAndWaitingRefunds(boolean waitingRefund) throws Exception {
        unknown();
        String refund = waitingRefund ? f.accept(originalPayment, 800).refundId() : "";
        bank.payments.put(id, payment(id, "REJECTED"));
        String command = enqueue();
        worker(true).processAvailable();
        assertThat(status()).isEqualTo("FAILED");
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(f.balance(2104)).isZero();
        assertThat(f.balance(2102)).isEqualTo(waitingRefund ? 8880 : 9680);
        assertThat(f.balance(1103)).isEqualTo(9680);
        assertThat(f.number("""
                SELECT count(*) FROM ledger.journal_entry
                WHERE entry_type='PAYOUT_FAILED' AND reverses_entry_id IS NULL
                """)).isEqualTo(1);
        if (waitingRefund) {
            assertThat(f.status(refund)).isEqualTo("READY");
            assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND'"))
                    .isEqualTo(1);
        }
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("GET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UNKNOWN", "IN_FLIGHT"})
    void ac_7_23_notFoundSchedulesUnfinishedSubmitWithoutSendingOrChangingItsLease(String submitState)
            throws Exception {
        unknown();
        f.jdbc.sql("""
                UPDATE io.outbound_command SET status=:state, lease_epoch=7,
                    lease_owner='submit-worker', lease_until=:until WHERE kind='SUBMIT_PAYOUT'
                """)
                .param("state", submitState)
                .param("until", f.clock.instant().plusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        var before = submitSnapshot();
        String command = enqueue();
        worker(true).processAvailable();
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(submitSnapshot()).isEqualTo(before);
        assertThat(f.jdbc.sql("SELECT next_attempt_at FROM io.outbound_command WHERE kind='SUBMIT_PAYOUT'")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(f.clock.instant());
        assertThat(status()).isEqualTo("UNKNOWN");
        held();
        assertThat(f.count("recon.issue")).isZero();
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("GET");
        if (submitState.equals("UNKNOWN")) {
            worker(false).processAvailable();
            assertThat(bank.requests.stream().map(BankHttpServer.Request::method))
                    .containsExactly("GET", "GET", "POST");
            assertThat(status()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void ac_7_23_notFoundAfterDoneOpensConflictWithoutRescheduling() throws Exception {
        worker(false).processAvailable();
        bank.requests.clear();
        bank.payments.clear();
        var before = f.jdbc.sql("SELECT * FROM io.outbound_command").query().singleRow();
        String command = enqueue();
        worker(true).processAvailable();
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(f.jdbc.sql("SELECT * FROM io.outbound_command WHERE kind='SUBMIT_PAYOUT'")
                        .query()
                        .singleRow())
                .isEqualTo(before);
        assertThat(status()).isEqualTo("ACCEPTED");
        held();
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT'"))
                .isEqualTo(1);
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("GET");
    }

    @Test
    void ac_7_23_unavailableQueryIsReusedAndRetriesOnlyGet() throws Exception {
        unknown();
        bank.handler = e -> respond(e, 503, "{\"code\":\"UNAVAILABLE\"}");
        String command = enqueue();
        worker(true).processAvailable();
        assertThat(commandStatus(command)).isEqualTo("UNKNOWN");
        assertThat(enqueue()).isEqualTo(command);
        bank.payments.put(id, payment(id, "ACCEPTED"));
        bank.handler = bank::normal;
        f.clock.set(f.clock.instant().plusSeconds(10));
        worker(true).processAvailable();
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(status()).isEqualTo("ACCEPTED");
        held();
        assertThat(bank.requests.stream().map(BankHttpServer.Request::method)).containsExactly("GET", "GET");
    }

    @Test
    void ac_7_23_concurrentOperatorsShareOneUnfinishedCommand() throws Exception {
        unknown();
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return enqueue();
            });
            var second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return enqueue();
            });
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='QUERY_PAYOUT'"))
                .isEqualTo(1);
    }

    @Test
    void ac_7_23_lateNotFoundCannotRescheduleSubmitAfterLeaseTakeover() throws Exception {
        unknown();
        String command = enqueue();
        var reached = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var old = worker(true, f.queue, new OutboundHooks() {
            @Override
            public void beforeWriteback(CommandSnapshot ignored) {
                reached.countDown();
                await(resume);
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(old::processAvailable);
            try {
                await(reached);
                assertThat(enqueue()).isEqualTo(command);
                f.clock.set(f.clock.instant().plusSeconds(61));
                bank.payments.put(id, payment(id, "ACCEPTED"));
                worker(true).processAvailable();
            } finally {
                resume.countDown();
            }
            future.get(15, TimeUnit.SECONDS);
        }
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(
                        f.number(
                                "SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_PAYOUT' AND next_attempt_at='infinity'"))
                .isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM io.outbound_attempt WHERE command_id='" + command + "'"))
                .isEqualTo(2);
        assertThat(f.count("recon.issue")).isZero();
        held();
    }

    @Test
    void ac_7_23_paidEvidenceWinsOverQueryRejection() throws Exception {
        unknown();
        bank.payments.put(id, payment(id, "REJECTED"));
        String command = enqueue();
        worker(true, f.queue, new OutboundHooks() {
                    @Override
                    public void beforeWriteback(CommandSnapshot ignored) {
                        statement(false);
                    }
                })
                .processAvailable();
        assertThat(status()).isEqualTo("PAID");
        assertThat(commandStatus(command)).isEqualTo("DONE");
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT'"))
                .isEqualTo(1);
        assertThat(f.balance(2102)).isZero();
    }

    @Test
    void ac_7_9_ac_7_23_queueFailureRollsBackPayoutAndCommandWriteback() throws Exception {
        unknown();
        bank.payments.put(id, payment(id, "REJECTED"));
        String command = enqueue();
        long histories = f.count("core.payout_status_history");
        assertThatThrownBy(() -> worker(
                                true,
                                () -> {
                                    throw new IllegalStateException("queue failure");
                                },
                                new OutboundHooks() {})
                        .processAvailable())
                .hasMessage("queue failure");
        assertThat(status()).isEqualTo("UNKNOWN");
        assertThat(commandStatus(command)).isEqualTo("IN_FLIGHT");
        assertThat(f.count("core.payout_status_history")).isEqualTo(histories);
        held();
    }

    private void unknown() {
        bank.handler = e -> respond(e, 503, "{\"code\":\"UNAVAILABLE\"}");
        worker(false).processAvailable();
        assertThat(status()).isEqualTo("UNKNOWN");
        f.jdbc.sql("UPDATE io.outbound_command SET next_attempt_at='infinity' WHERE kind='SUBMIT_PAYOUT'")
                .update();
        bank.requests.clear();
        bank.handler = bank::normal;
    }

    private String enqueue() throws Exception {
        var response =
                mvc.perform(post("/api/payouts/{id}/query", id)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(202);
        return f.mapper
                .readTree(response.getContentAsString())
                .path("commandId")
                .asString();
    }

    private String status() {
        return f.jdbc.sql("SELECT status FROM core.payout WHERE id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    private String commandStatus(String command) {
        return f.jdbc.sql("SELECT status FROM io.outbound_command WHERE id=:id")
                .param("id", command)
                .query(String.class)
                .single();
    }

    private java.util.Map<String, @Nullable Object> submitSnapshot() {
        return f.jdbc.sql("""
                SELECT status,attempts,lease_epoch,lease_owner,lease_until,first_sent_at,last_outcome
                FROM io.outbound_command WHERE kind='SUBMIT_PAYOUT'
                """).query().singleRow();
    }

    private void held() {
        assertThat(f.balance(1103)).isEqualTo(9680);
        assertThat(f.balance(2104)).isEqualTo(9680);
        assertThat(f.balance(2102)).isZero();
        assertThat(
                        f.number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE entry_type IN ('PAYOUT_PAID','PAYOUT_FAILED')"))
                .isZero();
    }

    private OutboundWorker worker(boolean queryOnly) {
        return worker(queryOnly, f.queue, new OutboundHooks() {});
    }

    private OutboundWorker worker(boolean queryOnly, RefundQueue queue, OutboundHooks hooks) {
        var evidence = new PayoutCommandEvidence(f.jdbc, f.ledger, queue, f.issues, f.mapper, f.clock);
        var handler = new PayoutCommandHandler(f.jdbc, bank.gateway(f.clock), evidence, f.clock, queryOnly);
        var properties = new OutboundProperties(
                "query-test",
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                10,
                Duration.ofHours(20));
        return new OutboundWorker(
                f.jdbc, f.manager, List.of(handler), f.clock, properties, new Random(6), hooks, f.mapper);
    }

    private void statement(boolean returned) {
        var statements = new BankStatements(
                f.jdbc,
                f.ledger,
                f.issues,
                f.clock,
                f.ids,
                new BusinessCalendar(),
                f.mapper,
                f.manager,
                2,
                new JdbcPayoutBankEvidence(f.jdbc, f.ledger, f.queue, new BusinessCalendar(), f.clock));
        String date = returned ? "2026-11-04" : "2026-11-03";
        String csv = "H,BANK_STATEMENT,SIM-CAD-001," + date + "," + date + ",1,CAD\r\n"
                + "D,bt_" + id + (returned ? "_R,CREDIT" : "_1,DEBIT") + ",9680,CAD,BOOKED," + id
                + "," + date + "," + date + "," + (returned ? "RETURN ACCOUNT_CLOSED" : "Payout " + id)
                + "\r\nT,1," + (returned ? "9680,0,0,9680" : "0,9680,9680,0") + "\r\n";
        assertThat(statements
                        .importBankStatement("query-" + date + ".csv", csv.getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo("ACCEPTED");
    }
}
