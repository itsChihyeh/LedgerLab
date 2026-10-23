package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.ledger.internal.JdbcLedgerAudit;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.payments.PaymentSettlement;
import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.shared.BusinessCalendar;
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
    private BankStatements bankStatements;
    private int failAfterPosting;

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
        bankStatements =
                new BankStatements(jdbc, ledger, issues, clock, ids, new BusinessCalendar(), mapper, manager, 2);
        var batches =
                new BatchSettlement(jdbc, ledger, new PaymentSettlement(jdbc), issues, clock, mapper, bankStatements);
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
                                        && failAfterPosting > 0
                                        && --failAfterPosting == 0) {
                                    throw new IllegalStateException("Injected during bank file posting");
                                }
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

    static byte[] bankCsv(
            String id,
            long amount,
            String direction,
            String currency,
            String status,
            String ref,
            String statementDate,
            String bookingDate,
            int version) {
        long credit = status.equals("BOOKED") && direction.equals("CREDIT") ? amount : 0;
        long debit = status.equals("BOOKED") && direction.equals("DEBIT") ? amount : 0;
        return ("H,BANK_STATEMENT,SIM-CAD-001," + statementDate + "," + statementDate + "," + version + ",CAD\r\n"
                        + "D," + id + "," + direction + "," + amount + "," + currency + "," + status + "," + ref + ","
                        + bookingDate + "," + bookingDate + ",memo\r\n"
                        + "T,1," + credit + "," + debit + "," + debit + "," + credit + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    BankStatements.ImportResult bank(String id, long amount) {
        return bankStatements.importBankStatement(
                "test.csv", bankCsv(id, amount, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1));
    }

    void modelBank(String action) {
        if (action.equals("EXPIRE_BANK")) {
            clock.set(Instant.parse("2026-10-28T04:00:00Z"));
            bankStatements.expireWaiting();
            return;
        }
        String id = action.equals("BANK") || action.equals("PENDING_BANK")
                ? "model_bank"
                : action.toLowerCase(java.util.Locale.ROOT);
        bankStatements.importBankStatement(
                "model.csv",
                bankCsv(
                        id,
                        action.equals("SHORT_BANK") ? 9679 : 9680,
                        action.equals("DEBIT_BANK") ? "DEBIT" : "CREDIT",
                        action.equals("FOREIGN_BANK") ? "USD" : "CAD",
                        action.equals("PENDING_BANK") ? "PENDING" : "BOOKED",
                        "po_b1",
                        "2026-10-23",
                        "2026-10-23",
                        1));
    }

    String bankState(String id) {
        return jdbc.sql(
                        "SELECT match_status FROM recon.bank_line WHERE bank_txn_id = :id ORDER BY version DESC LIMIT 1")
                .param("id", id)
                .query(String.class)
                .single();
    }

    @Test
    void ac_5_4_ac_1_4b_exampleAStepsOneThroughThreeAndFreeCash() {
        capture();
        assertThat(balances()).containsExactly(10000L, 0L, 0L, 10000L, 0L, 0L, 0L, 0L);
        ready();
        assertThat(balances()).containsExactly(0L, 9680L, 0L, 0L, 9680L, 0L, 0L, 0L);
        var b = balances();
        long freeCash = Math.subtractExact(
                Math.subtractExact(Math.subtractExact(b.get(2), b.get(6)), b.get(7)),
                Math.max(0L, Math.negateExact(b.getFirst())));
        assertThat(freeCash).isZero(); // P5 withdrawal API is deliberately not introduced here.
        assertThat(bank("t1", 9680).status()).isEqualTo("ACCEPTED");
        assertThat(bankState("t1")).isEqualTo("MATCHED");
        assertThat(state()).isEqualTo("BANKED");
        assertThat(balances()).containsExactly(0L, 0L, 9680L, 0L, 9680L, 0L, 0L, 0L);
        assertThat(count("ledger.posting")).isEqualTo(9);
        assertThat(jdbc.sql("SELECT source_key FROM ledger.journal_entry WHERE entry_type = 'BANK_DEPOSIT_RECEIVED'")
                        .query(String.class)
                        .single())
                .isEqualTo("bank_txn:t1");
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM core.batch_transition WHERE from_status='CONFIRMED' AND to_status='BANKED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void ac_5_5_shortDepositIsUnappliedWithCandidate() {
        capture();
        ready();
        bank("short", 9679);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(balances()).containsExactly(0L, 9680L, 9679L, 0L, 9680L, 0L, 0L, 9679L);
        assertThat(bankState("short")).isEqualTo("UNAPPLIED");
        assertThat(jdbc.sql("SELECT category, detected_by, evidence_refs::text AS refs FROM recon.issue")
                        .query()
                        .singleRow())
                .containsEntry("category", "DEPOSIT_AMOUNT_MISMATCH")
                .containsEntry("detected_by", "INGESTION");
        assertThat(jdbc.sql("SELECT evidence_refs::text FROM recon.issue")
                        .query(String.class)
                        .single())
                .contains("po_b1");
    }

    @Test
    void ac_5_6_secondRealDepositAndRepeatedFilesAndRows() {
        capture();
        ready();
        var first = bank("t1", 9680);
        var second = bank("t2", 9680);
        assertThat(bank("t1", 9680)).isEqualTo(first);
        assertThat(bank("t2", 9680)).isEqualTo(second);
        assertThat(count("io.imported_file")).isEqualTo(2);
        assertThat(count("recon.bank_line")).isEqualTo(2);
        assertThat(count("ledger.posting")).isEqualTo(11);
        assertThat(balances()).containsExactly(0L, 0L, 19360L, 0L, 9680L, 0L, 0L, 9680L);
        assertThat(bankState("t2")).isEqualTo("UNAPPLIED");
        assertThat(issueIdentities()).containsExactly("UNMATCHED_BANK_LINE:t2");
        bankStatements.importBankStatement(
                "different-version.csv",
                bankCsv("t1", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 2));
        assertThat(count("io.imported_file")).isEqualTo(3);
        assertThat(count("recon.bank_line")).isEqualTo(2);
        assertThat(count("ledger.posting")).isEqualTo(11);
    }

    @ParameterizedTest
    @CsvSource({"T,2,FILE_INCOMPLETE", "9680,9681,FILE_INCOMPLETE"})
    void ac_5_10_badTotalsArchiveRejectionWithoutAnyRows(String ignored, String other, String reason) {
        String csv = new String(
                bankCsv("t1", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1),
                StandardCharsets.UTF_8);
        csv = ignored.equals("T") ? csv.replace("T,1,", "T,2,") : csv.replace("T,1,9680,", "T,1,9681,");
        byte[] content = csv.getBytes(StandardCharsets.UTF_8);
        var result = bankStatements.importBankStatement("bad.csv", content);
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.rejectReason()).isEqualTo(reason);
        assertThat(bankStatements.importBankStatement("again.csv", content)).isEqualTo(result);
        assertThat(count("io.imported_file")).isEqualTo(1);
        assertThat(count("recon.bank_statement")).isZero();
        assertThat(count("recon.bank_line")).isZero();
        assertThat(jdbc.sql("SELECT raw FROM io.imported_file")
                        .query(byte[].class)
                        .single())
                .isEqualTo(content);
    }

    @Test
    void ac_5_12_balanceMismatchArchivesRaw() {
        byte[] content = new String(
                        bankCsv("t1", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1),
                        StandardCharsets.UTF_8)
                .replace("T,1,9680,0,0,9680", "T,1,9680,0,0,9679")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(bankStatements.importBankStatement("bad.csv", content).rejectReason())
                .isEqualTo("BALANCE_MISMATCH");
        assertThat(count("recon.bank_line")).isZero();
        assertThat(jdbc.sql("SELECT raw FROM io.imported_file")
                        .query(byte[].class)
                        .single())
                .isEqualTo(content);
    }

    @Test
    void ac_5_10_pendingAdvancesAcrossDailyFileVersionsButOtherChangesConflict() {
        capture();
        ready();
        bankStatements.importBankStatement(
                "pending.csv", bankCsv("p", 9680, "CREDIT", "CAD", "PENDING", "po_b1", "2026-10-22", "2026-10-23", 1));
        assertThat(bankState("p")).isEqualTo("IGNORED_PENDING");
        assertThat(count("ledger.posting")).isEqualTo(7);
        bankStatements.importBankStatement(
                "booked.csv", bankCsv("p", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1));
        assertThat(count("recon.bank_line")).isEqualTo(2);
        assertThat(bankState("p")).isEqualTo("MATCHED");
        bankStatements.importBankStatement(
                "changed.csv", bankCsv("p", 9679, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 2));
        bankStatements.importBankStatement(
                "regressed.csv",
                bankCsv("p", 9680, "CREDIT", "CAD", "PENDING", "po_b1", "2026-10-23", "2026-10-23", 3));
        assertThat(count("recon.bank_line")).isEqualTo(2);
        assertThat(count("ledger.posting")).isEqualTo(9);
        assertThat(issueIdentities()).containsExactly("STATEMENT_VERSION_CONFLICT:p");
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "announced", "held", "polling"})
    void ac_5_20_earlyCashWakesOnEveryConfirmationPath(String path) {
        if (path.equals("announced") || path.equals("polling")) announce();
        if (path.equals("held")) ready();
        var original = bank("early", 9680);
        assertThat(bankState("early")).isEqualTo("WAITING");
        assertThat(balances().get(2)).isZero();
        capture();
        if (path.equals("polling")) {
            clock.set(NOW.plusSeconds(1800));
            polling.poll();
        } else if (!path.equals("held")) ready();
        assertThat(state()).isEqualTo("BANKED");
        assertThat(bankState("early")).isEqualTo("MATCHED");
        assertThat(bank("early", 9680)).isEqualTo(original);
        assertThat(balances()).containsExactly(0L, 0L, 9680L, 0L, 9680L, 0L, 0L, 0L);
    }

    @Test
    void ac_5_20_wakeAndBothEntriesRollbackWithConfirmation() {
        capture();
        announce();
        bank("early", 9680);
        event("evt_ready", "payout.reconciliation_completed", payout("po_b1", "completed"));
        worker(new WorkerHooks() {
                    @Override
                    public void afterBusiness(String id) {
                        assertThat(state()).isEqualTo("BANKED");
                        assertThat(count("ledger.posting")).isEqualTo(9);
                        throw new IllegalStateException("after both cash and batch entries");
                    }
                })
                .processAvailable();
        assertThat(state()).isEqualTo("ANNOUNCED");
        assertThat(bankState("early")).isEqualTo("WAITING");
        assertThat(count("ledger.posting")).isEqualTo(2);
        clock.set(NOW.plusSeconds(1));
        runWorker();
        assertThat(state()).isEqualTo("BANKED");
        assertThat(count("ledger.posting")).isEqualTo(9);
    }

    @Test
    void ac_5_20_unknownPayoutExpiresAtSecondBusinessDayEndAcrossHoliday() {
        clock.set(Instant.parse("2026-11-10T15:00:00Z"));
        bankStatements.importBankStatement(
                "early.csv",
                bankCsv("unknown", 9680, "CREDIT", "CAD", "BOOKED", "po_unknown", "2026-11-10", "2026-11-10", 1));
        assertThat(bankState("unknown")).isEqualTo("WAITING");
        assertThat(jdbc.sql("SELECT wait_until FROM recon.bank_line")
                        .query(Instant.class)
                        .single())
                .isEqualTo(Instant.parse("2026-11-14T05:00:00Z"));
        clock.set(Instant.parse("2026-11-14T04:59:59Z"));
        bankStatements.expireWaiting();
        assertThat(bankState("unknown")).isEqualTo("WAITING");
        clock.set(Instant.parse("2026-11-14T05:00:00Z"));
        bankStatements.expireWaiting();
        bankStatements.expireWaiting();
        assertThat(bankState("unknown")).isEqualTo("UNAPPLIED");
        assertThat(balances()).containsExactly(0L, 0L, 9680L, 0L, 0L, 0L, 0L, 9680L);
        assertThat(issueIdentities()).containsExactly("UNMATCHED_BANK_LINE:unknown");
    }

    @Test
    void ac_5_20_storedDeadlineSurvivesConfigurationChange() {
        bank("early", 9680);
        clock.set(Instant.parse("2026-10-28T04:00:00Z"));
        var changed = new BankStatements(
                jdbc,
                ledger,
                issues,
                clock,
                IdGenerator.seeded(clock, 808),
                new BusinessCalendar(),
                mapper,
                manager,
                3);
        changed.expireWaiting();
        assertThat(bankState("early")).isEqualTo("UNAPPLIED");
    }

    @ParameterizedTest
    @CsvSource({
        "USD,CREDIT,BOOKED,UNAPPLIED,9680",
        "CAD,DEBIT,BOOKED,UNMATCHED_NO_POSTING,0",
        "CAD,CREDIT,PENDING,IGNORED_PENDING,0"
    })
    void ac_5_20_currencyDirectionAndPendingCannotMatch(
            String currency, String direction, String status, String expected, long cash) {
        capture();
        ready();
        bankStatements.importBankStatement(
                "line.csv",
                bankCsv("ineligible", 9680, direction, currency, status, "po_b1", "2026-10-23", "2026-10-23", 1));
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(bankState("ineligible")).isEqualTo(expected);
        assertThat(balances().get(2)).isEqualTo(cash);
        assertThat(count("recon.issue")).isEqualTo(status.equals("PENDING") ? 0 : 1);
    }

    @Test
    void ac_5_20_failedPayoutDepositRemainsUnapplied() {
        capture();
        ready();
        event("evt_fail", "payout.failed", payout("po_b1", "completed"));
        runWorker();
        bank("late", 9680);
        assertThat(state()).isEqualTo("PAYOUT_FAILED");
        assertThat(bankState("late")).isEqualTo("UNAPPLIED");
        assertThat(issueIdentities()).containsExactly("BATCH_STATE_CONFLICT:late");
        assertThat(balances()).containsExactly(9680L, 0L, 9680L, 0L, 9680L, 0L, 0L, 9680L);
    }

    @Test
    void ac_5_6_concurrentSameFileImportsExactlyOnce() throws Exception {
        capture();
        ready();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<BankStatements.ImportResult> call = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return bank("same", 9680);
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(count("io.imported_file")).isEqualTo(1);
        assertThat(count("ledger.posting")).isEqualTo(9);
    }

    @Test
    void ac_5_20_confirmationAndImportDoNotLoseWakeup() throws Exception {
        capture();
        announce();
        event("evt_ready", "payout.reconciliation_completed", payout("po_b1", "completed"));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                runWorker();
                return 0;
            });
            var b = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return bank("early", 9680);
            });
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        }
        assertThat(state()).isEqualTo("BANKED");
        assertThat(bankState("early")).isEqualTo("MATCHED");
        assertThat(count("ledger.posting")).isEqualTo(9);
    }

    @Test
    void ac_5_10_importJoinsCallerRollback() {
        capture();
        ready();
        byte[] first = bankCsv("t1", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(ignored -> {
                    bankStatements.importBankStatement("rollback.csv", first);
                    assertThat(state()).isEqualTo("BANKED");
                    throw new IllegalStateException("failed before commit");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("io.imported_file")).isZero();
        assertThat(count("recon.bank_line")).isZero();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("ledger.posting")).isEqualTo(7);
        bank("t1", 9680);
        assertThat(state()).isEqualTo("BANKED");
    }

    @Test
    void ac_5_10_wholeFileRollsBackIfSecondLinePostingFails() {
        capture();
        ready();
        String csv = new String(
                bankCsv("first", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1),
                StandardCharsets.UTF_8);
        String second = "D,second,CREDIT,9680,CAD,BOOKED,po_b1,2026-10-23,2026-10-23,memo\r\n";
        byte[] content =
                csv.replace("T,1,9680,0,0,9680", second + "T,2,19360,0,0,19360").getBytes(StandardCharsets.UTF_8);
        failAfterPosting = 3;
        assertThatThrownBy(() -> bankStatements.importBankStatement("two-lines.csv", content))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("io.imported_file")).isZero();
        assertThat(count("recon.bank_statement")).isZero();
        assertThat(count("recon.bank_line")).isZero();
        assertThat(count("ledger.posting")).isEqualTo(7);
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("core.batch_transition")).isEqualTo(3);
        bankStatements.importBankStatement("two-lines.csv", content);
        assertThat(bankState("first")).isEqualTo("MATCHED");
        assertThat(bankState("second")).isEqualTo("UNAPPLIED");
        assertThat(count("ledger.posting")).isEqualTo(11);
    }

    @Test
    void ac_5_20_waitingLinesWakeInImportOrder() {
        bank("z_first", 9680);
        bank("a_second", 9680);
        capture();
        ready();
        assertThat(bankState("z_first")).isEqualTo("MATCHED");
        assertThat(bankState("a_second")).isEqualTo("UNAPPLIED");
        assertThat(count("ledger.posting")).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"CREDIT|DEBIT", "9680|9679", "CAD|USD", "po_b1|po_other", "2026-10-23|2026-10-24", "memo|changed"
            })
    void ac_5_10_pendingPromotionCannotChangeOtherEvidence(String change) {
        byte[] pending = bankCsv("p", 9680, "CREDIT", "CAD", "PENDING", "po_b1", "2026-10-22", "2026-10-23", 1);
        bankStatements.importBankStatement("pending.csv", pending);
        String[] parts = change.split("\\|", 2);
        String csv = new String(
                bankCsv("p", 9680, "CREDIT", "CAD", "BOOKED", "po_b1", "2026-10-23", "2026-10-23", 1),
                StandardCharsets.UTF_8);
        String[] records = csv.split("\r\n", -1);
        records[1] = records[1].replace(parts[0], parts[1]);
        if (parts[0].equals("9680")) records[2] = "T,1,9679,0,0,9679";
        if (parts[0].equals("CREDIT")) records[2] = "T,1,0,9680,9680,0";
        var result = bankStatements.importBankStatement(
                "changed.csv", String.join("\r\n", records).getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(bankState("p")).isEqualTo("IGNORED_PENDING");
        assertThat(count("recon.bank_line")).isEqualTo(1);
        assertThat(count("ledger.posting")).isZero();
        assertThat(issueIdentities()).containsExactly("STATEMENT_VERSION_CONFLICT:p");
    }

    @Test
    void ac_5_2_amountMismatchKeepsCaptureAndArchivesExpectedAndActual() {
        capture();
        lines = page(line("txn_payment", "charge", "ch_p1", 9999, 320), false);
        event(
                "evt_ready",
                "payout.reconciliation_completed",
                payout("po_b1", "completed").put("amount", 9679));
        runWorker();
        assertHold("AMOUNT_MISMATCH", "AMOUNT_MISMATCH");
        assertThat(jdbc.sql("SELECT expected->>'gross' FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("10000");
        assertThat(jdbc.sql("SELECT actual->'line'->>'gross' FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("9999");
        assertThat(jdbc.sql("SELECT status FROM core.payment")
                        .query(String.class)
                        .single())
                .isEqualTo("CAPTURED");
        assertThat(count("ledger.posting")).isEqualTo(2);
    }

    @Test
    void ac_5_19_feeAboveGrossHoldsWholePositivePayout() {
        capture();
        lines = page(
                line("txn_small", "charge", "ch_small", 50, 60) + ","
                        + line("txn_payment", "charge", "ch_p1", 10000, 320),
                false);
        event(
                "evt_ready",
                "payout.reconciliation_completed",
                payout("po_b1", "completed").put("amount", 9670));
        runWorker();
        assertHold("FEE_EXCEEDS_GROSS", "AMOUNT_MISMATCH");
        assertThat(jdbc.sql("SELECT actual->'line'->>'fee' FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("60");
        assertThat(count("ledger.posting")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"adjustment", "dispute", "refund"})
    void ac_5_2_unsupportedLinesAreHeldWithEvidence(String type) {
        lines = page(line("txn_other", type, "ch_p1", 10000, 320), false);
        ready();
        assertHold("UNSUPPORTED_LINE", "UNSUPPORTED_EVENT");
        assertThat(count("ledger.posting")).isZero();
    }

    private void assertHold(String reason, String category) {
        assertThat(state()).isEqualTo("HELD");
        assertThat(jdbc.sql("SELECT hold_reason FROM core.settlement_batch WHERE batch_id = 'po_b1'")
                        .query(String.class)
                        .single())
                .isEqualTo(reason);
        assertThat(jdbc.sql("SELECT category, detected_by, subject_type, subject_id FROM recon.issue")
                        .query()
                        .singleRow())
                .containsEntry("category", category)
                .containsEntry("detected_by", "INGESTION")
                .containsEntry("subject_type", "batch")
                .containsEntry("subject_id", "po_b1");
        assertThat(inbox("evt_ready")).containsEntry("status", "PROCESSED").containsEntry("result", "APPLIED");
        assertThat(count("core.settlement_batch_line")).isZero();
        assertThat(count("io.processor_fetch")).isEqualTo(1);
    }

    @Test
    void ac_4_1_captureRechecksArchivedUnknownPaymentInSameTransaction() {
        ready();
        assertThat(state()).isEqualTo("HELD");
        assertThat(count("recon.issue")).isZero();
        assertThat(count("ledger.posting")).isZero();
        // The next HTTP response is unusable: recovery must use the pinned archive.
        lines = "unavailable";
        capture();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(lineRequests).hasValue(1);
        assertThat(balances()).containsExactly(0L, 9680L, 0L, 0L, 9680L, 0L, 0L, 0L);
        assertThat(count("ledger.posting")).isEqualTo(7);
    }

    @Test
    void ac_4_1_captureRollbackAlsoRollsBackRecheckAndCanRetry() {
        ready();
        event("evt_capture", "charge.succeeded", charge("ch_p1", 10000));
        worker(new WorkerHooks() {
                    @Override
                    public void afterBusiness(String eventId) {
                        assertThat(state()).isEqualTo("CONFIRMED");
                        assertThat(count("ledger.posting")).isEqualTo(7);
                        throw new IllegalStateException("Injected capture rollback after recheck");
                    }
                })
                .processAvailable();
        assertThat(state()).isEqualTo("HELD");
        assertThat(count("core.payment")).isZero();
        assertThat(count("core.settlement_batch_line")).isZero();
        assertThat(count("ledger.posting")).isZero();
        assertThat(count("io.processor_fetch")).isEqualTo(1);
        clock.set(NOW.plusSeconds(1));
        runWorker();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(inbox("evt_capture")).containsEntry("result", "APPLIED");
        assertThat(lineRequests).hasValue(1);
    }

    ObjectNode charge(String id, long amount) {
        return (ObjectNode) mapper.readTree("""
                {"id":"%s","payment_intent":"pi_synthetic","amount":%d,"amount_captured":%d,"currency":"cad",
                 "captured":true,"status":"succeeded","livemode":false,"created":1792756700}
                """.formatted(id, amount, amount));
    }

    void modelLines(boolean changed, boolean returned) {
        lines = page(
                returned
                        ? line("txn_returned", "payout_failure", "po_b1", 9680, 0)
                        : line("txn_payment", "charge", "ch_p1", changed ? 10001 : 10000, changed ? 321 : 320),
                false);
    }

    void modelUnsupportedLine() {
        lines = page(line("txn_payment", "refund", "ch_p1", 10000, 320), false);
    }

    void modelExcessFee() {
        lines = page(line("txn_payment", "charge", "ch_p1", 50, 60), false);
    }

    @Nullable
    String holdReason() {
        return (String) jdbc.sql("SELECT hold_reason FROM core.settlement_batch WHERE batch_id = 'po_b1'")
                .query()
                .singleRow()
                .get("hold_reason");
    }

    List<@Nullable String> issueIdentities() {
        return jdbc.sql(
                        "SELECT category || ':' || subject_id FROM recon.issue WHERE detected_by = 'INGESTION' AND subject_type IN ('batch', 'bank_txn')")
                .query(String.class)
                .list();
    }

    void assertSettlementInvariants() {
        assertThat(jdbc.sql("SELECT balance FROM ledger.account WHERE code = '2101'")
                        .query(Long.class)
                        .single())
                .isEqualTo(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM core.payment WHERE status = 'CAPTURED'")
                        .query(Long.class)
                        .single());
        assertThat(jdbc.sql("SELECT balance FROM ledger.account WHERE code = '1102'")
                        .query(Long.class)
                        .single())
                .isEqualTo(
                        jdbc.sql("SELECT COALESCE(SUM(net), 0) FROM core.settlement_batch WHERE status = 'CONFIRMED'")
                                .query(Long.class)
                                .single());
    }

    @Test
    void ac_5_3_normalizedVersionIgnoresOrderingFormattingAndCurrencyCase() {
        capture();
        event("evt_p2", "charge.succeeded", charge("ch_p2", 10000));
        runWorker();
        String first = line("txn_payment", "charge", "ch_p1", 10000, 320);
        String second = line("txn_p2", "charge", "ch_p2", 10000, 320);
        lines = page(first + "," + second, false);
        event(
                "evt_ready",
                "payout.reconciliation_completed",
                payout("po_b1", "completed").put("amount", 19360));
        runWorker();
        lines = page(second + "," + first, false).replace("cad", "CAD").replace(",", ",\n  ");
        event(
                "evt_duplicate",
                "payout.reconciliation_completed",
                payout("po_b1", "completed").put("amount", 19360));
        runWorker();
        assertThat(inbox("evt_duplicate")).containsEntry("result", "DUPLICATE_FACT");
        assertThat(count("recon.issue")).isZero();
        assertThat(count("ledger.posting")).isEqualTo(9);
        assertThat(jdbc.sql("SELECT count(DISTINCT response_sha256) FROM io.processor_fetch")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void ac_5_2_contractFeeDifferenceIsSettledAtActualFee() {
        capture();
        lines = page(line("txn_payment", "charge", "ch_p1", 10000, 321), false);
        event(
                "evt_ready",
                "payout.reconciliation_completed",
                payout("po_b1", "completed").put("amount", 9679));
        runWorker();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT expected_fee, actual_fee FROM core.payment")
                        .query()
                        .singleRow())
                .containsEntry("expected_fee", 320L)
                .containsEntry("actual_fee", 321L);
        assertThat(count("recon.issue")).isZero();
        assertThat(balances()).containsExactly(0L, 9679L, 0L, 0L, 9679L, 0L, 0L, 0L);
    }

    @Test
    void ac_4_1_unrelatedCaptureAndClockAdvanceLeaveUnknownPaymentHeld() {
        ready();
        event("evt_other", "charge.succeeded", charge("ch_other", 10000));
        runWorker();
        clock.set(NOW.plus(Duration.ofDays(5)));
        polling.poll();
        assertThat(state()).isEqualTo("HELD");
        assertThat(count("core.batch_transition")).isEqualTo(3);
        assertThat(count("recon.issue")).isZero();
        assertThat(count("core.settlement_batch_line")).isZero();
        assertThat(lineRequests).hasValue(1);
    }

    @Test
    void ac_4_1_otherHoldReasonsNeverAutomaticallyRecover() {
        capture();
        lines = page(line("txn_payment", "charge", "ch_p1", 9999, 319), false);
        ready();
        long history = count("core.batch_transition");
        modelLines(false, false);
        event("evt_capture_again", "charge.succeeded", charge("ch_p1", 10000));
        runWorker();
        assertThat(state()).isEqualTo("HELD");
        assertThat(count("core.batch_transition")).isEqualTo(history);
        event("evt_ready_again", "payout.reconciliation_completed", payout("po_b1", "completed"));
        runWorker();
        assertThat(state()).isEqualTo("HELD");
        assertThat(lineRequests).hasValue(1);
        assertThat(count("ledger.posting")).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"payout.failed,false", "payout.failed,true", "payout.canceled,false", "payout.canceled,true"})
    void ac_4_1_failureParksThenOnlyRecordsHistoryForUnconfirmedBatch(String type, boolean held) {
        event("evt_failed", type, payout("po_b1", "completed"));
        runWorker();
        assertThat(inbox("evt_failed")).containsEntry("status", "PARKED");
        if (held) ready();
        else announce();
        String before = state();
        runWorker();
        assertThat(state()).isEqualTo(before);
        assertThat(inbox("evt_failed")).containsEntry("result", "APPLIED");
        assertThat(count("ledger.posting")).isZero();
        assertThat(count("recon.issue")).isZero();
        if (held)
            assertThat(jdbc.sql("SELECT hold_reason FROM core.settlement_batch")
                            .query(String.class)
                            .single())
                    .isEqualTo("UNKNOWN_PAYMENT");
    }

    @Test
    void ac_1_4b_payoutFailureAndEntryRollbackTogether() {
        capture();
        ready();
        event("evt_failed", "payout.failed", payout("po_b1", "completed"));
        worker(new WorkerHooks() {
                    @Override
                    public void afterBusiness(String eventId) {
                        assertThat(state()).isEqualTo("PAYOUT_FAILED");
                        assertThat(count("ledger.posting")).isEqualTo(9);
                        throw new IllegalStateException("Injected failure rollback");
                    }
                })
                .processAvailable();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count("ledger.posting")).isEqualTo(7);
        clock.set(NOW.plusSeconds(1));
        runWorker();
        assertThat(state()).isEqualTo("PAYOUT_FAILED");
        assertThat(count("ledger.posting")).isEqualTo(9);
    }

    @Test
    void ac_5_3_changedCompleteVersionIsArchivedOncePerIssueWithoutChangingFacts() {
        capture();
        ready();
        var original = jdbc.sql("SELECT * FROM core.settlement_batch").query().singleRow();
        var originalLines =
                jdbc.sql("SELECT * FROM core.settlement_batch_line").query().listOfRows();
        var originalEntries = jdbc.sql("SELECT * FROM ledger.journal_entry ORDER BY seq")
                .query()
                .listOfRows();
        lines = page(line("txn_payment", "charge", "ch_p1", 10001, 321), false);
        for (int i = 0; i < 2; i++) {
            event("evt_changed_" + i, "payout.reconciliation_completed", payout("po_b1", "completed"));
            runWorker();
            assertThat(inbox("evt_changed_" + i)).containsEntry("result", "BUSINESS_CONFLICT");
        }
        assertThat(jdbc.sql("SELECT * FROM core.settlement_batch").query().singleRow())
                .isEqualTo(original);
        assertThat(jdbc.sql("SELECT * FROM core.settlement_batch_line").query().listOfRows())
                .isEqualTo(originalLines);
        assertThat(jdbc.sql("SELECT * FROM ledger.journal_entry ORDER BY seq")
                        .query()
                        .listOfRows())
                .isEqualTo(originalEntries);
        assertThat(count("io.processor_fetch")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT category FROM recon.issue")
                        .query(String.class)
                        .list())
                .containsExactly("BATCH_VERSION_CONFLICT");
        interrupted = true;
        event("evt_incomplete", "payout.reconciliation_completed", payout("po_b1", "completed"));
        runWorker();
        assertThat(inbox("evt_incomplete")).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        assertThat(count("recon.issue")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payout.failed", "payout.canceled"})
    void ac_1_4b_ac_5_18_exampleHUsesRealFailureEventAndReissuedBatch(String type) {
        capture();
        ready();
        event("evt_failed", type, payout("po_b1", "completed"));
        runWorker();
        assertThat(state()).isEqualTo("PAYOUT_FAILED");
        assertThat(balances()).containsExactly(9680L, 0L, 0L, 0L, 9680L, 0L, 0L, 0L);
        event("evt_failed_again", type, payout("po_b1", "completed"));
        runWorker();
        assertThat(inbox("evt_failed_again")).containsEntry("result", "DUPLICATE_FACT");
        lines = page(line("txn_return", "payout_failure", "po_b1", 9680, 0), false);
        event("evt_reissued", "payout.reconciliation_completed", payout("po_reissued", "completed"));
        runWorker();
        assertThat(balances()).containsExactly(0L, 9680L, 0L, 0L, 9680L, 0L, 0L, 0L);
        assertThat(jdbc.sql(
                                "SELECT posting_count FROM ledger.journal_entry WHERE source_key = 'batch:po_reissued:settled'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
        assertThat(count("ledger.posting")).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payout.failed", "payout.canceled"})
    void ac_5_3_bankedFailureOpensStateConflictWithoutPosting(String type) {
        capture();
        ready();
        JdbcClient.create(db.migrator())
                .sql("UPDATE core.settlement_batch SET status = 'BANKED' WHERE batch_id = 'po_b1'")
                .update();
        event("evt_failed", type, payout("po_b1", "completed"));
        runWorker();
        assertThat(state()).isEqualTo("BANKED");
        assertThat(count("ledger.posting")).isEqualTo(7);
        assertThat(jdbc.sql("SELECT category FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("BATCH_STATE_CONFLICT");
        assertThat(inbox("evt_failed")).containsEntry("result", "BUSINESS_CONFLICT");
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
    void ac_2_8_sameFactWithNewEventComparesFetchWithoutPosting() {
        capture();
        ready();
        event("evt_again", "payout.reconciliation_completed", payout("po_b1", "completed"));
        runWorker();
        assertThat(inbox("evt_again")).containsEntry("result", "DUPLICATE_FACT");
        assertThat(lineRequests).hasValue(2);
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
