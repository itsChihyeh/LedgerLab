package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
@Import(InboxWorkerTest.Controls.class)
class InboxWorkerTest {
    private static final Instant NOW = Instant.parse("2026-10-21T14:00:00Z");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicInteger IDS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Controls {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @AfterAll
    static void close() {
        HTTP.close();
        DB.drop();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    PaymentCapture capture;

    @Autowired
    IngestionIssues issues;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ChargeParser parser;

    @Autowired
    LedgerAudit audit;

    @Autowired
    InboxWorker configuredWorker;

    private long paymentsBefore;
    private long postingsBefore;
    private long issuesBefore;
    private long balanceBefore;

    @BeforeEach
    void baseline() {
        clock.set(NOW);
        paymentsBefore = count("core.payment");
        postingsBefore = count("ledger.posting");
        issuesBefore = count("recon.issue");
        balanceBefore = jdbc.sql("SELECT balance FROM ledger.account WHERE code=1101")
                .query(Long.class)
                .single();
    }

    @AfterEach
    void invariants() {
        assertThat(audit.check()).isEmpty();
    }

    private InboxWorker worker(String owner, WorkerHooks hooks) {
        return new InboxWorker(
                jdbc,
                manager,
                capture,
                issues,
                mapper,
                parser,
                clock,
                new WorkerProperties(owner, 1, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(4), 2),
                hooks);
    }

    private InboxWorker worker() {
        return worker("worker-b", new WorkerHooks() {});
    }

    private ObjectNode event() {
        int id = IDS.incrementAndGet();
        return (ObjectNode) mapper.readTree("""
                {"id":"evt_worker_%d","type":"charge.succeeded","api_version":"2026-09-30.endive",
                 "livemode":false,"created":1792591200,"data":{"object":{
                 "id":"ch_worker_%d","payment_intent":"pi_test","amount":10000,"amount_captured":10000,
                 "currency":"cad","captured":true,"status":"succeeded","livemode":false,"created":1792591100}}}
                """.formatted(id, id));
    }

    private static ObjectNode charge(ObjectNode event) {
        return (ObjectNode) event.path("data").path("object");
    }

    private static String id(ObjectNode event) {
        return event.path("id").asString();
    }

    private byte[] deliver(ObjectNode event) throws Exception {
        byte[] body = mapper.writeValueAsBytes(event);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .header("Content-Type", "application/json")
                .header(
                        "Stripe-Signature",
                        StripeSignatures.sign(body, clock.instant().getEpochSecond()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                .isEqualTo(200);
        return body;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private Map<String, @Nullable Object> row(ObjectNode event) {
        return jdbc.sql("SELECT * FROM io.inbox_event WHERE event_id=:id")
                .param("id", id(event))
                .query()
                .singleRow();
    }

    private void outcome(ObjectNode event, String status, @Nullable String result) {
        assertThat(row(event))
                .containsEntry("status", status)
                .containsEntry("result", result)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_until", null)
                .containsEntry("processed_at", Timestamp.from(clock.instant()));
    }

    private void balances(long added) {
        assertThat(jdbc.sql("SELECT code, balance FROM ledger.account ORDER BY code")
                        .query()
                        .listOfRows())
                .containsExactly(
                        Map.of("code", 1101, "balance", balanceBefore + added),
                        Map.of("code", 1102, "balance", 0L),
                        Map.of("code", 1103, "balance", 0L),
                        Map.of("code", 2101, "balance", balanceBefore + added),
                        Map.of("code", 2102, "balance", 0L),
                        Map.of("code", 2103, "balance", 0L),
                        Map.of("code", 2104, "balance", 0L),
                        Map.of("code", 2105, "balance", 0L));
    }

    private void money(long payments) {
        assertThat(count("core.payment")).isEqualTo(paymentsBefore + payments);
        assertThat(count("ledger.journal_entry")).isEqualTo(paymentsBefore + payments);
        assertThat(count("ledger.posting")).isEqualTo(postingsBefore + 2 * payments);
        assertThat(count("core.payment_transition")).isEqualTo(paymentsBefore + payments);
        balances(10000 * payments);
    }

    private void issue(ObjectNode event, String category) {
        assertThat(jdbc.sql("SELECT category, detected_by FROM recon.issue WHERE subject_id=:id")
                        .param("id", id(event))
                        .query()
                        .listOfRows())
                .containsExactly(Map.of("category", category, "detected_by", "INGESTION"));
    }

    @Test
    void ac_1_1_ac_1_4a_captureMatchesEveryS1CellAndRetainsReceivedTime() throws Exception {
        ObjectNode event = event();
        deliver(event);
        clock.set(NOW.plusSeconds(10));
        assertThat(configuredWorker.processAvailable()).isEqualTo(1);
        outcome(event, "PROCESSED", "APPLIED");
        money(1);
        assertThat(count("recon.issue")).isEqualTo(issuesBefore);
        assertThat(jdbc.sql(
                                "SELECT amount, expected_fee, received_at, occurred_at, processor_intent_ref FROM core.payment WHERE source_event_id=:id")
                        .param("id", id(event))
                        .query()
                        .singleRow())
                .containsEntry("amount", 10000L)
                .containsEntry("expected_fee", 320L)
                .containsEntry("received_at", Timestamp.from(NOW))
                .containsEntry("occurred_at", Timestamp.from(Instant.ofEpochSecond(1792591100)))
                .containsEntry("processor_intent_ref", "pi_test");
        assertThat(jdbc.sql(
                                "SELECT data_type FROM information_schema.columns WHERE table_schema='core' AND table_name='payment' AND column_name='amount'")
                        .query(String.class)
                        .single())
                .isEqualTo("bigint");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "100.5",
                "\"100.00\"",
                "0",
                "-500",
                "49",
                "100000000",
                "null",
                "true",
                "{}",
                "9223372036854775808",
                "missing"
            })
    void ac_1_2_invalidAmountKeepsRawEvidenceWithoutMoneyWrites(String amount) throws Exception {
        ObjectNode event = event();
        if (amount.equals("missing")) {
            charge(event).remove("amount");
        } else {
            charge(event).set("amount", mapper.readTree(amount));
        }
        byte[] body = deliver(event);
        worker().processAvailable();
        outcome(event, "REJECTED", "INVALID_AMOUNT");
        assertThat((byte[]) row(event).get("payload")).isEqualTo(body);
        money(0);
        issue(event, "UNSUPPORTED_EVENT");
    }

    @ParameterizedTest
    @CsvSource({
        "currency,usd,UNSUPPORTED_CURRENCY",
        "captured,false,UNSUPPORTED_CAPTURE",
        "amount_captured,9999,UNSUPPORTED_CAPTURE",
        "livemode,true,LIVEMODE"
    })
    void ac_1_3_ac_1_12_ac_8_2_admissionRejectsAndOpensOneIssue(String field, String value, String result)
            throws Exception {
        ObjectNode event = event();
        if (field.equals("currency")) {
            charge(event).put(field, value);
        } else {
            charge(event).set(field, mapper.readTree(value));
        }
        if (field.equals("livemode")) {
            event.put("livemode", true);
        }
        byte[] body = deliver(event);
        worker().processAvailable();
        outcome(event, "REJECTED", result);
        issue(event, result.equals("LIVEMODE") ? "LIVEMODE_EVENT" : "UNSUPPORTED_EVENT");
        assertThat(count("recon.issue")).isEqualTo(issuesBefore + 1);
        assertThat((byte[]) row(event).get("payload")).isEqualTo(body);
        money(0);
    }

    @ParameterizedTest
    @CsvSource({
        "customer.created,false,IGNORED,",
        "refund.updated,false,IGNORED,",
        "payout.created,false,IGNORED,",
        "charge.dispute.created,false,REJECTED,UNSUPPORTED_TYPE",
        "customer.created,true,REJECTED,LIVEMODE",
        "charge.dispute.created,true,REJECTED,LIVEMODE"
    })
    void ac_8_2_typeRoutingChecksLiveBeforeUnsupportedType(
            String type, boolean live, String status, @Nullable String result) throws Exception {
        ObjectNode event = event();
        event.put("type", type).put("livemode", live);
        deliver(event);
        worker().processAvailable();
        outcome(event, status, result);
        money(0);
        if (result != null) {
            issue(event, live ? "LIVEMODE_EVENT" : "UNSUPPORTED_EVENT");
        }
        assertThat(count("recon.issue")).isEqualTo(issuesBefore + (result == null ? 0 : 1));
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true"})
    void ac_8_2_eitherEnvelopeOrChargeLiveFlagTakesPriorityOverInvalidAmount(boolean envelopeLive, boolean chargeLive)
            throws Exception {
        ObjectNode event = event();
        event.put("livemode", envelopeLive);
        charge(event).put("livemode", chargeLive).put("amount", 0);
        deliver(event);
        worker().processAvailable();
        outcome(event, "REJECTED", "LIVEMODE");
        issue(event, "LIVEMODE_EVENT");
        assertThat(count("recon.issue")).isEqualTo(issuesBefore + 1);
        money(0);
    }

    @ParameterizedTest
    @CsvSource({
        "captured,UNSUPPORTED_CAPTURE",
        "amount_captured,UNSUPPORTED_CAPTURE",
        "status,UNSUPPORTED_CAPTURE",
        "currency,UNSUPPORTED_CURRENCY"
    })
    void ac_1_12_missingCaptureFieldsArePassedToAdmission(String field, String result) throws Exception {
        ObjectNode event = event();
        charge(event).remove(field);
        charge(event).remove("payment_intent");
        deliver(event);
        worker().processAvailable();
        outcome(event, "REJECTED", result);
        issue(event, "UNSUPPORTED_EVENT");
        money(0);
    }

    @Test
    void ac_2_3_ac_2_5_ac_2_6_ac_4_3_duplicateConflictAndResignedReplayNeverMoveMoneyTwice() throws Exception {
        ObjectNode first = event();
        deliver(first);
        worker().processAvailable();
        var original = row(first);
        deliver(first);
        assertThat(row(first)).usingRecursiveComparison().isEqualTo(original);
        ObjectNode duplicate = first.deepCopy();
        duplicate.put("id", id(event()));
        deliver(duplicate);
        worker().processAvailable();
        outcome(duplicate, "PROCESSED", "DUPLICATE_FACT");
        ObjectNode conflict = first.deepCopy();
        conflict.put("id", id(event()));
        charge(conflict).put("amount", 9000).put("amount_captured", 9000);
        deliver(conflict);
        worker().processAvailable();
        outcome(conflict, "REJECTED", "BUSINESS_CONFLICT");
        issue(conflict, "EVENT_BUSINESS_CONFLICT");
        long inbox = count("io.inbox_event");
        clock.set(NOW.plusSeconds(60));
        for (ObjectNode replay : List.of(first, first, duplicate, conflict)) {
            var before = row(replay);
            deliver(replay);
            assertThat(row(replay)).usingRecursiveComparison().isEqualTo(before);
        }
        assertThat(worker().processAvailable()).isZero();
        assertThat(count("io.inbox_event")).isEqualTo(inbox);
        money(1);
    }

    @Test
    void ac_2_7_twoWorkerInstancesClaimDistinctEventsTogetherFiftyTimes() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 50; i++) {
                ObjectNode first = event();
                ObjectNode second = first.deepCopy();
                second.put("id", id(event()));
                deliver(first);
                deliver(second);
                CyclicBarrier claimed = new CyclicBarrier(2);
                WorkerHooks hooks = new WorkerHooks() {
                    @Override
                    public void afterClaim(String id) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isFalse();
                        try {
                            claimed.await(15, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    }
                };
                InboxWorker a = worker("worker-a", hooks);
                InboxWorker b = worker("worker-b", hooks);
                var fa = executor.submit(a::processAvailable);
                var fb = executor.submit(b::processAvailable);
                assertThat(fa.get(20, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(fb.get(20, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(List.of(row(first).get("result"), row(second).get("result")))
                        .containsExactlyInAnyOrder("APPLIED", "DUPLICATE_FACT");
                assertThat(row(first)).containsEntry("lease_epoch", 1L);
                assertThat(row(second)).containsEntry("lease_epoch", 1L);
                money(i + 1);
                assertThat(audit.check()).isEmpty();
            }
        }
    }

    @Test
    void ac_4_5_unstartedWorkerLeavesDurableInboxForNewWorker() throws Exception {
        ObjectNode event = event();
        deliver(event);
        assertThat(row(event)).containsEntry("status", "RECEIVED").containsEntry("lease_epoch", 0L);
        money(0);
        InboxWorker restarted = worker("restarted-process", new WorkerHooks() {});
        restarted.processAvailable();
        outcome(event, "PROCESSED", "APPLIED");
        assertThat(restarted.processAvailable()).isZero();
        money(1);
    }

    @Test
    void ac_4_6_crashRollsBackBusinessWithoutRecordingFailureThenLeaseExpires() throws Exception {
        ObjectNode event = event();
        deliver(event);
        InboxWorker doomed = worker("worker-a", new WorkerHooks() {
            @Override
            public void afterBusiness(String id) {
                throw new SimulatedProcessDeath();
            }
        });
        assertThatThrownBy(doomed::processAvailable).isInstanceOf(SimulatedProcessDeath.class);
        assertThat(row(event))
                .containsEntry("status", "RECEIVED")
                .containsEntry("attempts", 0)
                .containsEntry("lease_epoch", 1L)
                .containsEntry("lease_owner", "worker-a");
        money(0);
        clock.set(NOW.plusSeconds(30));
        assertThat(worker().processAvailable()).isZero();
        clock.set(NOW.plusSeconds(31));
        worker().processAvailable();
        outcome(event, "PROCESSED", "APPLIED");
        assertThat(row(event)).containsEntry("lease_epoch", 2L);
        money(1);
    }

    @Test
    void ac_4_6_2a_oldWorkerResumesAfterNewWorkerCommitsAndCannotOverwriteApplied() throws Exception {
        takeover(false);
    }

    @Test
    void ac_4_6_2b_newWorkerWaitsForFundsLockUntilOldEpochRollsBack() throws Exception {
        takeover(true);
    }

    private void takeover(boolean insideBusiness) throws Exception {
        ObjectNode event = event();
        deliver(event);
        CountDownLatch stopped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger();
        InboxWorker a = worker("worker-a", new WorkerHooks() {
            @Override
            public void afterClaim(String id) {
                if (!insideBusiness) {
                    stopped.countDown();
                    await(release);
                }
            }

            @Override
            public void afterBusiness(String id) {
                if (insideBusiness) {
                    pid.set(jdbc.sql("SELECT pg_backend_pid()")
                            .query(Integer.class)
                            .single());
                    stopped.countDown();
                    await(release);
                }
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var fa = executor.submit(a::processAvailable);
            try {
                await(stopped);
                clock.set(NOW.plusSeconds(31));
                var fb = executor.submit(worker()::processAvailable);
                if (insideBusiness) {
                    JdbcClient observer = JdbcClient.create(DB.superuser());
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    boolean waiting;
                    do {
                        waiting = observer.sql("""
                                SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database()
                                AND wait_event_type='Lock' AND :pid = ANY(pg_blocking_pids(pid))
                                AND query LIKE '%ledger.funds_lock%FOR UPDATE%')
                                """)
                                .param("pid", pid.get())
                                .query(Boolean.class)
                                .single();
                    } while (!waiting && System.nanoTime() < deadline);
                    assertThat(waiting)
                            .as("B waits for A's funds lock after committing the new epoch")
                            .isTrue();
                    assertThat(row(event)).containsEntry("lease_epoch", 2L).containsEntry("lease_owner", "worker-b");
                    assertThat(fb.isDone()).isFalse();
                    release.countDown();
                }
                assertThat(fb.get(20, TimeUnit.SECONDS)).isEqualTo(1);
                var committed = row(event);
                release.countDown();
                assertThat(fa.get(20, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(row(event)).usingRecursiveComparison().isEqualTo(committed);
            } finally {
                release.countDown();
            }
        }
        outcome(event, "PROCESSED", "APPLIED");
        assertThat(row(event)).containsEntry("lease_epoch", 2L).containsEntry("attempts", 0);
        money(1);
        assertThat(count("recon.issue")).isEqualTo(issuesBefore);
    }

    @Test
    void ac_4_6_failuresRollbackAndBackOffUntilDeadWithoutIssue() throws Exception {
        ObjectNode event = event();
        deliver(event);
        WorkerHooks fail = new WorkerHooks() {
            @Override
            public void afterBusiness(String id) {
                throw new IllegalStateException("x".repeat(2500));
            }
        };
        InboxWorker faulty = new InboxWorker(
                jdbc,
                manager,
                capture,
                issues,
                mapper,
                parser,
                clock,
                new WorkerProperties(
                        "faulty", 1, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(3), 5),
                fail);
        for (int attempts = 1; attempts <= 6; attempts++) {
            Instant failedAt = clock.instant();
            assertThat(faulty.processAvailable()).isEqualTo(1);
            long delay = Math.min(1L << (attempts - 1), 3);
            assertThat(row(event))
                    .containsEntry("attempts", attempts)
                    .containsEntry("status", attempts > 5 ? "DEAD" : "RECEIVED")
                    .containsEntry("next_attempt_at", Timestamp.from(failedAt.plusSeconds(delay)))
                    .containsEntry("lease_owner", null)
                    .containsEntry("lease_until", null);
            assertThat((String) row(event).get("last_error")).hasSize(2000);
            assertThat(faulty.processAvailable()).isZero();
            money(0);
            clock.set(failedAt.plusSeconds(delay));
        }
        assertThat(worker().processAvailable()).isZero();
        assertThat(count("recon.issue")).isEqualTo(issuesBefore);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class SimulatedProcessDeath extends Error {}
}
