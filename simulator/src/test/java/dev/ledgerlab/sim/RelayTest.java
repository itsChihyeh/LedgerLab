package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.sim.processor.SimulationClock;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import tools.jackson.databind.json.JsonMapper;

class RelayTest {
    private static final String SECRET = "synthetic-relay-test-only";
    private static final String SIGNATURE = "t=123,v1=unchanged,v1=second";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger STATUS = new AtomicInteger(204);
    private static PostgreSQLContainer database;
    private static HttpServer receiver;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static String base;
    private static JdbcClient jdbc;
    private static SimulationClock clock;

    @BeforeAll
    static void start() throws Exception {
        database = TestDatabase.start();
        receiver = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            RECEIVED.add(new Received(
                    exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders().getFirst("Stripe-Signature")));
            exchange.sendResponseHeaders(STATUS.get(), -1);
            exchange.close();
        });
        receiver.start();
        // Resolve the simulator's own ephemeral port at bean creation, after server startup.
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                        "--sim.stripe.webhook-secret=" + SECRET,
                        "--sim.stripe.webhook-url=http://127.0.0.1:${local.server.port}/relay/stripe",
                        "--sim.relay.target-url=http://127.0.0.1:"
                                + receiver.getAddress().getPort() + "/hook",
                        "--spring.main.lazy-initialization=true");
        base = "http://127.0.0.1:"
                + java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                        .getPort();
        client = HttpClient.newHttpClient();
        jdbc = JdbcClient.create(TestDatabase.role(database.getJdbcUrl(), "simulator"));
        clock = app.getBean(SimulationClock.class);
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (app != null) app.close();
        if (receiver != null) receiver.stop(0);
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        clock.advance(Duration.ofMinutes(4));
        plan("[]");
        RECEIVED.clear();
        STATUS.set(204);
        jdbc.sql("truncate sim_chaos.relay_log").update();
    }

    @Test
    void ac_2_1_passPreservesWireBytesSignatureAndHash() throws Exception {
        byte[] body = " {\n\"id\":\"evt_pass\", \"text\":\"中文\",\"type\":\"charge.succeeded\" }\r\n"
                .getBytes(StandardCharsets.UTF_8);
        relay(body);
        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.getFirst().body()).isEqualTo(body);
        assertThat(RECEIVED.getFirst().signature()).isEqualTo(SIGNATURE);
        assertThat(jdbc.sql("select payload_sha256 from sim_chaos.relay_log")
                        .query(String.class)
                        .list())
                .containsOnly(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(jdbc.sql("select attempt_no from sim_chaos.relay_log order by id")
                        .query(Integer.class)
                        .list())
                .containsExactly(0, 1);
    }

    @Test
    void ac_2_3_duplicateThreeTimesAndFirstMatchingRuleWins() throws Exception {
        plan("""
                [{"event_type":"charge.succeeded","action":"DUPLICATE","copies":3},
                 {"event_id":"evt_a","action":"DROP"}]
                """);
        byte[] body = event("a");
        relay(body);
        assertThat(RECEIVED).hasSize(3);
        for (var received : RECEIVED) {
            assertThat(received.body()).isEqualTo(body);
            assertThat(received.signature()).isEqualTo(SIGNATURE);
        }
        assertThat(jdbc.sql("select attempt_no from sim_chaos.relay_log order by id")
                        .query(Integer.class)
                        .list())
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void ac_2_2_delayBoundaryAndInvalidPlansAreAtomic() throws Exception {
        plan("[{\"action\":\"DELAY\",\"delay_seconds\":240}]");
        relay(event("delayed"));
        clock.advance(Duration.ofSeconds(239));
        assertThat(RECEIVED).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(RECEIVED).hasSize(1);
        var rejected = request("PUT", "/sim/relay/plan", "{\"rules\":[{\"action\":\"DELAY\",\"delay_seconds\":241}]}");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.body()).contains("更长的延迟用 fake Stripe 的 redeliver 模拟");
        for (String rule : new String[] {
            "{\"action\":\"DUPLICATE\",\"copies\":1}",
            "{\"action\":\"DELAY\",\"delay_seconds\":-1}",
            "{\"action\":\"DELAY\",\"delay_seconds\":1.5}",
            "{\"action\":\"HOLD_AND_REORDER\",\"after_events\":0}",
            "{\"action\":\"UNKNOWN\"}",
            "{\"action\":\"PASS\",\"event_id\":1}",
            "{\"action\":\"DUPLICATE\"}"
        }) {
            assertThat(request("PUT", "/sim/relay/plan", "{\"rules\":[" + rule + "]}")
                            .statusCode())
                    .isEqualTo(400);
        }
        assertThat(request("GET", "/sim/relay/plan", "").body()).contains("240");
    }

    @Test
    void ac_2_2_holdReordersThenTimeoutDoesNotSendAgain() throws Exception {
        plan("[{\"event_id\":\"evt_a\",\"action\":\"HOLD_AND_REORDER\",\"after_events\":1}]");
        relay(event("a"));
        assertThat(RECEIVED).isEmpty();
        relay(event("b"));
        assertThat(RECEIVED)
                .extracting(r -> JSON.readTree(r.body()).path("id").asText())
                .containsExactly("evt_b", "evt_a");
        clock.advance(Duration.ofMinutes(4));
        assertThat(RECEIVED).hasSize(2);
    }

    @Test
    void ac_2_2_holdWithoutFollowingEventsReleasesAt240Seconds() throws Exception {
        plan("[{\"action\":\"HOLD_AND_REORDER\",\"after_events\":1}]");
        relay(event("a"));
        clock.advance(Duration.ofSeconds(239));
        assertThat(RECEIVED).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        assertThat(RECEIVED).hasSize(1);
        assertThat(jdbc.sql("select action from sim_chaos.relay_log order by id")
                        .query(String.class)
                        .list())
                .containsExactly("HOLD_AND_REORDER", "HOLD_TIMEOUT_RELEASE");
    }

    @Test
    void ac_2_3_duplicatesCountAsOneLaterEventAndDropDoesNotCount() throws Exception {
        plan("""
                [{"event_id":"evt_a","action":"HOLD_AND_REORDER","after_events":2},
                 {"event_id":"evt_drop","action":"DROP"},
                 {"event_id":"evt_b","action":"DUPLICATE","copies":3}]
                """);
        relay(event("a"));
        relay(event("drop"));
        relay(event("b"));
        assertThat(RECEIVED).hasSize(3);
        plan("[]"); // Replacing a plan does not discard already scheduled events.
        relay(event("c"));
        assertThat(RECEIVED)
                .extracting(r -> JSON.readTree(r.body()).path("id").asText())
                .containsExactly("evt_b", "evt_b", "evt_b", "evt_c", "evt_a");
    }

    @Test
    void ac_2_1_dropOnlyLogsReceiptAndMalformedJsonStillPasses() throws Exception {
        plan("[{\"action\":\"DROP\"}]");
        relay(event("drop"));
        assertThat(RECEIVED).isEmpty();
        assertThat(jdbc.sql("select action from sim_chaos.relay_log")
                        .query(String.class)
                        .list())
                .containsExactly("DROP");
        plan("[]");
        relay(new byte[] {0, -1, 123});
        assertThat(RECEIVED.getFirst().body()).containsExactly((byte) 0, (byte) -1, (byte) 123);
        assertThat(jdbc.sql("select count(*) from sim_chaos.relay_log where event_id is null")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void ac_2_1_downstream500StillAcknowledgesReceipt() throws Exception {
        STATUS.set(500);
        relay(event("failure"));
        assertThat(jdbc.sql("select http_status from sim_chaos.relay_log where attempt_no=1")
                        .query(Integer.class)
                        .single())
                .isEqualTo(500);
    }

    @Test
    void ac_8_2_fakeStripeThroughRelayPassesIndependentSignatureVerification() throws Exception {
        var result = request("POST", "/sim/charges", "{\"amount\":10000}");
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(RECEIVED).hasSize(1);
        var received = RECEIVED.getFirst();
        String[] fields = received.signature().split(",", -1);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        hmac.update((fields[0].substring(2) + ".").getBytes(StandardCharsets.UTF_8));
        assertThat(MessageDigest.isEqual(
                        hmac.doFinal(received.body()), HexFormat.of().parseHex(fields[1].substring(3))))
                .isTrue();
        assertThat(JSON.readTree(received.body()).path("id").asText())
                .isEqualTo(JSON.readTree(result.body()).path("event_id").asText());
    }

    @Test
    void ac_2_1_emptyBodyAndAbsentSignatureAreForwarded() throws Exception {
        var response = request("POST", "/relay/stripe", "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.getFirst().body()).isEmpty();
        assertThat(RECEIVED.getFirst().signature()).isNull();
    }

    @Test
    void ac_2_2_delayedEventsReleaseInDeadlineOrderAndZeroDelayIsImmediate() throws Exception {
        plan("""
                [{"event_id":"evt_a","action":"DELAY","delay_seconds":20},
                 {"event_id":"evt_b","action":"DELAY","delay_seconds":10},
                 {"event_id":"evt_c","action":"DELAY","delay_seconds":10},
                 {"action":"DELAY","delay_seconds":0}]
                """);
        relay(event("a"));
        relay(event("b"));
        relay(event("c"));
        relay(event("immediate"));
        assertThat(RECEIVED).hasSize(1);
        clock.advance(Duration.ofSeconds(20));
        assertThat(RECEIVED)
                .extracting(r -> JSON.readTree(r.body()).path("id").asText())
                .containsExactly("evt_immediate", "evt_b", "evt_c", "evt_a");
    }

    @Test
    void ac_2_1_networkErrorIsRecorded() throws Exception {
        HttpServer closed = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int port = closed.getAddress().getPort();
        closed.start();
        closed.stop(0);
        var relay = new dev.ledgerlab.sim.relay.Relay(jdbc, client, clock, "http://127.0.0.1:" + port + "/hook");
        relay.receive(event("network"), SIGNATURE);
        var result = jdbc.sql("select http_status,error from sim_chaos.relay_log where attempt_no=1")
                .query()
                .singleRow();
        assertThat(result.get("http_status")).isNull();
        assertThat(result.get("error")).isNotNull();
    }

    @Test
    void ac_8_2_relayTableIsIsolatedAndConstraintsAreEnforced() throws Exception {
        relay(event("schema"));
        for (String role : new String[] {"app", "readonly", "migrator"}) {
            try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                    var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("select * from sim_chaos.relay_log"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("42501");
            }
        }
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            for (String change : new String[] {
                "action='UNKNOWN'",
                "attempt_no=-1",
                "payload_sha256='bad'",
                "http_status=200",
                "action='HOLD_TIMEOUT_RELEASE'"
            }) {
                assertThatThrownBy(() -> statement.executeUpdate(
                                "update sim_chaos.relay_log set " + change + " where attempt_no=0"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23514");
            }
            for (String change : new String[] {
                "http_status=99", "http_status=600", "http_status=null", "error='both'", "action='DROP'"
            }) {
                assertThatThrownBy(() -> statement.executeUpdate(
                                "update sim_chaos.relay_log set " + change + " where attempt_no=1"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23514");
            }
            assertThatThrownBy(
                            () -> statement.executeUpdate(
                                    "insert into sim_chaos.relay_log overriding system value select * from sim_chaos.relay_log limit 1"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23505");
            assertThatThrownBy(
                            () -> statement.executeUpdate(
                                    "insert into sim_chaos.relay_log (receipt_id,event_id,action,attempt_no,http_status,error,recorded_at,payload_sha256) select receipt_id,event_id,action,attempt_no,http_status,error,recorded_at,payload_sha256 from sim_chaos.relay_log limit 1"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23505");
            for (String column : new String[] {"receipt_id", "action", "attempt_no", "recorded_at", "payload_sha256"}) {
                assertThatThrownBy(() -> statement.executeUpdate("update sim_chaos.relay_log set " + column + "=null"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23502");
            }
        }
    }

    private static byte[] event(String id) {
        return ("{\"id\":\"evt_" + id + "\",\"type\":\"charge.succeeded\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static void relay(byte[] body) throws Exception {
        var response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/relay/stripe"))
                        .header("Content-Type", "application/json")
                        .header("Stripe-Signature", SIGNATURE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    }

    private static void plan(String rules) throws Exception {
        var response = request("PUT", "/sim/relay/plan", "{\"rules\":" + rules + "}");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    }

    private static HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("ArrayRecordComponent")
    private record Received(byte[] body, String signature) {}
}
