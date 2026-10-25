package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
@Import(PaymentQueryTest.Controls.class)
class PaymentQueryTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Probe PROBE = new Probe();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Controls {
        @Bean
        static BeanPostProcessor observeQueryConnections() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("investigationDataSource")) return bean;
                    return new DelegatingDataSource((DataSource) bean) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            Connection actual = super.getConnection();
                            return (Connection) Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("prepareStatement")) {
                                            PROBE.observe(actual, (String) args[0]);
                                        }
                                        try {
                                            return method.invoke(actual, args);
                                        } catch (InvocationTargetException failure) {
                                            throw failure.getCause();
                                        }
                                    });
                        }
                    };
                }
            };
        }
    }

    record Observation(String user, String isolation, String readOnly, int backend, String snapshot) {}

    static class Probe {
        final List<Observation> observations = new CopyOnWriteArrayList<>();
        final AtomicBoolean pause = new AtomicBoolean();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        void observe(Connection connection, String sql) throws Exception {
            try (var statement = connection.createStatement();
                    var rs = statement.executeQuery("""
                            SELECT current_user, current_setting('transaction_isolation'),
                                current_setting('transaction_read_only'), pg_backend_pid(), pg_current_snapshot()::text
                            """)) {
                assertThat(rs.next()).isTrue();
                observations.add(new Observation(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
            }
            if ((sql.contains("FROM core.v_payment") || sql.contains("FROM core.v_event"))
                    && pause.compareAndSet(true, false)) {
                reached.countDown();
                assertThat(release.await(15, TimeUnit.SECONDS))
                        .as("writer released snapshot reader")
                        .isTrue();
            }
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InboxWorker worker;

    @Autowired
    @Qualifier("investigationDataSource")
    DataSource queryDataSource;

    @BeforeEach
    void resetProbe() {
        PROBE.observations.clear();
        PROBE.pause.set(false);
        PROBE.reached = new CountDownLatch(1);
        PROBE.release = new CountDownLatch(1);
    }

    @AfterAll
    static void close() {
        HTTP.close();
        DB.drop();
    }

    @Test
    void ac_1_14_s1ShowsPostingsAndAllThreeDeliveriesByEitherReference() throws Exception {
        byte[] first = event("evt_001", "ch_query_s1");
        deliver(first);
        deliver(first);
        deliver(event("evt_002", "ch_query_s1"));
        worker.processAvailable();
        JsonNode detail = ok("/api/payments/ch_query_s1");
        assertThat(detail.path("id").asString()).startsWith("pay_");
        assertThat(detail.path("processorPaymentRef").asString()).isEqualTo("ch_query_s1");
        assertThat(detail.path("amount").asLong()).isEqualTo(10000);
        assertThat(detail.path("currency").asString()).isEqualTo("CAD");
        assertThat(detail.path("status").asString()).isEqualTo("CAPTURED");
        assertThat(detail.path("expectedFee").asLong()).isEqualTo(320);
        for (String field : List.of("actualFee", "net", "batch", "bankDeposit"))
            assertThat(detail.path(field).isNull()).as(field).isTrue();
        JsonNode postings = detail.path("postings");
        assertThat(postings.size()).isEqualTo(2);
        assertThat(postings.get(0).path("accountCode").asInt()).isEqualTo(1101);
        assertThat(postings.get(0).path("accountName").asString()).isEqualTo("processor_receivable");
        assertThat(postings.get(0).path("side").asString()).isEqualTo("D");
        assertThat(postings.get(1).path("accountCode").asInt()).isEqualTo(2101);
        assertThat(postings.get(1).path("accountName").asString()).isEqualTo("merchant_pending");
        assertThat(postings.get(1).path("side").asString()).isEqualTo("C");
        assertThat(postings.get(0).path("entryId")).isEqualTo(postings.get(1).path("entryId"));
        for (int i = 0; i < 2; i++) {
            assertThat(postings.get(i).path("lineNo").asInt()).isEqualTo(i + 1);
            assertThat(postings.get(i).path("amount").asLong()).isEqualTo(10000);
            assertThat(postings.get(i).path("entryType").asString()).isEqualTo("PAYMENT_CAPTURED");
        }
        JsonNode deliveries = detail.path("deliveries");
        assertThat(deliveries.size()).isEqualTo(3);
        assertDelivery(deliveries.get(0), "evt_001", "NEW", "APPLIED");
        assertDelivery(deliveries.get(1), "evt_001", "DUPLICATE", "APPLIED");
        assertDelivery(deliveries.get(2), "evt_002", "NEW", "DUPLICATE_FACT");
        assertThat(detail.path("asOf").path("seq").asLong()).isPositive();
        Instant.parse(detail.path("asOf").path("at").asString());
        assertQueryCredentials(5);
        JsonNode local = ok("/api/payments/" + detail.path("id").asString());
        for (String field : List.of(
                "id", "processorPaymentRef", "amount", "currency", "status", "expectedFee", "postings", "deliveries")) {
            assertThat(local.path(field)).isEqualTo(detail.path(field));
        }
        assertThat(local.path("asOf").path("seq")).isEqualTo(detail.path("asOf").path("seq"));
        assertThat(jdbc.sql("SELECT count(*) FROM io.inbox_event WHERE event_id IN ('evt_001', 'evt_002')")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void ac_5_15_eventSummaryIncludesAllDeliveriesWithoutSensitiveFields() throws Exception {
        byte[] original = event("evt_summary", "ch_summary");
        deliver(original);
        JsonNode pending = ok("/api/events/evt_summary");
        assertThat(pending.path("status").asString()).isEqualTo("RECEIVED");
        assertThat(pending.path("result").isNull()).isTrue();
        deliver(original);
        deliver(event("evt_summary", "ch_conflicting_secret"));
        worker.processAvailable();
        PROBE.observations.clear();
        JsonNode detail = ok("/api/events/evt_summary");
        assertQueryCredentials(3);
        assertThat(detail.propertyNames())
                .containsExactlyInAnyOrder(
                        "eventId",
                        "eventType",
                        "apiVersion",
                        "livemode",
                        "occurredAt",
                        "receivedAt",
                        "status",
                        "result",
                        "subject",
                        "subjectId",
                        "deliveries",
                        "asOf");
        assertThat(detail.path("eventId").asString()).isEqualTo("evt_summary");
        assertThat(detail.path("eventType").asString()).isEqualTo("charge.succeeded");
        assertThat(detail.path("apiVersion").asString()).isEqualTo("2026-09-30.endive");
        assertThat(detail.path("livemode").asBoolean()).isFalse();
        assertThat(Instant.parse(detail.path("occurredAt").asString())).isEqualTo(Instant.ofEpochSecond(1792591200));
        assertThat(detail.path("receivedAt")).isEqualTo(pending.path("receivedAt"));
        assertThat(detail.path("status").asString()).isEqualTo("PROCESSED");
        assertThat(detail.path("result").asString()).isEqualTo("APPLIED");
        assertThat(detail.path("subject").asString()).isEqualTo("payment");
        assertThat(detail.path("subjectId").asString()).isEqualTo("ch_summary");
        JsonNode deliveries = detail.path("deliveries");
        assertThat(deliveries.size()).isEqualTo(3);
        assertThat(deliveries.get(0).path("outcome").asString()).isEqualTo("NEW");
        assertThat(deliveries.get(1).path("outcome").asString()).isEqualTo("DUPLICATE");
        assertThat(deliveries.get(2).path("outcome").asString()).isEqualTo("CONFLICT");
        long previous = 0;
        for (JsonNode delivery : deliveries) {
            assertThat(delivery.propertyNames())
                    .containsExactlyInAnyOrder(
                            "id", "eventId", "eventType", "outcome", "receivedAt", "status", "result", "subject");
            assertThat(delivery.path("id").asLong()).isGreaterThan(previous);
            previous = delivery.path("id").asLong();
            assertThat(delivery.path("subject").asString()).isEqualTo("payment");
            assertThat(delivery.path("result").asString()).isEqualTo("APPLIED");
        }
        assertThat(detail.toString())
                .doesNotContain(
                        "payload",
                        "signature",
                        "sha256",
                        "signedAt",
                        "signed_at",
                        "conflictPayload",
                        "ch_conflicting_secret");
        assertThat(get("/api/events/evt_missing").statusCode()).isEqualTo(404);
        assertThat(get("/api/events/evt_%27%20OR%201=1--").statusCode()).isEqualTo(404);
    }

    @Test
    void ac_5_15_eventStatusAndDeliveriesShareSnapshotAcrossConcurrentCommit() throws Exception {
        byte[] body = event("evt_summary_snapshot", "ch_summary_snapshot");
        deliver(body);
        PROBE.pause.set(true);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> ok("/api/events/evt_summary_snapshot"));
            try {
                assertThat(PROBE.reached.await(15, TimeUnit.SECONDS)).isTrue();
                deliver(body);
                worker.processAvailable();
            } finally {
                PROBE.release.countDown();
            }
            JsonNode before = pending.get(15, TimeUnit.SECONDS);
            assertThat(before.path("status").asString()).isEqualTo("RECEIVED");
            assertThat(before.path("result").isNull()).isTrue();
            assertThat(before.path("deliveries").size()).isEqualTo(1);
            assertThat(before.path("deliveries").get(0).path("status").asString())
                    .isEqualTo("RECEIVED");
            assertQueryCredentials(3);
        }
        JsonNode after = ok("/api/events/evt_summary_snapshot");
        assertThat(after.path("status").asString()).isEqualTo("PROCESSED");
        assertThat(after.path("result").asString()).isEqualTo("APPLIED");
        assertThat(after.path("deliveries").size()).isEqualTo(2);
        for (JsonNode delivery : after.path("deliveries"))
            assertThat(delivery.path("result").asString()).isEqualTo("APPLIED");
    }

    @Test
    void ac_5_15_unsupportedEventStillHasSummaryWithoutInventingAnAssociation() throws Exception {
        byte[] body = new String(event("evt_summary_other", "obj_other"), StandardCharsets.UTF_8)
                .replace("charge.succeeded", "customer.created")
                .getBytes(StandardCharsets.UTF_8);
        deliver(body);
        worker.processAvailable();
        JsonNode detail = ok("/api/events/evt_summary_other");
        assertThat(detail.path("status").asString()).isEqualTo("IGNORED");
        assertThat(detail.path("result").isNull()).isTrue();
        assertThat(detail.path("subject").isNull()).isTrue();
        assertThat(detail.path("subjectId").isNull()).isTrue();
        assertThat(detail.path("deliveries").get(0).path("subject").isNull()).isTrue();
    }

    @Test
    void ac_1_13_queryDataSourceReallyUsesReadonlyAndAppStillWrites() {
        assertThat(JdbcClient.create(queryDataSource)
                        .sql("SELECT current_user")
                        .query(String.class)
                        .single())
                .isEqualTo("readonly");
        // Outside READ ONLY: the role itself must reject this, with 42501 rather than 25006.
        Sql.assertRejected("42501", queryDataSource, "UPDATE core.payment SET status = 'VOIDED'");
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("app");
    }

    @Test
    void ac_1_14_missingPaymentReturns404() throws Exception {
        assertThat(get("/api/payments/ch_missing").statusCode()).isEqualTo(404);
        assertThat(get("/api/payments/pay_missing").statusCode()).isEqualTo(404);
        assertThat(get("/api/payments/ch_%27%20OR%201=1--").statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "2147483648", "abc", "1.5"})
    void ac_1_14_invalidLimitsReturn400(String limit) throws Exception {
        assertThat(get("/api/payments?limit=" + limit).statusCode()).isEqualTo(400);
    }

    @Test
    void ac_1_14_recentPaymentsHaveBoundedDeterministicOrderingAndSnapshot() throws Exception {
        for (int i = 0; i < 21; i++) deliver(event("evt_recent_" + i, "ch_recent_" + i));
        while (worker.processAvailable() != 0) {
            /* Drain bounded test inputs. */
        }
        var expected = jdbc.sql("SELECT id FROM core.payment ORDER BY recorded_at DESC, id DESC LIMIT 20")
                .query(String.class)
                .list();
        JsonNode recent = ok("/api/payments");
        assertThat(recent.path("payments").size()).isEqualTo(20);
        for (int i = 0; i < 20; i++)
            assertThat(recent.path("payments").get(i).path("id").asString()).isEqualTo(expected.get(i));
        assertQueryCredentials(2);
        assertThat(recent.path("asOf").path("seq").asLong())
                .isEqualTo(jdbc.sql("SELECT max(seq) FROM ledger.journal_entry")
                        .query(Long.class)
                        .single());
        assertThat(ok("/api/payments?limit=1").path("payments").size()).isEqualTo(1);
        assertThat(ok("/api/payments?limit=100").path("payments").size()).isBetween(21, 100);
    }

    @Test
    void ac_1_14_detailKeepsOneSnapshotAcrossConcurrentCommits() throws Exception {
        deliver(event("evt_snapshot_1", "ch_snapshot"));
        worker.processAvailable();
        long seq = jdbc.sql("SELECT max(seq) FROM ledger.journal_entry")
                .query(Long.class)
                .single();
        PROBE.pause.set(true);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> ok("/api/payments/ch_snapshot"));
            try {
                assertThat(PROBE.reached.await(15, TimeUnit.SECONDS))
                        .as("reader established snapshot")
                        .isTrue();
                deliver(event("evt_snapshot_2", "ch_snapshot"));
                deliver(event("evt_snapshot_other", "ch_snapshot_other"));
                worker.processAvailable();
            } finally {
                PROBE.release.countDown();
            }
            JsonNode before = pending.get(15, TimeUnit.SECONDS);
            assertThat(before.path("deliveries").size()).isEqualTo(1);
            assertThat(before.path("postings").size()).isEqualTo(2);
            assertThat(before.path("asOf").path("seq").asLong()).isEqualTo(seq);
            assertQueryCredentials(5);
        }
        JsonNode after = ok("/api/payments/ch_snapshot");
        assertThat(after.path("deliveries").size()).isEqualTo(2);
        assertThat(after.path("asOf").path("seq").asLong()).isGreaterThan(seq);
    }

    static void assertQueryCredentials(int statements) {
        assertThat(PROBE.observations).hasSize(statements);
        assertThat(PROBE.observations.stream().map(Observation::user)).containsOnly("readonly");
        assertThat(PROBE.observations.stream().map(Observation::isolation)).containsOnly("repeatable read");
        assertThat(PROBE.observations.stream().map(Observation::readOnly)).containsOnly("on");
        assertThat(PROBE.observations.stream().map(Observation::backend).distinct())
                .hasSize(1);
        assertThat(PROBE.observations.stream().map(Observation::snapshot).distinct())
                .hasSize(1);
    }

    @Test
    void ac_1_14_emptyLedgerHasZeroSnapshotSequence() {
        TestDatabase.Handle empty = TestDatabase.freshMigrated();
        try {
            var snapshot = new QuerySnapshot(empty.readonly(), java.time.Clock.systemUTC());
            var workbench = new WorkbenchQueries(snapshot, mapper);
            PaymentQueries.PaymentList result =
                    new PaymentQueries(snapshot, workbench, new RefundQueries(snapshot, workbench, mapper)).recent(20);
            assertThat(result.payments()).isEmpty();
            assertThat(result.asOf().seq()).isZero();
            assertThat(result.asOf().at()).isBeforeOrEqualTo(Instant.now());
        } finally {
            empty.drop();
        }
    }

    @Test
    void ac_1_14_equalReceiptTimesUseDeliveryIdAndPendingResultIsNull() throws Exception {
        deliver(event("evt_order_original", "ch_query_order"));
        worker.processAvailable();
        deliver(event("evt_order_pending", "ch_query_order"));
        // Two additional accepted deliveries at the same receipt time, intentionally opposite event-id order.
        jdbc.sql("""
                INSERT INTO io.webhook_delivery (event_id, outcome, payload_sha256, signed_at, received_at, recorded_at)
                SELECT event_id, 'DUPLICATE', payload_sha256, signed_at, '2030-01-01Z', recorded_at
                FROM io.inbox_event WHERE event_id = 'evt_order_pending'
                """).update();
        jdbc.sql("""
                INSERT INTO io.webhook_delivery (event_id, outcome, payload_sha256, signed_at, received_at, recorded_at)
                SELECT event_id, 'DUPLICATE', payload_sha256, signed_at, '2030-01-01Z', recorded_at
                FROM io.inbox_event WHERE event_id = 'evt_order_original'
                """).update();
        JsonNode deliveries = ok("/api/payments/ch_query_order").path("deliveries");
        assertThat(deliveries.size()).isEqualTo(4);
        assertThat(deliveries.get(2).path("eventId").asString()).isEqualTo("evt_order_pending");
        assertThat(deliveries.get(2).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(deliveries.get(2).path("result").isNull()).isTrue();
        assertThat(deliveries.get(3).path("eventId").asString()).isEqualTo("evt_order_original");
        assertThat(deliveries.get(2).path("receivedAt"))
                .isEqualTo(deliveries.get(3).path("receivedAt"));
    }

    private static void assertDelivery(JsonNode delivery, String id, String outcome, String result) {
        assertThat(delivery.path("eventId").asString()).isEqualTo(id);
        assertThat(delivery.path("eventType").asString()).isEqualTo("charge.succeeded");
        assertThat(delivery.path("outcome").asString()).isEqualTo(outcome);
        assertThat(delivery.path("status").asString()).isEqualTo("PROCESSED");
        assertThat(delivery.path("result").asString()).isEqualTo(result);
        Instant.parse(delivery.path("receivedAt").asString());
    }

    private JsonNode ok(String path) throws Exception {
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void deliver(byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .header("Content-Type", "application/json")
                .header(
                        "Stripe-Signature",
                        StripeSignatures.sign(body, Instant.now().getEpochSecond()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        assertThat(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
    }

    private static byte[] event(String id, String charge) {
        return """
                {"id":"%s","type":"charge.succeeded","api_version":"2026-09-30.endive",
                 "livemode":false,"created":1792591200,"data":{"object":{
                 "id":"%s","payment_intent":"pi_query","amount":10000,"amount_captured":10000,
                 "currency":"cad","captured":true,"status":"succeeded","livemode":false,"created":1792591100}}}
                """.formatted(id, charge).getBytes(StandardCharsets.UTF_8);
    }
}
