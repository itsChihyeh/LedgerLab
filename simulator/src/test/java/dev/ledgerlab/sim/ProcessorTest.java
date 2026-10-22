package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.sim.processor.SeededIds;
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
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class ProcessorTest {
    private static final Instant START = Instant.now().minus(Duration.ofDays(3));
    private static final String SECRET = "synthetic-signing-test-only";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LinkedBlockingQueue<Received> RECEIVED = new LinkedBlockingQueue<>();
    private static final AtomicInteger STATUS = new AtomicInteger(204);
    private static PostgreSQLContainer database;
    private static HttpServer receiver;
    private static ConfigurableApplicationContext app;
    private static HttpClient client;
    private static String base;
    private static JdbcClient jdbc;

    @BeforeAll
    static void start() throws Exception {
        database = TestDatabase.start();
        receiver = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            RECEIVED.add(new Received(
                    exchange.getRequestBody().readAllBytes(),
                    exchange.getRequestHeaders().getFirst("Stripe-Signature"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestMethod()));
            exchange.sendResponseHeaders(STATUS.get(), -1);
            exchange.close();
        });
        receiver.start();
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                        "--sim.stripe.webhook-secret=" + SECRET,
                        "--sim.stripe.webhook-url=http://127.0.0.1:"
                                + receiver.getAddress().getPort() + "/hook",
                        "--sim.clock.start=" + START,
                        "--sim.seed=42");
        base = "http://127.0.0.1:"
                + java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                        .getPort();
        client = HttpClient.newHttpClient();
        jdbc = JdbcClient.create(TestDatabase.role(database.getJdbcUrl(), "simulator"));
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (app != null) app.close();
        if (receiver != null) receiver.stop(0);
        if (database != null) database.stop();
    }

    @Test
    void ac_2_1_snapshotAndIndependentSignatureMatchStoredBytes() throws Exception {
        RECEIVED.clear();
        var result = post("/sim/charges", "{\"amount\":10000}");
        var event = get(result.path("event_id").asText());
        String payload = event.path("payload").asText();
        var received = RECEIVED.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.body()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.contentType()).isEqualTo("application/json");
        assertThat(received.signature()).matches("t=[0-9]+,v1=[a-f0-9]{64}");
        assertThat(valid(received.body(), received.signature())).isTrue();
        byte[] changed = received.body().clone();
        changed[0] ^= 1;
        assertThat(valid(changed, received.signature())).isFalse();
        var e = JSON.readTree(payload);
        assertThat(e.path("object").asText()).isEqualTo("event");
        assertThat(e.path("type").asText()).isEqualTo("charge.succeeded");
        assertThat(e.path("api_version").asText()).isEqualTo("2026-09-30.endive");
        assertThat(e.path("id").asText()).isEqualTo(result.path("event_id").asText());
        assertThat(e.path("created").isIntegralNumber()).isTrue();
        assertThat(e.path("livemode").asBoolean()).isFalse();
        var c = e.path("data").path("object");
        assertThat(c.path("id").asText()).startsWith("ch_");
        assertThat(c.path("payment_intent").asText()).startsWith("pi_");
        assertThat(c.path("object").asText()).isEqualTo("charge");
        assertThat(c.path("amount").isIntegralNumber()).isTrue();
        assertThat(c.path("amount_captured").isIntegralNumber()).isTrue();
        assertThat(c.path("amount").asLong()).isEqualTo(10000);
        assertThat(c.path("amount_captured").asLong()).isEqualTo(10000);
        assertThat(c.path("currency").asText()).isEqualTo("cad");
        assertThat(c.path("captured").asBoolean()).isTrue();
        assertThat(c.path("status").asText()).isEqualTo("succeeded");
        assertThat(c.path("livemode").asBoolean()).isFalse();
        assertThat(c.path("created").isIntegralNumber()).isTrue();
        assertThat(event.path("deliveries").get(0).path("http_status").asInt()).isEqualTo(204);
        assertThat(jdbc.sql("select payload from sim_processor.event where id = ?")
                        .param(e.path("id").asText())
                        .query(String.class)
                        .single())
                .isEqualTo(payload);
    }

    @Test
    void ac_2_2_redeliveryKeepsOldEventAndRefreshesSignature() throws Exception {
        var result = post("/sim/charges", "{\"amount\":5000}");
        String id = result.path("event_id").asText();
        var before = get(id);
        RECEIVED.clear();
        post("/sim/events/" + id + "/redeliver", "{}");
        var after = get(id);
        var retry = RECEIVED.poll(5, TimeUnit.SECONDS);
        assertThat(retry).isNotNull();
        assertThat(retry.body()).isEqualTo(before.path("payload").asText().getBytes(StandardCharsets.UTF_8));
        assertThat(valid(retry.body(), retry.signature())).isTrue();
        assertThat(after.path("payload")).isEqualTo(before.path("payload"));
        assertThat(after.path("deliveries").size()).isEqualTo(2);
        assertThat(after.path("deliveries").get(1).path("signed_at").asLong())
                .isGreaterThan(
                        before.path("deliveries").get(0).path("signed_at").asLong());
        assertThat(JSON.readTree(after.path("payload").asText()).path("created").asLong())
                .isLessThan(Instant.now().minus(Duration.ofDays(2)).getEpochSecond());
    }

    @Test
    void ac_2_5_reissueChangesEventIdentityButKeepsChargeSnapshot() throws Exception {
        var first = post("/sim/charges", "{\"amount\":7000}");
        var second = post("/sim/events/" + first.path("event_id").asText() + "/reissue", "{}");
        assertThat(second.path("event_id")).isNotEqualTo(first.path("event_id"));
        var oldEvent = JSON.readTree(
                get(first.path("event_id").asText()).path("payload").asText());
        var newEvent = JSON.readTree(
                get(second.path("event_id").asText()).path("payload").asText());
        assertThat(newEvent.path("data")).isEqualTo(oldEvent.path("data"));
    }

    @Test
    void ac_2_1_httpFailureIsRecordedWithoutRetry() throws Exception {
        STATUS.set(500);
        try {
            var result = post("/sim/charges", "{\"amount\":100}");
            var deliveries = get(result.path("event_id").asText()).path("deliveries");
            assertThat(deliveries.size()).isEqualTo(1);
            assertThat(deliveries.get(0).path("http_status").asInt()).isEqualTo(500);
        } finally {
            STATUS.set(204);
        }
    }

    @Test
    void ac_1_1_defaultsCanBeOverriddenForRejectedCharges() throws Exception {
        var result = post("/sim/charges", """
                {"amount":-1,"amount_captured":0,"currency":"usd","captured":false,
                 "status":"failed","livemode":true}
                """);
        var c = result.path("charge");
        assertThat(c.path("amount").asLong()).isEqualTo(-1);
        assertThat(c.path("amount_captured").asLong()).isZero();
        assertThat(c.path("currency").asText()).isEqualTo("usd");
        assertThat(c.path("captured").asBoolean()).isFalse();
        assertThat(c.path("status").asText()).isEqualTo("failed");
        assertThat(c.path("livemode").asBoolean()).isTrue();
    }

    @Test
    @org.junit.jupiter.api.Order(Integer.MAX_VALUE)
    void ac_2_2_simulatedClockAdvancesThreeDays() throws Exception {
        var clock = app.getBean(SimulationClock.class);
        clock.advance(Duration.ofDays(3));
        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofDays(3)));
        var result = post("/sim/charges", "{\"amount\":100}");
        var event = JSON.readTree(
                get(result.path("event_id").asText()).path("payload").asText());
        assertThat(event.path("created").asLong()).isEqualTo(clock.instant().getEpochSecond());
        assertThat(event.path("data").path("object").path("created").asLong())
                .isEqualTo(clock.instant().getEpochSecond());
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_2_5_seededIdsAreReproducible() {
        var first = new SeededIds(42);
        var second = new SeededIds(42);
        for (int i = 0; i < 20; i++) {
            for (String prefix : new String[] {"ch", "pi", "evt"}) {
                assertThat(first.next(prefix)).isEqualTo(second.next(prefix)).startsWith(prefix + "_");
            }
        }
    }

    @Test
    void ac_5_13_databaseIsolationIsBidirectional() throws Exception {
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("select * from ledger.v_account"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
        }
        for (String role : new String[] {"app", "readonly", "migrator"}) {
            try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                    var statement = connection.createStatement()) {
                for (String table : new String[] {"charge", "event", "webhook_delivery"}) {
                    assertThatThrownBy(() -> statement.executeQuery("select * from sim_processor." + table))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("42501");
                }
            }
        }
    }

    @Test
    void ac_2_1_transportErrorIsRecordedAndEventRemainsAvailable() throws Exception {
        var result = post("/sim/charges", "{\"amount\":100}");
        String id = result.path("event_id").asText();
        HttpServer closed = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int port = closed.getAddress().getPort();
        closed.start();
        closed.stop(0);
        var sender = new dev.ledgerlab.sim.processor.WebhookSender(
                jdbc, client, java.time.Clock.systemUTC(), "http://127.0.0.1:" + port + "/hook", SECRET);
        sender.deliver(id, get(id).path("payload").asText());
        var delivery = get(id).path("deliveries").get(1);
        assertThat(delivery.path("http_status").isNull()).isTrue();
        assertThat(delivery.path("error").asText()).isNotBlank();
    }

    @Test
    void ac_2_1_invalidControlInputDoesNotCreateEvents() throws Exception {
        long before = jdbc.sql("select count(*) from sim_processor.event")
                .query(Long.class)
                .single();
        for (String body : new String[] {
            "{}",
            "{\"amount\":1.5}",
            "{\"amount\":\"100\"}",
            "{\"amount\":9223372036854775808}",
            "{\"amount\":100,\"captured\":\"true\"}",
            "{\"amount\":100,\"currency\":123}"
        }) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/sim/charges"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.sql("select count(*) from sim_processor.event")
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
        for (String suffix : new String[] {"", "/redeliver", "/reissue"}) {
            var builder = HttpRequest.newBuilder(URI.create(base + "/sim/events/evt_missing" + suffix));
            var response = client.send(
                    suffix.isEmpty()
                            ? builder.GET().build()
                            : builder.POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Test
    void ac_5_13_migrationConstraintsRejectInvalidRows() throws Exception {
        var result = post("/sim/charges", "{\"amount\":100}");
        String eventId = result.path("event_id").asText();
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            for (String table : new String[] {"charge", "event"}) {
                assertThatThrownBy(() -> statement.executeUpdate("insert into sim_processor." + table
                                + " select * from sim_processor." + table + " limit 1"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23505");
                assertThatThrownBy(
                                () -> statement.executeUpdate("insert into sim_processor." + table + " default values"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23502");
            }
            for (String values : new String[] {"null, null", "200, 'error'"}) {
                assertThatThrownBy(() -> statement.executeUpdate("insert into sim_processor.webhook_delivery "
                                + "(event_id,target_url,signed_at,http_status,error,recorded_at) values ('"
                                + eventId + "','http://localhost',1,"
                                + values + ",now())"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23514");
            }
            assertThatThrownBy(
                            () -> statement.executeUpdate(
                                    "insert into sim_processor.webhook_delivery "
                                            + "(event_id,target_url,signed_at,http_status,recorded_at) values ('evt_absent','http://localhost',1,200,now())"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23503");
            assertThatThrownBy(
                            () -> statement.executeUpdate(
                                    "insert into sim_processor.event "
                                            + "(id,charge_id,type,api_version,created,payload,recorded_at) values ('evt_absent','ch_absent','charge.succeeded','test',1,'{}',now())"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23503");
            assertThatThrownBy(() -> statement.executeUpdate("create schema forbidden"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("42501");
        }
    }

    @Test
    void ac_5_13_everyRequiredColumnAndDeliveryPrimaryKeyAreEnforced() throws Exception {
        post("/sim/charges", "{\"amount\":100}");
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            for (String table : new String[] {"charge", "event", "webhook_delivery"}) {
                var columns = jdbc.sql("select column_name from information_schema.columns "
                                + "where table_schema = 'sim_processor' and table_name = ? order by ordinal_position")
                        .param(table)
                        .query(String.class)
                        .list();
                var required = jdbc.sql("select column_name from information_schema.columns "
                                + "where table_schema = 'sim_processor' and table_name = ? and is_nullable = 'NO'")
                        .param(table)
                        .query(String.class)
                        .list();
                for (String column : required) {
                    String selections = columns.stream()
                            .map(c ->
                                    java.util.Objects.equals(c, column) ? "null" : java.util.Objects.requireNonNull(c))
                            .collect(java.util.stream.Collectors.joining(", "));
                    String sql = "insert into sim_processor." + table + " (" + String.join(", ", columns)
                            + ") overriding system value select " + selections + " from sim_processor." + table
                            + " limit 1";
                    assertThatThrownBy(() -> statement.executeUpdate(sql))
                            .as(table + "." + column)
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("23502");
                }
            }
            assertThatThrownBy(() -> statement.executeUpdate("insert into sim_processor.webhook_delivery "
                            + "overriding system value select * from sim_processor.webhook_delivery limit 1"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23505");
        }
    }

    private static JsonNode post(String path, String body) throws Exception {
        var response = client.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static JsonNode get(String id) throws Exception {
        var response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/sim/events/" + id))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    // Independent verifier: no reference to the production signing implementation.
    private static boolean valid(byte[] body, String header) throws Exception {
        String[] fields = header.split(",", -1);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        hmac.update((fields[0].substring(2) + ".").getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(hmac.doFinal(body), HexFormat.of().parseHex(fields[1].substring(3)));
    }

    @SuppressWarnings("ArrayRecordComponent") // Capture the exact wire bytes for assertions.
    private record Received(byte[] body, String signature, String contentType, String method) {}
}
