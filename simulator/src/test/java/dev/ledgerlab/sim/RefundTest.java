package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RefundTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "sk_test_synthetic_refunds_only";
    private static final String SECRET = "synthetic-refund-signing-only";
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static PostgreSQLContainer database;
    private static HttpServer receiver;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static String base;
    private static JdbcClient jdbc;
    private static volatile CountDownLatch arrived = new CountDownLatch(0);
    private static volatile CountDownLatch release = new CountDownLatch(0);

    @BeforeAll
    static void start() throws Exception {
        database = TestDatabase.start();
        receiver = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED.add(new Received(body, exchange.getRequestHeaders().getFirst("Stripe-Signature")));
            if (JSON.readTree(body).path("type").asText().startsWith("refund.")) {
                arrived.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Hook barrier timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                        "--sim.stripe.api-key=" + KEY,
                        "--sim.stripe.webhook-secret=" + SECRET,
                        "--sim.stripe.webhook-url=http://127.0.0.1:${local.server.port}/relay/stripe",
                        "--sim.relay.target-url=http://127.0.0.1:"
                                + receiver.getAddress().getPort() + "/hook",
                        "--sim.clock.start=2026-10-23T12:00:00Z",
                        "--sim.seed=42",
                        "--spring.main.lazy-initialization=true");
        base = "http://127.0.0.1:"
                + java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                        .getPort();
        client = HttpClient.newHttpClient();
        jdbc = JdbcClient.create(TestDatabase.role(database.getJdbcUrl(), "simulator"));
    }

    @AfterAll
    static void stop() {
        release.countDown();
        if (client != null) client.close();
        if (app != null) app.close();
        if (receiver != null) receiver.stop(0);
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        get("/sim/clock");
        release.countDown();
        arrived = new CountDownLatch(0);
        release = new CountDownLatch(0);
        RECEIVED.clear();
        jdbc.sql("truncate sim_processor.charge, sim_processor.event, sim_processor.webhook_delivery, "
                        + "sim_processor.balance_transaction, sim_processor.payout, sim_processor.fault_plan cascade")
                .update();
        jdbc.sql("truncate sim_processor.refund_faults, sim_processor.idempotency_keys cascade")
                .update();
    }

    @Test
    void ac_3_1_availableFundsExecuteRefundAndPayoutIncludesItsDebit() throws Exception {
        String charge = charge(5000);
        var refund = refund(charge, 2000, "rfd_one");
        assertThat(refund.path("status").asText()).isEqualTo("succeeded");
        var line = jdbc.sql("select type, amount, fee, net, source from sim_processor.balance_transaction where id=?")
                .param(refund.path("balance_transaction").asText())
                .query()
                .singleRow();
        assertThat(line)
                .containsEntry("type", "refund")
                .containsEntry("amount", -2000L)
                .containsEntry("fee", 0L)
                .containsEntry("net", -2000L)
                .containsEntry("source", refund.path("id").asText());
        var payout = post("/sim/payouts", "{}").path("payout");
        assertThat(payout.path("amount").asLong()).isEqualTo(2825);
        var lines = get("/v1/balance_transactions?payout=" + payout.path("id").asText());
        assertThat(lines.path("data")).anySatisfy(row -> {
            assertThat(row.path("type").asText()).isEqualTo("refund");
            assertThat(row.path("net").asLong()).isEqualTo(-2000);
        });
        assertThat(get("/v1/refunds/" + refund.path("id").asText())).isEqualTo(refund);
    }

    @Test
    void ac_3_18_sampleAPendingWaitsForP4AvailableFunds() throws Exception {
        String original = charge(10000);
        post("/sim/payouts", "{}");
        var pending = refund(original, 3000, "rfd_r2");
        assertThat(pending.path("status").asText()).isEqualTo("pending");
        assertThat(pending.path("balance_transaction").isNull()).isTrue();
        long available = Instant.parse(get("/sim/clock").path("now").asText())
                .plusSeconds(60)
                .getEpochSecond();
        post("/sim/charges", "{\"amount\":5000,\"available_on\":" + available + "}");
        assertThat(get("/v1/refunds/" + pending.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("pending");
        post("/sim/clock/advance", "{\"seconds\":60}");
        assertThat(get("/v1/refunds/" + pending.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("succeeded");
        assertThat(events("refund.updated")).hasSize(1);
        assertThat(events("refund.updated")
                        .getFirst()
                        .path("data")
                        .path("object")
                        .path("amount")
                        .asLong())
                .isEqualTo(3000);
        assertThat(post("/sim/payouts", "{}").path("payout").path("amount").asLong())
                .isEqualTo(1825);
    }

    @Test
    void ac_8_7_originalResponseSurvivesStateChangeAndPurgeAllowsDuplicate() throws Exception {
        String charge = charge(10000);
        post("/sim/refunds/faults", "{\"force_pending\":true}");
        var pending = refund(charge, 100, "rfd_original");
        post("/sim/refunds/" + pending.path("id").asText() + "/succeed", "{}");
        assertThat(refund(charge, 100, "rfd_original")).isEqualTo(pending);
        var mismatch = submit(charge, 101, "rfd_original");
        assertThat(mismatch.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(mismatch.body()).path("error").path("type").asText())
                .isEqualTo("idempotency_error");
        post("/sim/idempotency/purge", "{}");
        assertThat(refund(charge, 100, "rfd_original")).isEqualTo(pending);
        post("/sim/clock/advance", "{\"seconds\":86401}");
        post("/sim/idempotency/purge", "{}");
        assertThat(refund(charge, 100, "rfd_original").path("id").asText())
                .isNotEqualTo(pending.path("id").asText());
        assertThat(get("/v1/refunds?charge=" + charge).path("data")).hasSize(2);
    }

    @Test
    void ac_8_7_twelveSameSecondRefundsHaveTruePaginationAndSecondPageFault() throws Exception {
        String charge = charge(10000);
        var created = new ArrayList<String>();
        for (int i = 0; i < 12; i++)
            created.add(refund(charge, 100, "rfd_" + i).path("id").asText());
        var first = get("/v1/refunds?charge=" + charge);
        assertThat(first.path("data")).hasSize(10);
        assertThat(first.path("has_more").asBoolean()).isTrue();
        assertThat(first.path("data").get(0).path("id").asText()).isEqualTo(created.get(11));
        String next = "/v1/refunds?charge=" + charge + "&limit=10&starting_after=" + created.get(2);
        var second = get(next);
        assertThat(second.path("has_more").asBoolean()).isFalse();
        assertThat(second.path("data")).hasSize(2);
        assertThat(second.path("data").get(0).path("id").asText()).isEqualTo(created.get(1));
        post("/sim/refunds/faults", "{\"charge\":\"" + charge + "\",\"list_page_timeout\":2}");
        assertThat(request("GET", next, "").statusCode()).isEqualTo(504);
        assertThat(get("/v1/refunds?charge=" + charge).path("data")).hasSize(10);
    }

    @Test
    void ac_3_10_timeoutBeforeCreateConsumesCountWithoutRefundOrIdempotencyRecord() throws Exception {
        String charge = charge(5000);
        post("/sim/refunds/faults", "{\"timeout_before_create\":2}");
        for (int i = 0; i < 2; i++)
            assertThat(submit(charge, 100, "rfd_timeout").statusCode()).isEqualTo(504);
        assertThat(get("/v1/refunds?charge=" + charge).path("data")).isEmpty();
        assertThat(jdbc.sql("select count(*) from sim_processor.idempotency_keys")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(refund(charge, 100, "rfd_timeout").path("status").asText()).isEqualTo("succeeded");
        assertThat(events("refund.created")).hasSize(1);
    }

    @Test
    void ac_3_11_timeoutAfterCreateLosesResponseButListAndSignedEventRecoverIt() throws Exception {
        String charge = charge(5000);
        post("/sim/refunds/faults", "{\"charge\":\"" + charge + "\",\"timeout_after_create\":1}");
        arrived = new CountDownLatch(1);
        var response = client.sendAsync(
                refundRequest(charge, 100, "rfd_lost")
                        .timeout(Duration.ofSeconds(2))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(HttpTimeoutException.class);
        var list = get("/v1/refunds?charge=" + charge).path("data");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("metadata").path("ledgerlab_refund_id").asText())
                .isEqualTo("rfd_lost");
        assertThat(refund(charge, 100, "rfd_lost")).isEqualTo(list.get(0));
        assertThat(events("refund.created")).hasSize(1);
        for (Received received : RECEIVED) {
            String[] fields = received.signature().split(",", -1);
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            hmac.update((fields[0].substring(2) + ".").getBytes(StandardCharsets.UTF_8));
            assertThat(MessageDigest.isEqual(
                            hmac.doFinal(received.body()), HexFormat.of().parseHex(fields[1].substring(3))))
                    .isTrue();
            assertThat(Math.abs(app.getBean(java.time.Clock.class).instant().getEpochSecond()
                            - Long.parseLong(fields[0].substring(2))))
                    .isLessThan(300);
        }
    }

    @Test
    void ac_3_17_eventBeforeResponseWaitsForWebhookAndUsesCommittedState() throws Exception {
        String charge = charge(5000);
        post("/sim/refunds/faults", "{\"event_before_response\":true}");
        arrived = new CountDownLatch(1);
        release = new CountDownLatch(1);
        var response =
                client.sendAsync(refundRequest(charge, 100, "rfd_early").build(), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(response.isDone()).isFalse();
            var event = events("refund.created").getFirst();
            var refund = event.path("data").path("object");
            assertThat(refund.path("status").asText()).isEqualTo("succeeded");
            assertThat(get("/v1/refunds/" + refund.path("id").asText())).isEqualTo(refund);
            // A second funds transaction can finish while the first webhook is held.
            assertThat(post("/sim/refunds/process", "{}").path("events").asInt())
                    .isZero();
        } finally {
            release.countDown();
        }
        assertThat(ok(response.get(5, TimeUnit.SECONDS)).path("status").asText())
                .isEqualTo("succeeded");
    }

    @Test
    void ac_3_17_suppressEventsAppliesToCreateAndLaterTransitions() throws Exception {
        String charge = charge(5000);
        post("/sim/refunds/faults", "{\"suppress_events\":true}");
        assertThat(refund(charge, 100, "rfd_silent").path("status").asText()).isEqualTo("succeeded");
        post("/sim/refunds/faults", "{\"suppress_events\":true,\"force_pending\":true}");
        var pending = refund(charge, 100, "rfd_silent_later");
        post("/sim/refunds/" + pending.path("id").asText() + "/succeed", "{}");
        assertThat(events("refund.created")).isEmpty();
        assertThat(events("refund.updated")).isEmpty();
        assertThat(jdbc.sql("select count(*) from sim_processor.event where type like 'refund.%'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_3_18_forcePendingChargeOverrideAndOldestEligibleRefundWins() throws Exception {
        String charge = charge(1000);
        post("/sim/refunds/faults", "{\"force_pending\":true}");
        var first = refund(charge, 600, "rfd_first");
        var second = refund(charge, 400, "rfd_second");
        post("/sim/clock/advance", "{\"seconds\":1}");
        assertThat(get("/v1/refunds/" + first.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("pending");
        post("/sim/refunds/faults", "{\"charge\":\"" + charge + "\",\"force_pending\":false}");
        post("/sim/refunds/process", "{}");
        assertThat(get("/v1/refunds/" + first.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("succeeded");
        assertThat(get("/v1/refunds/" + second.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("pending");
        charge(100); // New immediately available income triggers pending processing.
        assertThat(get("/v1/refunds/" + second.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("succeeded");
        assertThat(events("refund.updated")).hasSize(2);
        post("/sim/refunds/process", "{}");
        assertThat(events("refund.updated")).hasSize(2);
    }

    @Test
    void ac_3_18_unfundedHeadDoesNotBlockSmallerProcessorRefund() throws Exception {
        String charge = charge(1000);
        post("/sim/refunds/faults", "{\"force_pending\":true}");
        var first = refund(charge, 950, "rfd_large");
        var second = refund(charge, 50, "rfd_small");
        post("/sim/refunds/faults", "{}");
        post("/sim/refunds/process", "{}");
        assertThat(get("/v1/refunds/" + first.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("pending");
        assertThat(get("/v1/refunds/" + second.path("id").asText())
                        .path("status")
                        .asText())
                .isEqualTo("succeeded");
    }

    @Test
    void ac_3_10_failNextReleasesCapacityAndPreservesCreatedSnapshot() throws Exception {
        String charge = charge(1000);
        post("/sim/refunds/faults", "{\"fail_next\":\"lost_or_stolen_card\"}");
        var failed = refund(charge, 1000, "rfd_failed");
        assertThat(failed.path("status").asText()).isEqualTo("failed");
        assertThat(failed.path("failure_reason").asText()).isEqualTo("lost_or_stolen_card");
        assertThat(failed.path("balance_transaction").isNull()).isTrue();
        assertThat(events("refund.created")
                        .getFirst()
                        .path("data")
                        .path("object")
                        .path("status")
                        .asText())
                .isEqualTo("pending");
        assertThat(events("refund.failed").getFirst().path("data").path("object"))
                .isEqualTo(failed);
        assertThat(refund(charge, 1000, "rfd_after_failure").path("status").asText())
                .isEqualTo("pending");
        assertThat(events("refund.failed")).hasSize(1);
        var rejected = submit(charge, 1, "rfd_over");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(rejected.body()).path("error").path("code").asText())
                .isEqualTo("charge_already_refunded");
    }

    @Test
    void ac_3_18_manualTransitionsAreIdempotentAndCannotInventFunds() throws Exception {
        String charge = charge(1000);
        var pending = refund(charge, 1000, "rfd_manual");
        String path = "/sim/refunds/" + pending.path("id").asText();
        assertThat(request("POST", path + "/succeed", "{}").statusCode()).isEqualTo(409);
        var failed = post(path + "/fail", "{\"reason\":\"expired_or_canceled_card\"}");
        assertThat(failed.path("status").asText()).isEqualTo("failed");
        assertThat(post(path + "/fail", "{}")).isEqualTo(failed);
        assertThat(events("refund.failed")).hasSize(1);
        assertThat(request("POST", path + "/succeed", "{}").statusCode()).isEqualTo(409);
        var success = refund(charge, 100, "rfd_success");
        String successPath = "/sim/refunds/" + success.path("id").asText();
        assertThat(post(successPath + "/succeed", "{}")).isEqualTo(success);
        assertThat(request("POST", successPath + "/fail", "{}").statusCode()).isEqualTo(409);
    }

    @Test
    void ac_8_7_concurrentSameKeyAndConcurrentDifferentKeysCannotDoubleAllocate() throws Exception {
        String charge = charge(1000);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                barrier.await();
                return refund(charge, 600, "rfd_same");
            });
            var second = executor.submit(() -> {
                barrier.await();
                return refund(charge, 600, "rfd_same");
            });
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            var a = executor.submit(() -> {
                barrier.await();
                return submit(charge, 400, "rfd_a");
            });
            var b = executor.submit(() -> {
                barrier.await();
                return submit(charge, 400, "rfd_b");
            });
            assertThat(List.of(
                            a.get(10, TimeUnit.SECONDS).statusCode(),
                            b.get(10, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 400);
        }
        var rows = get("/v1/refunds?charge=" + charge).path("data");
        assertThat(rows).hasSize(2);
        assertThat(jdbc.sql("select count(*) from sim_processor.balance_transaction where type='refund'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(events("refund.created")).hasSize(2);
    }

    @Test
    void ac_8_7_invalidRequestsHaveStripeErrorsAndCreateNothing() throws Exception {
        String charge = charge(1000);
        for (String body : List.of(
                "",
                "charge=" + charge + "&amount=1.5",
                "charge=" + charge + "&amount=0",
                "charge=" + charge + "&amount=9223372036854775808",
                "charge=" + charge + "&amount=-1",
                "charge=" + charge + "&amount=1&amount=2",
                "charge=" + charge + "&amount=1&unknown=1")) {
            var result = client.send(
                    headers("/v1/refunds")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Idempotency-Key", "rfd_bad")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).as(body).isEqualTo(400);
            assertThat(JSON.readTree(result.body()).path("error").path("type").asText())
                    .isEqualTo("invalid_request_error");
        }
        assertThat(submit("ch_absent", 100, "rfd_missing").statusCode()).isEqualTo(400);
        assertThat(JSON.readTree(submit(charge, 1001, "rfd_large").body())
                        .path("error")
                        .path("code")
                        .asText())
                .isEqualTo("amount_too_large");
        for (String suffix :
                List.of("?limit=0", "?limit=101", "?limit=x", "?starting_after=re_absent", "?charge=ch_absent"))
            assertThat(request("GET", "/v1/refunds" + suffix, "").statusCode()).isEqualTo(400);
        assertThat(request("GET", "/v1/refunds/re_absent", "").statusCode()).isEqualTo(404);
        var noKey = client.send(
                headers("/v1/refunds")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("charge=" + charge + "&amount=1"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noKey.statusCode()).isEqualTo(400);
        var noAuth = client.send(
                HttpRequest.newBuilder(URI.create(base + "/v1/refunds")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noAuth.statusCode()).isEqualTo(401);
        var noVersion = client.send(
                HttpRequest.newBuilder(URI.create(base + "/v1/refunds"))
                        .header("Authorization", "Bearer " + KEY)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noVersion.statusCode()).isEqualTo(400);
        assertThat(get("/v1/refunds?charge=" + charge).path("data")).isEmpty();
        assertThat(jdbc.sql("select count(*) from sim_processor.idempotency_keys")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_8_7_faultPlanValidationAndScopedListFaults() throws Exception {
        for (String body : List.of(
                "[]",
                "{\"unknown\":1}",
                "{\"charge\":\"bad\"}",
                "{\"fail_next\":\"\"}",
                "{\"force_pending\":1}",
                "{\"timeout_before_create\":-1}",
                "{\"timeout_after_create\":1.5}",
                "{\"list_page_timeout\":2147483648}"))
            assertThat(request("POST", "/sim/refunds/faults", body).statusCode())
                    .as(body)
                    .isEqualTo(400);
        String charge = charge(1000);
        post("/sim/refunds/faults", "{\"list_page_timeout\":1}");
        assertThat(request("GET", "/v1/refunds?charge=" + charge, "").statusCode())
                .isEqualTo(504);
        post("/sim/refunds/faults", "{\"charge\":\"" + charge + "\",\"list_page_timeout\":0}");
        assertThat(get("/v1/refunds?charge=" + charge).path("data")).isEmpty();
        assertThat(request("POST", "/sim/idempotency/purge", "{\"before\":\"bad\"}")
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void ac_3_18_twoChargesCompeteForOneAvailableBalanceWithoutOverspending() throws Exception {
        String firstCharge = charge(1000);
        String secondCharge = charge(1000);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                barrier.await();
                return refund(firstCharge, 1000, "rfd_compete_1");
            });
            var second = executor.submit(() -> {
                barrier.await();
                return refund(secondCharge, 1000, "rfd_compete_2");
            });
            assertThat(List.of(
                            first.get(10, TimeUnit.SECONDS).path("status").asText(),
                            second.get(10, TimeUnit.SECONDS).path("status").asText()))
                    .containsExactlyInAnyOrder("succeeded", "pending");
        }
        assertThat(jdbc.sql("select sum(net) from sim_processor.balance_transaction where payout is null")
                        .query(Long.class)
                        .single())
                .isEqualTo(882);
        assertThat(jdbc.sql("select count(*) from sim_processor.balance_transaction where type='refund'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void ac_3_11_eventStorageFailureRollsBackRefundBalanceAndIdempotencyTogether() throws Exception {
        String charge = charge(1000);
        jdbc.sql("alter table sim_processor.event add constraint test_refund_rejected check (type <> 'refund.created')")
                .update();
        try {
            assertThat(submit(charge, 100, "rfd_rollback").statusCode()).isEqualTo(500);
            assertThat(get("/v1/refunds?charge=" + charge).path("data")).isEmpty();
            assertThat(jdbc.sql("select count(*) from sim_processor.idempotency_keys")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("select count(*) from sim_processor.balance_transaction where type='refund'")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(events("refund.created")).isEmpty();
        } finally {
            jdbc.sql("alter table sim_processor.event drop constraint test_refund_rejected")
                    .update();
        }
        assertThat(refund(charge, 100, "rfd_rollback").path("status").asText()).isEqualTo("succeeded");
    }

    @Test
    void ac_8_7_v5ConstraintsAndForeignKeysAreEnforcedByPostgres() throws Exception {
        String charge = charge(5000);
        post("/sim/refunds/faults", "{\"force_pending\":true}");
        var refund = refund(charge, 100, "rfd_constraints");
        String id = refund.path("id").asText();
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            for (String assignment : List.of(
                    "id='bad'",
                    "amount=0",
                    "amount=-1",
                    "amount=9007199254740992",
                    "status='unknown'",
                    "metadata='[]'::jsonb",
                    "status='succeeded'",
                    "status='failed'",
                    "failure_reason='declined'"))
                rejected(
                        statement,
                        "update sim_processor.refunds set " + assignment + " where id='" + id + "'",
                        "23514");
            rejected(statement, "update sim_processor.refunds set charge='ch_absent' where id='" + id + "'", "23503");
            rejected(
                    statement,
                    "update sim_processor.refunds set status='succeeded',balance_transaction='txn_absent' where id='"
                            + id + "'",
                    "23503");
            rejected(statement, "update sim_processor.event set refund_id='re_absent'", "23503");
            for (String assignment : List.of("key=''", "key=repeat('x',256)", "request_fingerprint='bad'"))
                rejected(statement, "update sim_processor.idempotency_keys set " + assignment, "23514");
            rejected(statement, "update sim_processor.refund_faults set target='bad'", "23514");
            rejected(statement, "update sim_processor.refund_faults set plan='[]'::jsonb", "23514");
            for (String table : List.of("refunds", "idempotency_keys", "refund_faults")) {
                var columns = jdbc.sql(
                                "select column_name from information_schema.columns where table_schema='sim_processor' and table_name=? order by ordinal_position")
                        .param(table)
                        .query(String.class)
                        .list();
                var required = jdbc.sql(
                                "select column_name from information_schema.columns where table_schema='sim_processor' and table_name=? and is_nullable='NO'")
                        .param(table)
                        .query(String.class)
                        .list();
                for (String column : required) {
                    String selection = columns.stream()
                            .map(c ->
                                    java.util.Objects.equals(c, column) ? "null" : java.util.Objects.requireNonNull(c))
                            .collect(java.util.stream.Collectors.joining(","));
                    rejected(
                            statement,
                            "insert into sim_processor." + table + " (" + String.join(",", columns)
                                    + ") overriding system value select " + selection + " from sim_processor." + table
                                    + " limit 1",
                            "23502");
                }
                rejected(
                        statement,
                        "insert into sim_processor." + table + " overriding system value select * from sim_processor."
                                + table + " limit 1",
                        "23505");
            }
            rejected(
                    statement,
                    "insert into sim_processor.refunds(id,charge,amount,status,metadata,created,creation_order,recorded_at) overriding system value select 're_duplicate_order',charge,amount,status,metadata,created,creation_order,recorded_at from sim_processor.refunds limit 1",
                    "23505");
        }
        post("/sim/refunds/faults", "{}");
        post("/sim/refunds/process", "{}");
        var other = refund(charge, 100, "rfd_second_line");
        String line = get("/v1/refunds/" + id).path("balance_transaction").asText();
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            rejected(
                    statement,
                    "update sim_processor.refunds set balance_transaction='" + line + "' where id='"
                            + other.path("id").asText() + "'",
                    "23505");
        }
        assertThat(jdbc.sql(
                                "select version from sim_processor.flyway_schema_history where success order by installed_rank desc limit 1")
                        .query(String.class)
                        .single())
                .isEqualTo("5");
    }

    @Test
    void ac_8_1_v5TablesAndSequenceAreInvisibleToAllMainApplicationRoles() throws Exception {
        for (String role : List.of("migrator", "app", "readonly")) {
            try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                    var statement = connection.createStatement()) {
                for (String table : List.of("refunds", "idempotency_keys", "refund_faults")) {
                    for (String sql : List.of(
                            "select * from sim_processor." + table,
                            "insert into sim_processor." + table + " default values",
                            "delete from sim_processor." + table,
                            "truncate sim_processor." + table)) rejected(statement, sql, "42501");
                }
                rejected(statement, "select nextval('sim_processor.refunds_creation_order_seq')", "42501");
            }
        }
        for (String role : List.of("migrator", "app", "readonly")) {
            for (String table : List.of("refunds", "idempotency_keys", "refund_faults"))
                assertThat(jdbc.sql(
                                        "select has_table_privilege(?, ?, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')")
                                .param(role)
                                .param("sim_processor." + table)
                                .query(Boolean.class)
                                .single())
                        .isFalse();
        }
    }

    private static void rejected(java.sql.Statement statement, String sql, String state) {
        assertThatThrownBy(() -> statement.execute(sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(state);
    }

    private static List<JsonNode> events(String type) {
        return RECEIVED.stream()
                .map(r -> JSON.readTree(r.body()))
                .filter(e -> e.path("type").asText().equals(type))
                .toList();
    }

    private static String charge(long amount) throws Exception {
        return post("/sim/charges", "{\"amount\":" + amount + "}")
                .path("charge")
                .path("id")
                .asText();
    }

    private static JsonNode refund(String charge, long amount, String key) throws Exception {
        return ok(submit(charge, amount, key));
    }

    private static HttpResponse<String> submit(String charge, long amount, String key) throws Exception {
        return client.send(refundRequest(charge, amount, key).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder refundRequest(String charge, long amount, String key) {
        return headers("/v1/refunds")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString("charge=" + charge + "&amount=" + amount
                        + "&metadata%5Bledgerlab_refund_id%5D=" + URLEncoder.encode(key, StandardCharsets.UTF_8)));
    }

    private static HttpRequest.Builder headers(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + KEY)
                .header("Stripe-Version", "2026-09-30.endive");
    }

    private static HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                headers(path)
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode post(String path, String body) throws Exception {
        return ok(request("POST", path, body));
    }

    private static JsonNode get(String path) throws Exception {
        return ok(request("GET", path, ""));
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    @SuppressWarnings("ArrayRecordComponent")
    private record Received(byte[] body, String signature) {}
}
