package dev.ledgerlab.payments;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static dev.ledgerlab.testsupport.LedgerFixture.only;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostedEntry;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.ledger.internal.LedgerFaults;
import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL, app-role connections, explicit caller transactions and deterministic fault controls. */
class PaymentCaptureTest {
    private final Clock clock = Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC);
    private TestDatabase.Handle db;
    private LedgerFixture fixture;
    private DataSource source;
    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private IdGenerator ids;
    private Ledger ledger;
    private PaymentCapture service;

    @BeforeEach
    void setUp() {
        db = TestDatabase.freshMigrated();
        fixture = new LedgerFixture(db);
        source = db.app();
        jdbc = JdbcClient.create(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        ids = IdGenerator.seeded(clock, 101);
        ledger = new JdbcLedger(source, clock, ids);
        service = service(ledger);
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    private PaymentCapture service(Ledger target) {
        return new PaymentCapture(
                target, jdbc, new JdbcIngestionIssues(jdbc, ids, clock), ids, clock, new JsonMapper());
    }

    private static ChargeEvidence evidence(String event, String charge, Long amount) {
        return new ChargeEvidence(
                event, charge, "pi_test", amount, amount, "cad", true, "succeeded", false, T0, T0.plusSeconds(30));
    }

    private void inbox(ChargeEvidence evidence) {
        jdbc.sql("""
                INSERT INTO io.inbox_event
                    (event_id, event_type, api_version, livemode, payload, payload_sha256,
                     signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
                VALUES (:event, 'charge.succeeded', '2026-09-30.endive', :live,
                    '{}'::bytea, encode(sha256('{}'::bytea), 'hex'),
                    :time, :time, :received, :time, 'RECEIVED', :time)
                """)
                .param("event", evidence.eventId())
                .param("live", evidence.livemode())
                .param("received", evidence.receivedAt().atOffset(ZoneOffset.UTC))
                .param("time", T0.atOffset(ZoneOffset.UTC))
                .update();
    }

    private CaptureResult capture(ChargeEvidence evidence) {
        inbox(evidence);
        return transactions.execute(status -> service.capture(evidence));
    }

    private void assertBalances(long amount) {
        assertThat(fixture.balances())
                .isEqualTo(only(Account.PROCESSOR_RECEIVABLE, amount, Account.MERCHANT_PENDING, amount));
        assertThat(fixture.audit().check()).isEmpty();
    }

    private void assertCounts(long payments, long entries, long postings, long transitions) {
        assertThat(fixture.count("core.payment")).isEqualTo(payments);
        assertThat(fixture.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(fixture.count("ledger.posting")).isEqualTo(postings);
        assertThat(fixture.count("core.payment_transition")).isEqualTo(transitions);
    }

    @Test
    void ac_1_1_ac_1_4a_captureMatchesS1AndPreservesEvidenceAndTimes() {
        var evidence = evidence("evt_1", "ch_1", 10000L);
        assertThat(capture(evidence)).isEqualTo(CaptureResult.APPLIED);
        assertCounts(1, 1, 2, 1);
        assertBalances(10000);
        var payment = jdbc.sql("SELECT * FROM core.payment").query().singleRow();
        assertThat(payment.get("id")).asString().matches("pay_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(payment)
                .containsEntry("status", "CAPTURED")
                .containsEntry("amount", 10000L)
                .containsEntry("expected_fee", 320L)
                .containsEntry("processor_payment_ref", "ch_1")
                .containsEntry("processor_intent_ref", "pi_test")
                .containsEntry("source_event_id", "evt_1")
                .containsEntry("refunded_amount", 0L)
                .containsEntry("actual_fee", null)
                .containsEntry("batch_id", null);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM core.payment p JOIN core.payment_transition t ON t.subject_id = p.id
                JOIN ledger.journal_entry e ON e.subject_id = p.id
                WHERE p.occurred_at = :occurred AND p.received_at = :received AND p.recorded_at = :now
                    AND p.received_at <> p.recorded_at
                    AND t.from_status IS NULL AND t.to_status = 'CAPTURED' AND t.evidence_type = 'inbox_event'
                    AND t.evidence_id = 'evt_1' AND t.occurred_at = :occurred AND t.recorded_at = :now
                    AND e.subject_type = 'payment' AND e.entry_type = 'PAYMENT_CAPTURED'
                    AND e.source_key = 'payment:ch_1:captured' AND e.occurred_at = :occurred AND e.recorded_at = :now
                """)
                        .param("occurred", T0.atOffset(ZoneOffset.UTC))
                        .param("received", evidence.receivedAt().atOffset(ZoneOffset.UTC))
                        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT account_code || ':' || side || ':' || amount FROM ledger.posting ORDER BY line_no")
                        .query(String.class)
                        .list())
                .containsExactly("1101:D:10000", "2101:C:10000");
        assertThat(fixture.count("recon.issue")).isZero();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -500, 49, 100000000, Long.MIN_VALUE, Long.MAX_VALUE})
    void ac_1_2_invalidAmountHasOnlyAnIssue(Long amount) {
        assertRejected(
                evidence("evt_invalid", "ch_invalid", amount), CaptureResult.INVALID_AMOUNT, "UNSUPPORTED_EVENT");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"usd", ""})
    void ac_1_3_nonCadHasOnlyAnIssue(String currency) {
        assertRejected(
                new ChargeEvidence(
                        "evt_currency",
                        "ch_1",
                        null,
                        10000L,
                        10000L,
                        currency,
                        true,
                        "succeeded",
                        false,
                        T0,
                        T0.plusSeconds(30)),
                CaptureResult.UNSUPPORTED_CURRENCY,
                "UNSUPPORTED_EVENT");
    }

    static Stream<Arguments> unsupportedCaptures() {
        return Stream.of(
                Arguments.of(false, 10000L, "succeeded"),
                Arguments.of(null, 10000L, "succeeded"),
                Arguments.of(true, 9999L, "succeeded"),
                Arguments.of(true, null, "succeeded"),
                Arguments.of(true, 10001L, "succeeded"),
                Arguments.of(true, 10000L, "pending"),
                Arguments.of(true, 10000L, null));
    }

    @ParameterizedTest
    @MethodSource("unsupportedCaptures")
    void ac_1_12_unsupportedCaptureHasOnlyAnIssue(Boolean captured, Long capturedAmount, String status) {
        assertRejected(
                new ChargeEvidence(
                        "evt_capture",
                        "ch_1",
                        null,
                        10000L,
                        capturedAmount,
                        "cad",
                        captured,
                        status,
                        false,
                        T0,
                        T0.plusSeconds(30)),
                CaptureResult.UNSUPPORTED_CAPTURE,
                "UNSUPPORTED_EVENT");
    }

    @Test
    void ac_1_12_liveModeWinsOverOtherAdmissionFailures() {
        assertRejected(
                new ChargeEvidence(
                        "evt_live", "ch_1", null, null, null, "usd", false, "pending", true, T0, T0.plusSeconds(30)),
                CaptureResult.LIVEMODE,
                "LIVEMODE_EVENT");
    }

    @Test
    void ac_1_2_ac_1_3_admissionReportsOnlyTheFirstFailure() {
        assertRejected(
                new ChargeEvidence(
                        "evt_priority",
                        "ch_1",
                        null,
                        49L,
                        null,
                        "usd",
                        false,
                        "pending",
                        false,
                        T0,
                        T0.plusSeconds(30)),
                CaptureResult.INVALID_AMOUNT,
                "UNSUPPORTED_EVENT");
        assertThat(capture(new ChargeEvidence(
                        "evt_priority2",
                        "ch_1",
                        null,
                        50L,
                        null,
                        "usd",
                        false,
                        "pending",
                        false,
                        T0,
                        T0.plusSeconds(30))))
                .isEqualTo(CaptureResult.UNSUPPORTED_CURRENCY);
        assertThat(fixture.count("recon.issue")).isEqualTo(2);
        assertCounts(0, 0, 0, 0);
    }

    private void assertRejected(ChargeEvidence evidence, CaptureResult result, String category) {
        assertThat(capture(evidence)).isEqualTo(result);
        assertCounts(0, 0, 0, 0);
        assertBalances(0);
        assertThat(jdbc.sql("SELECT category, subject_type, subject_id, detected_by FROM recon.issue")
                        .query()
                        .singleRow())
                .containsEntry("category", category)
                .containsEntry("subject_type", "event")
                .containsEntry("subject_id", evidence.eventId())
                .containsEntry("detected_by", "INGESTION");
        assertThat(jdbc.sql("SELECT evidence_refs->0->>'id' FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo(evidence.eventId());
    }

    @ParameterizedTest
    @CsvSource({"50,31", "500,45", "99999999,2900030"})
    void ac_1_1_boundaryAmountsAndHalfUpFee(long amount, long fee) {
        assertThat(capture(new ChargeEvidence(
                        "evt_bound",
                        "ch_bound",
                        null,
                        amount,
                        amount,
                        "CaD",
                        true,
                        "succeeded",
                        false,
                        T0,
                        T0.plusSeconds(30))))
                .isEqualTo(CaptureResult.APPLIED);
        assertThat(jdbc.sql("SELECT expected_fee FROM core.payment")
                        .query(Long.class)
                        .single())
                .isEqualTo(fee);
        assertBalances(amount);
    }

    @Test
    void ac_1_10_firstPostingFailureRollsBackAllBusinessWritesAndRetryReachesS1() {
        var evidence = evidence("evt_retry", "ch_retry", 10000L);
        inbox(evidence);
        AtomicInteger calls = new AtomicInteger();
        var faulty = service(LedgerFaults.afterFirstPosting(source, clock, ids, () -> {
            calls.incrementAndGet();
            for (String table :
                    List.of("core.payment", "core.payment_transition", "ledger.journal_entry", "ledger.posting")) {
                assertThat(jdbc.sql("SELECT count(*) FROM " + table)
                                .query(Long.class)
                                .single())
                        .isEqualTo(1);
            }
            throw new IllegalStateException("crash after first posting");
        }));
        assertThatThrownBy(() -> transactions.execute(status -> faulty.capture(evidence)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("crash after first posting");
        assertThat(calls.get()).isEqualTo(1);
        assertCounts(0, 0, 0, 0);
        assertBalances(0);
        assertThat(jdbc.sql("SELECT status FROM io.inbox_event")
                        .query(String.class)
                        .single())
                .isEqualTo("RECEIVED");
        CaptureResult retried = transactions.execute(status -> service.capture(evidence));
        assertThat(retried).isEqualTo(CaptureResult.APPLIED);
        assertCounts(1, 1, 2, 1);
        assertBalances(10000);
    }

    @Test
    void ac_1_10_captureRequiresCallerTransaction() {
        assertThatThrownBy(() -> service.capture(evidence("evt_1", "ch_1", 10000L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caller's transaction");
        assertCounts(0, 0, 0, 0);
    }

    @Test
    void ac_2_5_duplicateFactDoesNotWriteAnything() {
        capture(evidence("evt_1", "ch_1", 10000L));
        var before = jdbc.sql("SELECT * FROM core.payment").query().singleRow();
        assertThat(capture(evidence("evt_2", "ch_1", 10000L))).isEqualTo(CaptureResult.DUPLICATE_FACT);
        assertCounts(1, 1, 2, 1);
        assertThat(jdbc.sql("SELECT * FROM core.payment").query().singleRow()).isEqualTo(before);
        assertThat(fixture.count("recon.issue")).isZero();
        assertBalances(10000);
    }

    @Test
    void ac_2_6_businessConflictPreservesS1AndReportsBothAmounts() {
        capture(evidence("evt_1", "ch_1", 10000L));
        assertThat(capture(evidence("evt_2", "ch_1", 9000L))).isEqualTo(CaptureResult.BUSINESS_CONFLICT);
        assertCounts(1, 1, 2, 1);
        assertBalances(10000);
        assertThat(jdbc.sql("""
                SELECT category, subject_type, subject_id, detected_by, expected->>'amount' AS expected_amount,
                    actual->>'amount' AS actual_amount FROM recon.issue
                """).query().singleRow())
                .containsEntry("category", "EVENT_BUSINESS_CONFLICT")
                .containsEntry("subject_type", "event")
                .containsEntry("subject_id", "evt_2")
                .containsEntry("detected_by", "INGESTION")
                .containsEntry("expected_amount", "10000")
                .containsEntry("actual_amount", "9000");
    }

    @Test
    void ac_2_7_concurrentDuplicatesStartTogetherFiftyTimes() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 50; i++) {
                var first = evidence("evt_a" + i, "ch_race" + i, 10000L);
                var second = evidence("evt_b" + i, "ch_race" + i, 10000L);
                inbox(first);
                inbox(second);
                CyclicBarrier start = new CyclicBarrier(2);
                var a = executor.submit(() -> transactions.execute(status -> {
                    await(start);
                    return service.capture(first);
                }));
                var b = executor.submit(() -> transactions.execute(status -> {
                    await(start);
                    return service.capture(second);
                }));
                assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(CaptureResult.APPLIED, CaptureResult.DUPLICATE_FACT);
                assertCounts(i + 1, i + 1, 2L * (i + 1), i + 1);
                assertBalances(10000L * (i + 1));
            }
        }
    }

    @Test
    void ac_2_7_secondTransactionWaitsForFundsLockBeforeReadingPayment() throws Exception {
        var first = evidence("evt_first", "ch_controlled", 10000L);
        var second = evidence("evt_second", "ch_controlled", 10000L);
        inbox(first);
        inbox(second);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        var paused = service(new Ledger() {
            @Override
            public void assertFundsLocked() {
                ledger.assertFundsLocked();
            }

            @Override
            public void lockFunds() {
                ledger.lockFunds();
                firstPid.set(
                        jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                holding.countDown();
                await(release);
            }

            @Override
            public PostedEntry post(EntryRequest request) {
                return ledger.post(request);
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> transactions.execute(status -> paused.capture(first)));
            try {
                await(holding);
                var b = executor.submit(() -> transactions.execute(status -> {
                    secondPid.set(jdbc.sql("SELECT pg_backend_pid()")
                            .query(Integer.class)
                            .single());
                    attempting.countDown();
                    return service.capture(second);
                }));
                await(attempting);
                JdbcClient observer = JdbcClient.create(db.superuser());
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                boolean waiting;
                do {
                    waiting = observer.sql("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                WHERE pid = :second AND wait_event_type = 'Lock'
                                    AND :first = ANY(pg_blocking_pids(pid))
                                    AND query LIKE '%ledger.funds_lock%FOR UPDATE%')
                            """)
                            .param("second", secondPid.get())
                            .param("first", firstPid.get())
                            .query(Boolean.class)
                            .single();
                } while (!waiting && System.nanoTime() < deadline);
                assertThat(waiting)
                        .as("second backend waits on the first backend's funds lock")
                        .isTrue();
                assertThat(b.isDone()).isFalse();
                assertCounts(0, 0, 0, 0);
                release.countDown();
                assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(CaptureResult.APPLIED);
                assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(CaptureResult.DUPLICATE_FACT);
            } finally {
                release.countDown();
            }
        }
        assertCounts(1, 1, 2, 1);
        assertBalances(10000);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
