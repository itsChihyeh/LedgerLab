package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.sim.relay.RelayRecording;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

class OutboundRelayTest {
    private static final String KEY = "sk_test_" + "relay_synthetic_only";
    private static final String BODY = "charge=ch_test&metadata%5Bx%5D=a%2Bb+space&amount=500";
    private static final String REPLY = " {\"id\":\"re_test\",\"text\":\"中文\"}\n";
    private static final JsonMapper JSON = new JsonMapper();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger STATUS = new AtomicInteger(201);
    private static PostgreSQLContainer database;
    private static HttpServer upstream;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static String base;
    private static JdbcClient jdbc;
    private static JdbcClient admin;
    private static java.util.concurrent.ExecutorService executor;

    @BeforeAll
    static void start() throws Exception {
        database = TestDatabase.start();
        upstream = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(executor);
        upstream.createContext("/", e -> {
            RECEIVED.add(new Received(
                    e.getRequestMethod(),
                    e.getRequestURI().toASCIIString(),
                    e.getRequestBody().readAllBytes(),
                    Map.copyOf(e.getRequestHeaders())));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.getResponseHeaders().add("Request-Id", "req_synthetic");
            e.getResponseHeaders().add("Location", "/redirected");
            e.getResponseHeaders().add("X-Do-Not-Forward", "private");
            reply(e, STATUS.get(), REPLY);
        });
        upstream.start();
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                        "--sim.stripe.webhook-secret=synthetic-only",
                        "--sim.relay.outbound-enabled=true",
                        "--sim.relay.record-bodies=true",
                        "--sim.relay.upstream-api-url=http://127.0.0.1:"
                                + upstream.getAddress().getPort(),
                        "--sim.relay.target-url=http://127.0.0.1:"
                                + upstream.getAddress().getPort() + "/hook",
                        "--sim.clock.start=2026-11-02T12:00:00Z",
                        "--sim.relay.read-timeout=PT2S");
        base = "http://127.0.0.1:"
                + java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                        .getPort();
        client = HttpClient.newHttpClient();
        jdbc = JdbcClient.create(TestDatabase.role(database.getJdbcUrl(), "simulator"));
        admin = JdbcClient.create(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                database.getJdbcUrl(), "postgres", "test-admin"));
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (app != null) app.close();
        if (upstream != null) upstream.stop(0);
        if (executor != null) executor.close();
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        plan("[]");
        RECEIVED.clear();
        STATUS.set(201);
        admin.sql("truncate sim_chaos.api_exchange,sim_chaos.inbound_recording,sim_chaos.relay_log")
                .update();
    }

    @Test
    void ac_8_6_forwardPreservesWireBytesAllowedHeadersAndResponse() throws Exception {
        String path = "/v1/refunds/re_%74est?expand%5B%5D=a%2Bb&expand%5B%5D=x+y&empty=";
        var response = proxy("POST", path, BODY, KEY);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).isEqualTo(REPLY);
        assertThat(response.headers().firstValue("Request-Id")).contains("req_synthetic");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue("X-Do-Not-Forward")).isEmpty();
        assertThat(RECEIVED).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.uri()).isEqualTo(path);
            assertThat(r.body()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
            for (var expected : Map.of(
                            "Authorization",
                            "Bearer " + KEY,
                            "Stripe-Version",
                            "2026-09-30.endive",
                            "Idempotency-Key",
                            "rfd_synthetic",
                            "Content-Type",
                            "application/x-www-form-urlencoded")
                    .entrySet()) {
                assertThat(header(r, expected.getKey())).containsExactly(expected.getValue());
            }
            assertThat(header(r, "X-Do-Not-Forward")).isEmpty();
            assertThat(header(r, "Cookie")).isEmpty();
        });
        var row = jdbc.sql("select * from sim_chaos.api_exchange").query().singleRow();
        assertThat(row.get("path")).isEqualTo("/v1/refunds/re_%74est");
        assertThat(String.valueOf(row.get("query_parameter_names")))
                .isEqualTo("[\"expand[]\", \"expand[]\", \"empty\"]");
        assertThat(row.get("request_body")).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(row.get("response_body")).isEqualTo(REPLY.getBytes(StandardCharsets.UTF_8));
        assertThat(row.get("request_sha256")).isEqualTo(hash(BODY));
        assertThat(row.get("response_sha256")).isEqualTo(hash(REPLY));
        assertThat(row.get("upstream_status")).isEqualTo(201);
        assertNoCredentials(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void ac_8_6_forwardsMethodsAndNeverFollowsRedirects(String method) throws Exception {
        STATUS.set(307);
        assertThat(proxy(method, "/v1/objects", BODY, KEY).statusCode()).isEqualTo(307);
        assertThat(RECEIVED).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo(method);
            assertThat(r.body()).isEqualTo(BODY.getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    void ac_8_6_dropResponseExecutesUpstreamOnceAndReturns504() throws Exception {
        plan(
                "[{\"direction\":\"OUTBOUND\",\"method\":\"POST\",\"path_prefix\":\"/v1/refunds\",\"action\":\"DROP_RESPONSE\",\"times\":1}]");
        var first = proxy("POST", "/v1/refunds", BODY, KEY);
        assertThat(first.statusCode()).isEqualTo(504);
        assertThat(first.body()).isEqualTo("{\"error\":\"relay dropped upstream response\"}");
        assertThat(RECEIVED).hasSize(1);
        assertThat(jdbc.sql("select upstream_status from sim_chaos.api_exchange")
                        .query(Integer.class)
                        .single())
                .isEqualTo(201);
        assertThat(jdbc.sql("select response_sha256 from sim_chaos.api_exchange")
                        .query(String.class)
                        .single())
                .isEqualTo(hash(REPLY));
        assertThat(proxy("POST", "/v1/refunds", BODY, KEY).statusCode()).isEqualTo(201);
        assertThat(RECEIVED).hasSize(2);
        assertThat(stats().path("outbound").path("DROP_RESPONSE").asLong()).isEqualTo(1);
        assertThat(stats().path("outbound").path("PASS").asLong()).isEqualTo(1);
    }

    @Test
    void ac_8_6_mixedPlanMatchesByDirectionAndReplacementResetsCounters() throws Exception {
        String mixed = """
                [{"action":"DUPLICATE","copies":3},
                 {"direction":"OUTBOUND","method":"POST","path_prefix":"/v1/refunds","action":"FAIL_BEFORE_SEND","times":1},
                 {"direction":"OUTBOUND","method":"POST","path_prefix":"/v1/","action":"DROP_RESPONSE","times":1}]
                """;
        plan(mixed);
        assertThat(proxy("POST", "/v1/refunds", BODY, KEY).statusCode()).isEqualTo(503);
        assertThat(RECEIVED).isEmpty();
        assertThat(proxy("GET", "/v1/refunds", "", KEY).statusCode()).isEqualTo(201);
        assertThat(proxy("POST", "/v1/refunds", BODY, KEY).statusCode()).isEqualTo(504);
        plan(mixed);
        assertThat(proxy("POST", "/v1/refunds", BODY, KEY).statusCode()).isEqualTo(503);
        request("POST", "/relay/stripe", "{\"id\":\"evt_in\",\"type\":\"charge.succeeded\"}");
        assertThat(RECEIVED).hasSize(5);
        assertThat(stats().path("inbound").path("DUPLICATE").asLong()).isEqualTo(1);
        assertThat(stats().path("outbound").path("FAIL_BEFORE_SEND").asLong()).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from sim_chaos.inbound_recording")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(request("GET", "/sim/relay/stats?since=2999-01-01T00:00:00Z", "")
                        .body())
                .doesNotContain(":1", ":2");
        assertThat(request("GET", "/sim/relay/stats?since=bad", "").statusCode())
                .isEqualTo(400);
        assertThat(request("GET", "/sim/relay/plan", "").body()).contains("OUTBOUND", "FAIL_BEFORE_SEND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk_live_", "rk_live_"})
    void ac_8_2_liveCredentialsNeverReachUpstreamOrDatabase(String prefix) throws Exception {
        String key = prefix + "synthetic_only";
        assertThat(proxy("POST", "/v1/refunds", BODY, key).statusCode()).isEqualTo(403);
        assertThat(RECEIVED).isEmpty();
        assertNoCredentials(key);
        assertThat(jdbc.sql("select count(*) from sim_chaos.api_exchange")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_8_3_recordingSwitchPreservesRawInboundAndOmitsBodiesWhenOff() throws Exception {
        String event = " {\n\"id\":\"evt_raw\",\"type\":\"charge.succeeded\"}\r\n";
        request("POST", "/relay/stripe", event);
        var row = jdbc.sql("select * from sim_chaos.inbound_recording").query().singleRow();
        assertThat(row.get("body")).isEqualTo(event.getBytes(StandardCharsets.UTF_8));
        assertThat(row.get("sha256")).isEqualTo(hash(event));
        assertThat(row.get("event_id")).isEqualTo("evt_raw");
        assertThat(row.get("event_type")).isEqualTo("charge.succeeded");
        new RelayRecording(jdbc, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), false).inbound(null, null, new byte[] {0});
        assertThat(jdbc.sql("select count(*) from sim_chaos.inbound_recording")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void ac_8_6_deadlineCoversStalledResponseBody() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        upstream.createContext("/stall", e -> {
            e.sendResponseHeaders(200, 100);
            e.getResponseBody().write(1);
            e.getResponseBody().flush();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                e.close();
            }
        });
        try {
            var response = proxy("GET", "/stall", "", KEY);
            assertThat(entered.getCount()).isZero();
            assertThat(response.statusCode()).isEqualTo(504);
            var row = jdbc.sql("select * from sim_chaos.api_exchange").query().singleRow();
            assertThat(row.get("response_sha256")).isNull();
            assertThat(row.get("response_body")).isNull();
        } finally {
            release.countDown();
            upstream.removeContext("/stall");
        }
    }

    @Test
    void ac_8_3_recordingTablesAreAppendOnlyAndIsolated() throws Exception {
        proxy("POST", "/v1/refunds", BODY, KEY);
        request("POST", "/relay/stripe", "{}");
        for (String table : List.of("api_exchange", "inbound_recording")) {
            try (var connection = TestDatabase.role(database.getJdbcUrl(), "simulator")
                            .getConnection();
                    var statement = connection.createStatement()) {
                for (String sql : List.of(
                        "update sim_chaos." + table + " set received_at=received_at",
                        "delete from sim_chaos." + table,
                        "truncate sim_chaos." + table)) {
                    assertThatThrownBy(() -> statement.executeUpdate(sql))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("42501");
                }
            }
            for (String role : List.of("app", "readonly", "migrator")) {
                try (var connection =
                                TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                        var statement = connection.createStatement()) {
                    assertThatThrownBy(() -> statement.executeQuery("select * from sim_chaos." + table))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("42501");
                }
            }
        }
    }

    @Test
    void ac_8_3_disabledRecordingKeepsOnlyExchangeHashes() throws Exception {
        var clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
        var recorder = new RelayRecording(jdbc, clock, false);
        try (var relay = new dev.ledgerlab.sim.relay.OutboundRelay(
                app.getBean(dev.ledgerlab.sim.relay.Relay.class),
                recorder,
                clock,
                "http://127.0.0.1:" + upstream.getAddress().getPort(),
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(60))) {
            var incoming =
                    new org.springframework.mock.web.MockHttpServletRequest("POST", "/relay/stripe-api/v1/refunds");
            incoming.setContent(BODY.getBytes(StandardCharsets.UTF_8));
            incoming.addHeader("Authorization", "Bearer " + KEY);
            assertThat(relay.proxy(incoming).getStatusCode().value()).isEqualTo(201);
        }
        var row = jdbc.sql("select * from sim_chaos.api_exchange").query().singleRow();
        assertThat(row.get("request_body")).isNull();
        assertThat(row.get("response_body")).isNull();
        assertThat(row.get("request_sha256")).isEqualTo(hash(BODY));
        assertThat(row.get("response_sha256")).isEqualTo(hash(REPLY));
        assertThat(row.get("duration_ms")).isEqualTo(0L);
        assertThat(row.get("received_at")).isEqualTo(java.sql.Timestamp.from(clock.instant()));
        assertThat(recorder.stats(clock.instant()).get("outbound")).containsEntry("PASS", 1L);
        assertThat(recorder.stats(clock.instant().plusSeconds(1)).get("outbound"))
                .containsEntry("PASS", 0L);
        assertNoCredentials(KEY);
    }

    @Test
    void ac_8_3_databaseRejectsInvalidExchangeAndRecordingRows() throws Exception {
        proxy("POST", "/v1/refunds", BODY, KEY);
        request("POST", "/relay/stripe", "{}");
        try (var connection = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                database.getJdbcUrl(), "postgres", "test-admin")
                        .getConnection();
                var statement = connection.createStatement()) {
            for (String change : List.of(
                    "method='post'",
                    "path='relative'",
                    "path='/x?secret=value'",
                    "query_parameter_names='{}'",
                    "upstream_status=99",
                    "upstream_status=600",
                    "action='UNKNOWN'",
                    "duration_ms=-1",
                    "request_sha256='bad'",
                    "response_sha256='bad'",
                    "action='FAIL_BEFORE_SEND'",
                    "response_sha256=null")) {
                assertThatThrownBy(() -> statement.executeUpdate("update sim_chaos.api_exchange set " + change))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23514");
            }
            assertThatThrownBy(() -> statement.executeUpdate("update sim_chaos.inbound_recording set sha256='bad'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23514");
            for (var entry : Map.of(
                            "api_exchange",
                            List.of(
                                    "received_at",
                                    "recorded_at",
                                    "method",
                                    "path",
                                    "query_parameter_names",
                                    "action",
                                    "duration_ms",
                                    "request_sha256"),
                            "inbound_recording",
                            List.of("received_at", "recorded_at", "sha256", "body"))
                    .entrySet()) {
                for (String column : entry.getValue()) {
                    assertThatThrownBy(() -> statement.executeUpdate(
                                    "update sim_chaos." + entry.getKey() + " set " + column + "=null"))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("23502");
                }
                assertThatThrownBy(() -> statement.executeUpdate("insert into sim_chaos." + entry.getKey()
                                + " overriding system value select * from sim_chaos." + entry.getKey()))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23505");
            }
        }
    }

    @Test
    void ac_8_6_outboundRuleValidationLeavesCurrentPlanIntact() throws Exception {
        plan("[{\"action\":\"DROP\"}]");
        for (String fields : List.of(
                "\"times\":0",
                "\"times\":-1",
                "\"times\":1.5",
                "\"times\":2147483648",
                "\"times\":1,\"method\":\"post\"",
                "\"times\":1,\"path_prefix\":\"relative\"",
                "\"times\":1,\"action\":\"DUPLICATE\"",
                "\"times\":1,\"direction\":\"SIDEWAYS\"")) {
            // Build a single object without duplicate fields.
            var rule = JSON.createObjectNode()
                    .put("direction", "OUTBOUND")
                    .put("method", "POST")
                    .put("path_prefix", "/v1/refunds")
                    .put("action", "DROP_RESPONSE");
            rule.setAll((tools.jackson.databind.node.ObjectNode) JSON.readTree("{" + fields + "}"));
            assertThat(request("PUT", "/sim/relay/plan", "{\"rules\":[" + rule + "]}")
                            .statusCode())
                    .isEqualTo(400);
        }
        assertThat(request("GET", "/sim/relay/plan", "").body()).isEqualTo("{\"rules\":[{\"action\":\"DROP\"}]}");
    }

    @Test
    void ac_8_6_concurrentRequestsConsumeOneShotRuleExactlyOnce() throws Exception {
        plan(
                "[{\"direction\":\"OUTBOUND\",\"method\":\"POST\",\"path_prefix\":\"/v1/refunds\",\"action\":\"DROP_RESPONSE\",\"times\":1}]");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Integer> send = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return proxy("POST", "/v1/refunds", BODY, KEY).statusCode();
            };
            var first = workers.submit(send);
            var second = workers.submit(send);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(504, 201);
        }
        assertThat(RECEIVED).hasSize(2);
    }

    private static void assertNoCredentials(String key) {
        // JSON conversion covers every column, including text, arrays, JSON and bytea.
        for (String table : List.of("api_exchange", "inbound_recording", "relay_log")) {
            assertThat(jdbc.sql("select row_to_json(t)::text from sim_chaos." + table + " t")
                            .query(String.class)
                            .list())
                    .allSatisfy(text -> assertThat(text)
                            .doesNotContain(key, HexFormat.of().formatHex(key.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private static JsonNode stats() throws Exception {
        return JSON.readTree(request("GET", "/sim/relay/stats?since=2020-01-01T00:00:00Z", "")
                .body());
    }

    private static void plan(String rules) throws Exception {
        assertThat(request("PUT", "/sim/relay/plan", "{\"rules\":" + rules + "}")
                        .statusCode())
                .isEqualTo(200);
    }

    private static HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> proxy(String method, String path, String body, String key) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/relay/stripe-api" + path))
                        .header("Authorization", "Bearer " + key)
                        .header("Stripe-Version", "2026-09-30.endive")
                        .header("Idempotency-Key", "rfd_synthetic")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("X-Do-Not-Forward", "private")
                        .header("Cookie", "secret-cookie")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void reply(HttpExchange e, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
        e.close();
    }

    private static List<String> header(Received received, String name) {
        return received.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(List.of());
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("ArrayRecordComponent")
    private record Received(String method, String uri, byte[] body, Map<String, List<String>> headers) {}
}
