package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.ledger.internal.JdbcLedgerAudit;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.payments.PaymentSettlement;
import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Real app-role PostgreSQL plus a local HTTP processor; no database mocks. */
class SettlementWorkerTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final MutableClock clock = new MutableClock(NOW);
    private final AtomicInteger lineRequests = new AtomicInteger();
    private final AtomicInteger payoutRequests = new AtomicInteger();
    private final AtomicInteger postingsWritten = new AtomicInteger();
    private TestDatabase.Handle db;
    private JdbcClient jdbc;
    private JdbcTransactionManager manager;
    private TransactionTemplate tx;
    private Ledger ledger;
    private JdbcLedgerAudit audit;
    private PaymentCapture capture;
    private JdbcIngestionIssues issues;
    private PayoutProcessing processing;
    private InboxIntake intake;
    private SettlementPolling polling;
    private Supplier<SettlementPolling> pollers;
    private HttpServer server;
    private HttpClient http;
    private volatile String lines;
    private volatile String payout;
    private volatile boolean interrupted;
    private boolean failThird;

    @BeforeEach
    void start() throws IOException {
        db = TestDatabase.freshMigrated();
        DataSource real = db.app();
        DataSource source = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    return result instanceof Connection connection ? intercept(connection) : result;
                });
        jdbc = JdbcClient.create(source);
        manager = new JdbcTransactionManager(source);
        tx = new TransactionTemplate(manager);
        var ids = IdGenerator.seeded(clock, 505);
        ledger = new JdbcLedger(source, clock, ids);
        audit = new JdbcLedgerAudit(source, manager);
        issues = new JdbcIngestionIssues(jdbc, ids, clock);
        capture = new PaymentCapture(ledger, jdbc, issues, ids, clock, mapper);
        var batches = new BatchSettlement(jdbc, ledger, new PaymentSettlement(jdbc), issues, clock, mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lines = page(line("txn_payment", "charge", "ch_p1", 10000, 320), false);
        payout = payout("po_b1", "completed").toString();
        server.createContext("/v1/balance_transactions", exchange -> {
            lineRequests.incrementAndGet();
            boolean nextPage = exchange.getRequestURI().getRawQuery().contains("starting_after");
            byte[] body = (interrupted && !nextPage
                            ? page(line("txn_payment", "charge", "ch_p1", 10000, 320), true)
                            : lines)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(interrupted && nextPage ? 503 : 200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/v1/payouts/", exchange -> {
            payoutRequests.incrementAndGet();
            byte[] body = payout.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        http = HttpClient.newHttpClient();
        var client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(new JdkClientHttpRequestFactory(http))
                .requestInterceptor((request, body, execution) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return execution.execute(request, body);
                })
                .build();
        StripeReader reader = new RestStripeReader(
                client,
                new StripeProperties(StripeSignatures.SECRET, "sk_test_" + "t05_synthetic"),
                jdbc,
                manager,
                mapper,
                clock);
        processing = new PayoutProcessing(reader, batches, jdbc, mapper, clock);
        intake = new InboxIntake(jdbc, tx, issues, mapper, clock);
        pollers = () -> new SettlementPolling(
                reader,
                batches,
                processing,
                jdbc,
                clock,
                new SettlementPolling.Properties(Duration.ofMinutes(30), Duration.ofMinutes(1), Duration.ofMinutes(4)),
                manager);
        polling = pollers.get();
    }

    private Connection intercept(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (!(result instanceof PreparedStatement statement)
                            || !((String) args[0]).contains("INSERT INTO ledger.posting")) return result;
                    return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PreparedStatement.class},
                            (p, operation, arguments) -> {
                                Object value = invoke(statement, operation, arguments);
                                if (operation.getName().equals("executeUpdate")
                                        && failThird
                                        && postingsWritten.incrementAndGet() == 3) {
                                    try (var check = real.createStatement();
                                            var rows = check.executeQuery("SELECT count(*) FROM ledger.posting")) {
                                        assertThat(rows.next()).isTrue();
                                        assertThat(rows.getLong(1))
                                                .isEqualTo(5); // two capture + three batch postings really written
                                    }
                                    throw new IllegalStateException("Injected after third batch posting");
                                }
                                return value;
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @AfterEach
    void stop() {
        assertThat(audit.check()).isEmpty();
        server.stop(0);
        http.close();
        db.drop();
    }

    private InboxWorker worker(WorkerHooks hooks) {
        return new InboxWorker(
                jdbc,
                manager,
                capture,
                issues,
                mapper,
                new ChargeParser(mapper),
                clock,
                new WorkerProperties("t05", 1, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(5), 5),
                hooks,
                processing);
    }

    void runWorker() {
        worker(new WorkerHooks() {}).processAvailable();
    }

    void drain() {
        for (int i = 0; i < 32; i++) {
            if (worker(new WorkerHooks() {}).processAvailable() == 0) return;
        }
        throw new AssertionError("Settlement worker did not drain");
    }

    void assertLedger() {
        assertThat(audit.check()).isEmpty();
    }

    ObjectNode payout(String batch, String readiness) {
        return (ObjectNode) mapper.readTree("""
                {"id":"%s","amount":9680,"currency":"cad","automatic":true,"method":"standard",
                 "status":"pending","reconciliation_status":"%s","arrival_date":1792886400,"created":1792756800}
                """.formatted(batch, readiness));
    }

    void event(String id, String type, ObjectNode object) {
        event(id, type, object, false);
    }

    private void event(String id, String type, ObjectNode object, boolean live) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("id", id)
                .put("type", type)
                .put("api_version", "2026-09-30.endive")
                .put("livemode", live)
                .put("created", NOW.getEpochSecond());
        envelope.putObject("data").set("object", object);
        intake.receive(
                new IncomingEvent(id, type, "2026-09-30.endive", live, NOW),
                mapper.writeValueAsBytes(envelope),
                clock.instant());
    }

    void capture() {
        event("evt_capture", "charge.succeeded", (ObjectNode) mapper.readTree("""
                {"id":"ch_p1","payment_intent":"pi_p1","amount":10000,"amount_captured":10000,"currency":"cad",
                 "captured":true,"status":"succeeded","livemode":false,"created":1792756700}
                """));
        runWorker();
    }

    private void announce() {
        event("evt_created", "payout.created", payout("po_b1", "in_progress"));
        runWorker();
    }

    private void ready() {
        event("evt_ready", "payout.reconciliation_completed", payout("po_b1", "completed"));
        runWorker();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    String state() {
        return jdbc.sql("SELECT status FROM core.settlement_batch WHERE batch_id = 'po_b1'")
                .query(String.class)
                .single();
    }

    private Map<String, @Nullable Object> inbox(String id) {
        return jdbc.sql(
                        "SELECT status, result, attempts, parked_on, lease_epoch FROM io.inbox_event WHERE event_id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }

    List<Long> balances() {
        return jdbc.sql("SELECT balance FROM ledger.account ORDER BY code")
                .query((rs, row) -> rs.getLong(1))
                .list();
    }

    private static String line(String id, String type, String source, long gross, long fee) {
        return """
                {"id":"%s","type":"%s","source":"%s","amount":%d,"fee":%d,"net":%d,"currency":"cad","created":1792756700}
                """.formatted(id, type, source, gross, fee, Math.subtractExact(gross, fee));
    }

    private static String page(String items, boolean more) {
        return "{\"data\":[" + items + "],\"has_more\":" + more + "}";
    }

    @Test
    void ac_5_1_ac_1_4b_exampleASettlesExactlyFivePostings() {
        capture();
        assertThat(balances()).containsExactly(10000L, 0L, 0L, 10000L, 0L, 0L, 0L, 0L);
        announce();
        assertThat(state()).isEqualTo("ANNOUNCED");
        assertThat(lineRequests).hasValue(0);
        ready();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(balances()).containsExactly(0L, 9680L, 0L, 0L, 9680L, 0L, 0L, 0L);
        assertThat(jdbc.sql(
                                "SELECT posting_count, source_key, occurred_at FROM ledger.journal_entry WHERE entry_type = 'BATCH_SETTLED'")
                        .query()
                        .singleRow())
                .containsEntry("posting_count", 5)
                .containsEntry("source_key", "batch:po_b1:settled")
                .containsEntry("occurred_at", Timestamp.from(Instant.ofEpochSecond(1792756800)));
        assertThat(jdbc.sql("SELECT status, actual_fee, batch_id FROM core.payment")
                        .query()
                        .singleRow())
                .containsEntry("status", "SETTLED")
                .containsEntry("actual_fee", 320L)
                .containsEntry("batch_id", "po_b1");
        assertThat(count("core.settlement_batch_line")).isEqualTo(1);
        assertThat(count("core.payment_transition")).isEqualTo(2);
        assertThat(count("core.batch_transition")).isEqualTo(3);
    }

    @Test
    void ac_5_16_thirdPostingFailureRollsReceivedBackToAnnouncedThenRetries() {
        capture();
        announce();
        failThird = true;
        ready();
        assertThat(postingsWritten).hasValue(3);
        assertThat(state()).isEqualTo("ANNOUNCED");
        assertThat(jdbc.sql("SELECT status, actual_fee, batch_id FROM core.payment")
                        .query()
                        .singleRow())
                .containsEntry("status", "CAPTURED")
                .containsEntry("actual_fee", null)
                .containsEntry("batch_id", null);
        assertThat(count("core.settlement_batch_line")).isZero();
        assertThat(count("core.batch_transition")).isEqualTo(1);
        assertThat(count("core.payment_transition")).isEqualTo(1);
        assertThat(balances()).containsExactly(10000L, 0L, 0L, 10000L, 0L, 0L, 0L, 0L);
        assertThat(inbox("evt_ready")).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        failThird = false;
        clock.set(NOW.plusSeconds(1));
        runWorker();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("ledger.posting")).isEqualTo(7);
    }

    @Test
    void ac_5_17_interruptedPagesHaveNoVersionAndRetryWithCompleteEvidence() {
        capture();
        announce();
        interrupted = true;
        ready();
        assertThat(state()).isEqualTo("ANNOUNCED");
        assertThat(count("core.settlement_batch_line")).isZero();
        assertThat(count("recon.issue")).isZero();
        assertThat(jdbc.sql("SELECT lines_fetch_id FROM core.settlement_batch")
                        .query()
                        .singleRow())
                .containsEntry("lines_fetch_id", null);
        assertThat(jdbc.sql("SELECT complete FROM io.processor_fetch")
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(inbox("evt_ready")).containsEntry("attempts", 1);
        interrupted = false;
        clock.set(NOW.plusSeconds(1));
        runWorker();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("io.processor_fetch")).isEqualTo(2);
    }

    @Test
    void ac_5_17_readyBeforeCreatedAndPaidOnlyAddHistory() {
        capture();
        ready();
        assertThat(state()).isEqualTo("CONFIRMED");
        long history = count("core.batch_transition");
        announce();
        assertThat(inbox("evt_created")).containsEntry("result", "DUPLICATE_FACT");
        event("evt_paid", "payout.paid", payout("po_b1", "completed"));
        runWorker();
        assertThat(count("core.batch_transition")).isEqualTo(history + 2);
        assertThat(count("ledger.posting")).isEqualTo(7);
        assertThat(lineRequests).hasValue(1);
    }

    @Test
    void ac_5_17_createdAlreadyCompletedUsesSameSettlementPath() {
        capture();
        event("evt_created", "payout.created", payout("po_b1", "completed"));
        runWorker();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("ledger.posting")).isEqualTo(7);
    }

    @ParameterizedTest
    @CsvSource({
        "false,9680,standard,cad,completed",
        "true,-9680,standard,cad,completed",
        "true,9680,instant,cad,completed",
        "true,9680,standard,usd,completed",
        "true,9680,standard,cad,not_applicable"
    })
    void ac_5_17_unsupportedPayoutsAreHeldWithoutFetch(
            boolean automatic, long amount, String method, String currency, String readiness) {
        var data = payout("po_b1", readiness)
                .put("automatic", automatic)
                .put("amount", amount)
                .put("method", method)
                .put("currency", currency);
        event("evt_created", "payout.created", data);
        runWorker();
        assertThat(state()).isEqualTo("HELD");
        assertThat(jdbc.sql("SELECT category, detected_by, subject_type, subject_id FROM recon.issue")
                        .query()
                        .singleRow())
                .containsEntry("category", "UNSUPPORTED_PAYOUT")
                .containsEntry("detected_by", "INGESTION")
                .containsEntry("subject_type", "batch")
                .containsEntry("subject_id", "po_b1");
        assertThat(lineRequests).hasValue(0);
        assertThat(count("ledger.posting")).isZero();
    }

    @Test
    void ac_5_17_unknownPaymentIsHeld() {
        ready();
        assertThat(state()).isEqualTo("HELD");
        assertThat(jdbc.sql("SELECT hold_reason FROM core.settlement_batch")
                        .query(String.class)
                        .single())
                .isEqualTo("UNKNOWN_PAYMENT");
        assertThat(count("core.settlement_batch_line")).isZero();
        assertThat(count("ledger.posting")).isZero();
    }

    @Test
    void ac_5_17_paidParksUntilBatchCreationWakesItInSameTransaction() {
        event("evt_paid", "payout.paid", payout("po_b1", "in_progress"));
        runWorker();
        assertThat(inbox("evt_paid")).containsEntry("status", "PARKED").containsEntry("parked_on", "batch:po_b1");
        announce();
        assertThat(inbox("evt_paid")).containsEntry("status", "RECEIVED").containsEntry("parked_on", null);
        runWorker();
        assertThat(inbox("evt_paid")).containsEntry("status", "PROCESSED");
        assertThat(count("core.batch_transition")).isEqualTo(2);
        assertThat(count("ledger.posting")).isZero();
    }

    @Test
    void ac_2_8_sameFactWithNewEventSkipsFetchAndPosting() {
        capture();
        ready();
        event("evt_again", "payout.reconciliation_completed", payout("po_b1", "completed"));
        runWorker();
        assertThat(inbox("evt_again")).containsEntry("result", "DUPLICATE_FACT");
        assertThat(lineRequests).hasValue(1);
        assertThat(count("ledger.posting")).isEqualTo(7);
    }

    @Test
    void ac_5_17_duplicateCreatedOnlyRecordsHistoryEvenWhenItsSnapshotIsReady() {
        capture();
        announce();
        interrupted = true;
        event("evt_created_again", "payout.created", payout("po_b1", "completed"));
        runWorker();
        assertThat(inbox("evt_created_again")).containsEntry("result", "DUPLICATE_FACT");
        assertThat(state()).isEqualTo("ANNOUNCED");
        assertThat(lineRequests).hasValue(0);
        assertThat(count("core.batch_transition")).isEqualTo(2);
    }

    @Test
    void ac_2_8_twoWorkersAtFetchBarrierPostOnce() throws Exception {
        capture();
        announce();
        event("evt_ready_a", "payout.reconciliation_completed", payout("po_b1", "completed"));
        event("evt_ready_b", "payout.reconciliation_completed", payout("po_b1", "completed"));
        var barrier = new CyclicBarrier(2);
        WorkerHooks hooks = new WorkerHooks() {
            @Override
            public void afterFetch(String eventId) {
                try {
                    barrier.await(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> worker(hooks).processAvailable());
            var second = executor.submit(() -> worker(hooks).processAvailable());
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(count("ledger.posting")).isEqualTo(7);
        assertThat(List.of(
                        inbox("evt_ready_a").get("result"), inbox("evt_ready_b").get("result")))
                .containsExactlyInAnyOrder("APPLIED", "DUPLICATE_FACT");
    }

    @Test
    void ac_5_18_exampleHReissuesOnlyFailedPayoutWithTwoPostings() {
        capture();
        ready();
        tx.executeWithoutResult(status -> {
            ledger.lockFunds();
            ledger.post(new EntryRequest(
                    EntryType.BATCH_PAYOUT_FAILED,
                    "batch:po_b1:payout_failed",
                    NOW,
                    SubjectType.BATCH,
                    "po_b1",
                    List.of(
                            PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 9680),
                            PostingLine.credit(Account.SETTLEMENT_IN_TRANSIT, 9680))));
            jdbc.sql("UPDATE core.settlement_batch SET status = 'PAYOUT_FAILED' WHERE batch_id = 'po_b1'")
                    .update();
        });
        lines = page(line("txn_returned", "payout_failure", "po_b1", 9680, 0), false);
        event("evt_reissue", "payout.reconciliation_completed", payout("po_b2", "completed"));
        runWorker();
        assertThat(jdbc.sql("SELECT posting_count FROM ledger.journal_entry WHERE source_key = 'batch:po_b2:settled'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(balances()).containsExactly(0L, 9680L, 0L, 0L, 9680L, 0L, 0L, 0L);
    }

    @Test
    void ac_5_17_pollingWaitsThirtyMinutesAndBacksOffAcrossInstances() {
        capture();
        announce();
        payout = payout("po_b1", "in_progress").toString();
        assertThat(polling.poll()).isZero();
        clock.set(NOW.plusSeconds(1800));
        assertThat(polling.poll()).isEqualTo(1);
        polling = pollers.get();
        assertThat(polling.poll()).isZero();
        clock.set(NOW.plusSeconds(1860));
        assertThat(polling.poll()).isEqualTo(1);
        clock.set(NOW.plusSeconds(1979));
        assertThat(polling.poll()).isZero();
        payout = payout("po_b1", "completed").toString();
        clock.set(NOW.plusSeconds(1980));
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(polling.poll()).isZero();
        assertThat(payoutRequests).hasValue(3);
    }

    @Test
    void ac_5_17_pollingNotApplicableHoldsBatch() {
        announce();
        payout = payout("po_b1", "not_applicable").toString();
        clock.set(NOW.plusSeconds(1800));
        polling.poll();
        assertThat(state()).isEqualTo("HELD");
        assertThat(lineRequests).hasValue(0);
    }

    @Test
    void ac_8_2_livePayoutNeverFetchesOrCreatesBatch() {
        event("evt_live", "payout.reconciliation_completed", payout("po_b1", "completed"), true);
        runWorker();
        assertThat(inbox("evt_live")).containsEntry("result", "LIVEMODE");
        assertThat(count("core.settlement_batch")).isZero();
        assertThat(lineRequests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_2_8_expiredLeaseAfterFetchCannotCommitOrOverwriteNewOwner(boolean failBeforeBusiness) throws Exception {
        capture();
        announce();
        event("evt_ready", "payout.reconciliation_completed", payout("po_b1", "completed"));
        var fetched = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        WorkerHooks hooks = new WorkerHooks() {
            @Override
            public void afterFetch(String eventId) {
                fetched.countDown();
                try {
                    assertThat(resume.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                if (failBeforeBusiness) throw new IllegalStateException("old fetch failed");
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> worker(hooks).processAvailable());
            try {
                assertThat(fetched.await(15, TimeUnit.SECONDS)).isTrue();
                clock.set(NOW.plusSeconds(31));
                runWorker();
                assertThat(inbox("evt_ready")).containsEntry("lease_epoch", 2L).containsEntry("result", "APPLIED");
            } finally {
                resume.countDown();
            }
            assertThat(old.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(inbox("evt_ready")).containsEntry("attempts", 0).containsEntry("result", "APPLIED");
        assertThat(count("ledger.posting")).isEqualTo(7);
        assertThat(count("core.batch_transition")).isEqualTo(3);
    }

    @Test
    void ac_5_17_batchCreationAndParkedWakeRollBackTogether() {
        event("evt_paid", "payout.paid", payout("po_b1", "in_progress"));
        runWorker();
        event("evt_created", "payout.created", payout("po_b1", "in_progress"));
        worker(new WorkerHooks() {
                    @Override
                    public void afterBusiness(String eventId) {
                        throw new IllegalStateException("before commit");
                    }
                })
                .processAvailable();
        assertThat(count("core.settlement_batch")).isZero();
        assertThat(inbox("evt_paid")).containsEntry("status", "PARKED");
        clock.set(NOW.plusSeconds(1));
        runWorker();
        assertThat(inbox("evt_paid")).containsEntry("status", "RECEIVED");
        runWorker();
        assertThat(inbox("evt_paid")).containsEntry("status", "PROCESSED");
    }

    @ParameterizedTest
    @CsvSource({
        "refund,10000,320,UNSUPPORTED_LINE",
        "adjustment,10000,320,UNSUPPORTED_LINE",
        "charge,10000,10001,FEE_EXCEEDS_GROSS",
        "charge,10000,-1,UNSUPPORTED_LINE",
        "charge,0,0,UNSUPPORTED_LINE",
        "charge,100000000,0,UNSUPPORTED_LINE",
        "charge,9999,319,AMOUNT_MISMATCH",
        "charge,10000,321,TOTAL_MISMATCH",
        "payout_failure,9680,0,UNSUPPORTED_LINE",
        "payout_failure,10000,320,UNSUPPORTED_LINE"
    })
    void ac_5_17_unsafeLinesStayHeldWithoutPosting(String type, long gross, long fee, String reason) {
        capture();
        lines = page(line("txn_bad", type, "ch_p1", gross, fee), false);
        ready();
        assertThat(state()).isEqualTo("HELD");
        assertThat(jdbc.sql("SELECT hold_reason FROM core.settlement_batch")
                        .query(String.class)
                        .single())
                .isEqualTo(reason);
        assertThat(count("ledger.posting")).isEqualTo(2);
    }

    @Test
    void ac_5_17_zeroFeeOmitsPostingAndPayoutTransferRowIsSkipped() {
        capture();
        lines = page(
                line("txn_payment", "charge", "ch_p1", 10000, 0) + ","
                        + line("txn_payout", "payout", "po_b1", -10000, 0),
                false);
        event(
                "evt_ready",
                "payout.reconciliation_completed",
                payout("po_b1", "completed").put("amount", 10000));
        runWorker();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("core.settlement_batch_line")).isEqualTo(1);
        assertThat(count("ledger.posting")).isEqualTo(6);
    }

    @Test
    void ac_5_17_paymentOrBalanceTransactionCannotBeSettledTwice() {
        capture();
        ready();
        event("evt_second", "payout.reconciliation_completed", payout("po_b2", "completed"));
        runWorker();
        assertThat(jdbc.sql("SELECT hold_reason FROM core.settlement_batch WHERE batch_id = 'po_b2'")
                        .query(String.class)
                        .single())
                .isEqualTo("LINE_IN_OTHER_BATCH");
        lines = page(line("txn_other", "charge", "ch_p1", 10000, 320), false);
        event("evt_third", "payout.reconciliation_completed", payout("po_b3", "completed"));
        runWorker();
        assertThat(jdbc.sql("SELECT hold_reason FROM core.settlement_batch WHERE batch_id = 'po_b3'")
                        .query(String.class)
                        .single())
                .isEqualTo("LINE_IN_OTHER_BATCH");
        assertThat(count("ledger.posting")).isEqualTo(7);
    }

    @Test
    void ac_5_17_pollingRetriesReadAndPreparationFailuresWithArchivedBackoff() {
        capture();
        announce();
        clock.set(NOW.plusSeconds(1800));
        payout = "{}";
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(state()).isEqualTo("ANNOUNCED");
        clock.set(NOW.plusSeconds(1860));
        payout = payout("po_other", "completed").toString();
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(state()).isEqualTo("ANNOUNCED");
        clock.set(NOW.plusSeconds(1980));
        payout = payout("po_b1", "completed").toString();
        interrupted = true;
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(state()).isEqualTo("ANNOUNCED");
        clock.set(NOW.plusSeconds(2220));
        interrupted = false;
        assertThat(polling.poll()).isEqualTo(1);
        assertThat(state()).isEqualTo("CONFIRMED");
    }
}
