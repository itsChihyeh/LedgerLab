package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SandboxTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final List<Map<String, String>> REQUESTS = new CopyOnWriteArrayList<>();
    private static PostgreSQLContainer database;
    private static HttpServer upstream;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static JdbcClient admin;
    private static String base;
    private static volatile String mode = "normal";

    @BeforeAll
    static void start() throws Exception {
        database = TestDatabase.start();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/proxy/v1/", e -> {
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = e.getRequestHeaders().getFirst("Idempotency-Key");
            REQUESTS.add(Map.of(
                    "path",
                    e.getRequestURI().toString(),
                    "body",
                    body,
                    "key",
                    key == null ? "" : key,
                    "version",
                    e.getRequestHeaders().getFirst("Stripe-Version")));
            int status = 200;
            String result;
            if (e.getRequestURI().getPath().endsWith("payment_intents")) {
                var form = form(body);
                String ordinal = key.substring(key.lastIndexOf(':') + 1);
                if (mode.equals("partial") && ordinal.equals("2")) {
                    status = 402;
                    result = "{\"error\":{\"code\":\"card_declined\",\"message\":\"private message\"}}";
                } else {
                    result = "{\"id\":\"pi_test" + ordinal + "\",\"status\":\"succeeded\",\"livemode\":false,"
                            + "\"amount\":" + form.get("amount") + ",\"currency\":\"cad\",\"latest_charge\":{"
                            + "\"id\":\"ch_test" + ordinal
                            + "\",\"livemode\":false,\"payment_method_details\":{\"type\":\""
                            + (mode.equals("noncard") ? "link" : "card") + "\"}}}";
                }
            } else if (e.getRequestURI().getPath().contains("/charges/")) {
                result = "{\"id\":\"ch_test1\",\"livemode\":false,\"payment_method_details\":{\"type\":\"card\"}}";
            } else {
                boolean second = e.getRequestURI().getRawQuery().contains("starting_after");
                if (second && mode.equals("pagefail")) {
                    status = 503;
                    result = "{\"error\":{\"message\":\"private message\"}}";
                } else if (mode.equals("skip")) {
                    result = page(
                            false,
                            payout("manual", false, "standard", "cad", 100),
                            payout("instant", true, "instant", "cad", 100),
                            payout("usd", true, "standard", "usd", 100),
                            payout("zero", true, "standard", "cad", 0));
                } else {
                    result = page(!second, payout(second ? "two" : "one", true, "standard", "cad", 1000));
                }
            }
            switch (mode) {
                case "invalid-json" -> result = "not JSON";
                case "invalid-page" -> result = "{\"data\":[],\"has_more\":\"yes\"}";
                case "empty-cursor" -> result = page(true);
                case "repeated-cursor" -> result = page(true, payout("same", true, "standard", "cad", 1000));
                case "live" -> result = result.replace("\"livemode\":false", "\"livemode\":true");
                case "charge-ref" -> {
                    if (e.getRequestURI().getPath().endsWith("payment_intents")) {
                        var node = (tools.jackson.databind.node.ObjectNode) JSON.readTree(result);
                        node.put("latest_charge", "ch_test1");
                        result = node.toString();
                    }
                }
                default -> {}
            }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(status, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
        });
        upstream.start();
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.profiles.active=sandbox",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                        "--sim.stripe.webhook-secret=synthetic-only",
                        "--sim.sandbox.stripe-api-key=" + "sk_test_" + "synthetic",
                        "--sim.sandbox.stripe-base-url=http://127.0.0.1:"
                                + upstream.getAddress().getPort() + "/proxy",
                        "--sim.sandbox.run-id=sbx_test",
                        "--sim.sandbox.window-start=2026-11-02T00:00:00Z",
                        "--sim.clock.start=2026-11-02T12:00:00Z",
                        "--sim.clock.follow-wall=false");
        base = "http://127.0.0.1:"
                + java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                        .getPort();
        client = HttpClient.newHttpClient();
        admin = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.getJdbcUrl(), "postgres", "test-admin"));
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (app != null) app.close();
        if (upstream != null) upstream.stop(0);
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() {
        admin.sql("truncate sim_sandbox.payment_attempt,sim_sandbox.bank_sync,sim_bank.account cascade")
                .update();
        REQUESTS.clear();
        mode = "normal";
    }

    @Test
    void ac_8_3_recordingsFilterPageAndRedactWithoutChangingStoredEvidence() throws Exception {
        byte[] body = ("{\"id\":\"evt_export\",\"billing_details\":{\"name\":\"private person\"},"
                        + "\"nested\":[\"acct_" + "private\"],\"amount\":1234}")
                .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 2; i++) {
            admin.sql("""
                    INSERT INTO sim_chaos.inbound_recording(event_id,event_type,received_at,recorded_at,sha256,body)
                    VALUES ('evt_export','export.test','2026-11-02T12:00:00Z','2026-11-02T12:00:00Z',
                    encode(sha256(:body),'hex'),:body)
                    """).param("body", body).update();
        }
        var first = call("GET", "/recordings?kind=webhook&event_type=export.test&limit=1", "");
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode page = JSON.readTree(first.body());
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.get(0).path("body").path("amount").longValue()).isEqualTo(1234);
        assertThat(page.get(0).path("body").has("billing_details")).isFalse();
        assertThat(page.get(0).path("body").path("nested").get(0).asString()).isEqualTo("acct_" + "REDACTED");
        long id = page.get(0).path("id").longValue();
        var second = JSON.readTree(call("GET", "/recordings?kind=webhook&event_type=export.test&after_id=" + id, "")
                .body());
        assertThat(second.size()).isEqualTo(1);
        assertThat(second.get(0).path("id").longValue()).isGreaterThan(id);
        assertThat(admin.sql("SELECT body FROM sim_chaos.inbound_recording WHERE id=?")
                        .param(id)
                        .query(byte[].class)
                        .single())
                .isEqualTo(body);
        assertThat(JSON.readTree(call("GET", "/recordings?kind=webhook&event_type=absent", "")
                                .body())
                        .isEmpty())
                .isTrue();
    }

    @Test
    void ac_8_3_apiExportsResponsesOnlyAndMatchesLiteralPrefix() throws Exception {
        byte[] response =
                "{\"data\":[],\"has_more\":false,\"receipt_url\":\"private\"}".getBytes(StandardCharsets.UTF_8);
        admin.sql("""
                INSERT INTO sim_chaos.api_exchange(received_at,recorded_at,method,path,query_parameter_names,
                upstream_status,action,duration_ms,request_sha256,response_sha256,request_body,response_body)
                VALUES ('2026-11-02T12:00:00Z','2026-11-02T12:00:00Z','GET','/v1/export_test','[]',200,
                'PASS',1,encode(sha256('private'::bytea),'hex'),encode(sha256(:body),'hex'),'private'::bytea,:body)
                """).param("body", response).update();
        var result = call("GET", "/recordings?kind=api&path_prefix=/v1/export_", "");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body()).doesNotContain("private", "request_body", "receipt_url");
        var item = JSON.readTree(result.body()).get(0);
        assertThat(item.path("method").asString()).isEqualTo("GET");
        assertThat(item.path("status").intValue()).isEqualTo(200);
        assertThat(JSON.readTree(call("GET", "/recordings?kind=api&path_prefix=/v1/export%25", "")
                                .body())
                        .isEmpty())
                .isTrue();
    }

    @Test
    void ac_8_3_invalidRecordingFiltersAndUnsafeBodyFailClosed() throws Exception {
        for (String query :
                List.of("kind=other", "kind=api&limit=0", "kind=api&limit=1001", "kind=webhook&after_id=-1")) {
            assertThat(call("GET", "/recordings?" + query, "").statusCode()).isEqualTo(400);
        }
        byte[] body = ("{\"unknown\":\"sk_" + "private\"}").getBytes(StandardCharsets.UTF_8);
        admin.sql("""
                INSERT INTO sim_chaos.inbound_recording(event_type,received_at,recorded_at,sha256,body)
                VALUES ('export.unsafe','2026-11-02T12:00:00Z','2026-11-02T12:00:00Z',encode(sha256(:body),'hex'),:body)
                """).param("body", body).update();
        var response = call("GET", "/recordings?kind=webhook&event_type=export.unsafe", "");
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).doesNotContain("private");
    }

    @Test
    void ac_8_4_deterministicFormAndReplayCountsLogicalPayments() throws Exception {
        String request = "{\"count\":3,\"card\":\"pm_card_ca\",\"seed\":12}";
        assertThat(call("POST", "/payments", request).statusCode()).isEqualTo(200);
        var first = REQUESTS.stream()
                .map(r -> form(r.getOrDefault("body", "")).get("amount"))
                .toList();
        assertThat(first)
                .hasSize(3)
                .allSatisfy(a -> assertThat(Long.parseLong(a)).isBetween(500L, 20000L));
        for (int i = 0; i < 3; i++) {
            assertThat(REQUESTS.get(i).get("key")).isEqualTo("sbx_test:pay:pm_card_ca:12:" + (i + 1));
            assertThat(REQUESTS.get(i).get("version")).isEqualTo("2026-09-30.endive");
            assertThat(form(REQUESTS.get(i).getOrDefault("body", "")))
                    .containsEntry("currency", "cad")
                    .containsEntry("automatic_payment_methods[enabled]", "true")
                    .containsEntry("automatic_payment_methods[allow_redirects]", "never")
                    .containsEntry("confirm", "true")
                    .containsEntry("metadata[ledgerlab_run_id]", "sbx_test")
                    .doesNotContainKeys("payment_method_types", "payment_method_types[]");
        }
        call("POST", "/payments", request);
        assertThat(REQUESTS).hasSize(3);
        JsonNode summary = JSON.readTree(call("GET", "/summary", "").body());
        assertThat(summary.path("succeeded").asInt()).isEqualTo(3);
        assertThat(summary.path("failed").asInt()).isZero();
        assertThat(summary.path("cards").path("pm_card_ca").path("count").asInt())
                .isEqualTo(3);
        admin.sql("truncate sim_sandbox.payment_attempt").update();
        REQUESTS.clear();
        call("POST", "/payments", request);
        assertThat(REQUESTS.stream().map(r -> form(r.getOrDefault("body", "")).get("amount")))
                .containsExactlyElementsOf(first);
    }

    @Test
    void ac_8_4_partialFailurePreservesSuccessAndSafeResults() throws Exception {
        mode = "partial";
        call("POST", "/payments", "{\"count\":3,\"card\":\"pm_card_bypassPending\",\"seed\":1}");
        String records = call("GET", "/payments", "").body();
        assertThat(records).contains("card_declined", "pi_test1", "pi_test3").doesNotContain("private message");
        JsonNode summary = JSON.readTree(call("GET", "/summary", "").body());
        assertThat(summary.path("succeeded").asInt()).isEqualTo(2);
        assertThat(summary.path("failed").asInt()).isEqualTo(1);
    }

    @Test
    void ac_8_4_requiresCardCharge() throws Exception {
        mode = "noncard";
        call("POST", "/payments", "{\"count\":1,\"card\":\"pm_card_ca\",\"seed\":1}");
        assertThat(call("GET", "/payments", "").body()).contains("UNSUPPORTED_CHARGE");
        assertThat(JSON.readTree(call("GET", "/summary", "").body())
                        .path("succeeded")
                        .asInt())
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"count\":0,\"card\":\"pm_card_ca\",\"seed\":1}",
                "{\"count\":51,\"card\":\"pm_card_ca\",\"seed\":1}",
                "{\"count\":1,\"card\":\"pm_card_visa\",\"seed\":1}",
                "{\"count\":1.5,\"card\":\"pm_card_ca\",\"seed\":1}",
                "{\"count\":1,\"card\":\"pm_card_ca\"}"
            })
    void ac_8_4_invalidRequestNeverCallsStripe(String request) throws Exception {
        assertThat(call("POST", "/payments", request).statusCode()).isEqualTo(400);
        assertThat(REQUESTS).isEmpty();
    }

    @Test
    void ac_8_8_allPagesAndDuplicateSyncNeverDuplicateTransfers() throws Exception {
        var result = call("POST", "/bank/sync", "");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(result.body()).path("received").asInt()).isEqualTo(2);
        assertThat(REQUESTS).hasSize(2);
        assertThat(URLDecoder.decode(REQUESTS.getFirst().get("path"), StandardCharsets.UTF_8))
                .contains("status=paid", "created[gte]=1793577600", "limit=100");
        assertThat(REQUESTS.getLast().get("path")).contains("starting_after=po_one");
        assertThat(JSON.readTree(call("POST", "/bank/sync", "").body())
                        .path("received")
                        .asInt())
                .isZero();
        assertThat(admin.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(JSON.readTree(call("GET", "/summary", "").body())
                        .path("payouts_received")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void ac_8_8_secondPageFailureReceivesNothingAndRecordsFailure() throws Exception {
        mode = "pagefail";
        var result = call("POST", "/bank/sync", "");
        assertThat(result.statusCode()).isEqualTo(502);
        assertThat(JSON.readTree(result.body()).path("pages").asInt()).isEqualTo(1);
        assertThat(admin.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(admin.sql("select http_status from sim_sandbox.bank_sync")
                        .query(Integer.class)
                        .single())
                .isEqualTo(502);
    }

    @Test
    void ac_8_8_unsupportedPayoutsHaveReasons() throws Exception {
        mode = "skip";
        JsonNode result = JSON.readTree(call("POST", "/bank/sync", "").body());
        assertThat(result.path("seen").asInt()).isEqualTo(4);
        assertThat(result.path("received").asInt()).isZero();
        assertThat(result.path("skipped").size()).isEqualTo(4);
        assertThat(result.toString()).contains("MANUAL", "INSTANT", "CURRENCY", "AMOUNT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-json", "invalid-page", "empty-cursor", "repeated-cursor"})
    void ac_8_8_malformedOrNonAdvancingPagesNeverReachBank(String fault) throws Exception {
        mode = fault;
        assertThat(call("POST", "/bank/sync", "").statusCode()).isEqualTo(502);
        assertThat(admin.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(REQUESTS.size()).isBetween(1, 2);
    }

    @Test
    void ac_8_2_liveResponsesAreRejectedByBothBridges() throws Exception {
        mode = "live";
        assertThat(call("POST", "/payments", "{\"count\":1,\"card\":\"pm_card_ca\",\"seed\":1}")
                        .body())
                .contains("LIVEMODE");
        assertThat(JSON.readTree(call("GET", "/summary", "").body())
                        .path("succeeded")
                        .asInt())
                .isZero();
        assertThat(call("POST", "/bank/sync", "").body()).contains("LIVEMODE");
        assertThat(admin.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_8_4_unexpandedChargeIsFetchedAndVerified() throws Exception {
        mode = "charge-ref";
        var result = call("POST", "/payments", "{\"count\":1,\"card\":\"pm_card_ca\",\"seed\":1}");
        assertThat(JSON.readTree(result.body()).path("succeeded").asInt()).isEqualTo(1);
        assertThat(REQUESTS).hasSize(2);
        assertThat(REQUESTS.getLast().get("path")).isEqualTo("/proxy/v1/charges/ch_test1");
    }

    @Test
    void ac_8_8_bankConflictRollsBackWholeSync() throws Exception {
        app.getBean(dev.ledgerlab.sim.bank.Bank.class).receive("po_two", 999);
        var result = call("POST", "/bank/sync", "");
        assertThat(result.statusCode()).isEqualTo(502);
        assertThat(result.body()).contains("BANK_RECEIVE_FAILED");
        assertThat(admin.sql("select payout_ref from sim_bank.incoming_transfer")
                        .query(String.class)
                        .list())
                .containsExactly("po_two");
        assertThat(admin.sql("select received from sim_sandbox.bank_sync")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_8_8_concurrentSyncCountsEachTransferOnce() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> sync = () -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                var response = call("POST", "/bank/sync", "");
                assertThat(response.statusCode()).isEqualTo(200);
                return JSON.readTree(response.body()).path("received").asInt();
            };
            var first = workers.submit(sync);
            var second = workers.submit(sync);
            assertThat(List.of(
                            first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(2, 0);
        }
        assertThat(admin.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    private static HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/sim/sandbox" + path))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ac_8_8_followWallIsMonotonicAndRunsCallbacksAndBankPlans() throws Exception {
        var simulation = app.getBean(dev.ledgerlab.sim.processor.SimulationClock.class);
        var bank = app.getBean(dev.ledgerlab.sim.bank.Bank.class);
        var start = simulation.instant();
        var wall = new dev.ledgerlab.sim.processor.SimulationClock(start);
        var follower = new dev.ledgerlab.sim.processor.WallClockFollower(simulation, wall, bank);
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        simulation.schedule(java.time.Duration.ofSeconds(1), callbacks::incrementAndGet);
        call("POST", "/bank/sync", "");
        follower.tick();
        assertThat(callbacks).hasValue(0);
        wall.advance(java.time.Duration.ofDays(2));
        follower.tick();
        assertThat(simulation.instant()).isEqualTo(wall.instant());
        assertThat(callbacks).hasValue(1);
        assertThat(admin.sql("select count(*) from sim_bank.txn")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        simulation.advance(java.time.Duration.ofSeconds(10));
        var ahead = simulation.instant();
        follower.tick();
        assertThat(simulation.instant()).isEqualTo(ahead);
        assertThat(callbacks).hasValue(1);
        assertThat(admin.sql("select count(*) from sim_bank.txn")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void ac_8_4_unconfirmedOldAttemptCannotCreateAgain() throws Exception {
        mode = "partial";
        String request = "{\"count\":2,\"card\":\"pm_card_ca\",\"seed\":1}";
        call("POST", "/payments", request);
        admin.sql("update sim_sandbox.payment_attempt set recorded_at='2020-01-01T00:00:00Z'")
                .update();
        REQUESTS.clear();
        String result = call("POST", "/payments", request).body();
        assertThat(REQUESTS).isEmpty();
        assertThat(result).contains("IDEMPOTENCY_WINDOW_EXPIRED", "pi_test1");
    }

    @Test
    void ac_8_4_retryUnconfirmedUsesSameKeyAndCountsOnce() throws Exception {
        mode = "partial";
        String request = "{\"count\":2,\"card\":\"pm_card_ca\",\"seed\":1}";
        call("POST", "/payments", request);
        mode = "normal";
        call("POST", "/payments", request);
        assertThat(REQUESTS).hasSize(3);
        assertThat(REQUESTS.get(1).get("key")).isEqualTo(REQUESTS.get(2).get("key"));
        assertThat(REQUESTS.get(1).get("body")).isEqualTo(REQUESTS.get(2).get("body"));
        assertThat(JSON.readTree(call("GET", "/summary", "").body())
                        .path("succeeded")
                        .asInt())
                .isEqualTo(2);
    }

    @Test
    void ac_1_13_sandboxAppendOnlyAndOtherRolesCannotRead() throws Exception {
        call("POST", "/payments", "{\"count\":1,\"card\":\"pm_card_ca\",\"seed\":1}");
        call("POST", "/bank/sync", "");
        for (String role : List.of("simulator", "app", "readonly", "migrator")) {
            try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                    var statement = connection.createStatement()) {
                for (String table : List.of("payment_attempt", "bank_sync")) {
                    for (String sql : List.of(
                            "update sim_sandbox." + table + " set run_id='sbx_changed'",
                            "delete from sim_sandbox." + table,
                            "truncate sim_sandbox." + table)) {
                        assertThatThrownBy(() -> statement.execute(sql))
                                .isInstanceOf(java.sql.SQLException.class)
                                .extracting(e -> ((java.sql.SQLException) e).getSQLState())
                                .isEqualTo("42501");
                    }
                    if (!role.equals("simulator")) {
                        for (String sql : List.of(
                                "select * from sim_sandbox." + table,
                                "select nextval('sim_sandbox." + table + "_id_seq')",
                                "insert into sim_sandbox." + table + " default values")) {
                            assertThatThrownBy(() -> statement.execute(sql))
                                    .isInstanceOf(java.sql.SQLException.class)
                                    .extracting(e -> ((java.sql.SQLException) e).getSQLState())
                                    .isEqualTo("42501");
                        }
                    }
                }
            }
        }
    }

    @Test
    void ac_1_13_databaseRejectsInvalidSandboxRows() throws Exception {
        call("POST", "/payments", "{\"count\":1,\"card\":\"pm_card_ca\",\"seed\":1}");
        call("POST", "/bank/sync", "");
        var invalid = Map.of(
                "payment_attempt",
                List.of(
                        "run_id='wrong'",
                        "card='unknown'",
                        "ordinal=0",
                        "ordinal=51",
                        "amount=499",
                        "amount=20001",
                        "payment_intent='bad'",
                        "charge='bad'",
                        "status='bad'",
                        "http_status=99",
                        "http_status=600"),
                "bank_sync",
                List.of(
                        "run_id='wrong'",
                        "pages=-1",
                        "seen=-1",
                        "received=-1",
                        "received=3",
                        "http_status=400",
                        "received_payouts='{}'",
                        "skipped='{}'",
                        "received_payouts='[]'",
                        "http_status=502"));
        try (var connection = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                database.getJdbcUrl(), "postgres", "test-admin")
                        .getConnection();
                var statement = connection.createStatement()) {
            for (var entry : invalid.entrySet()) {
                for (String assignment : entry.getValue()) {
                    assertThatThrownBy(() ->
                                    statement.execute("update sim_sandbox." + entry.getKey() + " set " + assignment))
                            .isInstanceOf(java.sql.SQLException.class)
                            .extracting(e -> ((java.sql.SQLException) e).getSQLState())
                            .isEqualTo("23514");
                }
                assertThatThrownBy(() -> statement.execute("insert into sim_sandbox." + entry.getKey()
                                + " overriding system value select * from sim_sandbox." + entry.getKey()))
                        .isInstanceOf(java.sql.SQLException.class)
                        .extracting(e -> ((java.sql.SQLException) e).getSQLState())
                        .isEqualTo("23505");
            }
            for (var entry : Map.of(
                            "payment_attempt",
                            List.of(
                                    "id",
                                    "run_id",
                                    "card",
                                    "seed",
                                    "ordinal",
                                    "amount",
                                    "payment_intent",
                                    "charge",
                                    "status",
                                    "error_code",
                                    "recorded_at"),
                            "bank_sync",
                            List.of(
                                    "id",
                                    "run_id",
                                    "pages",
                                    "seen",
                                    "received",
                                    "http_status",
                                    "received_payouts",
                                    "skipped",
                                    "error_code",
                                    "recorded_at"))
                    .entrySet()) {
                for (String column : entry.getValue()) {
                    assertThatThrownBy(() -> statement.execute(
                                    "update sim_sandbox." + entry.getKey() + " set " + column + "=null"))
                            .isInstanceOf(java.sql.SQLException.class)
                            .extracting(e -> ((java.sql.SQLException) e).getSQLState())
                            .isEqualTo(column.equals("id") ? "428C9" : "23502");
                }
            }
        }
    }

    private static Map<String, String> form(String body) {
        var result = new java.util.HashMap<String, String>();
        for (String field : body.split("&")) {
            String[] pair = field.split("=", 2);
            result.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String payout(String id, boolean automatic, String method, String currency, long amount) {
        return "{\"id\":\"po_" + id + "\",\"status\":\"paid\",\"livemode\":false,\"created\":1793577600,\"automatic\":"
                + automatic + ",\"method\":\"" + method + "\",\"currency\":\"" + currency + "\",\"amount\":" + amount
                + "}";
    }

    private static String page(boolean more, String... payouts) {
        return "{\"has_more\":" + more + ",\"data\":[" + String.join(",", payouts) + "]}";
    }
}
