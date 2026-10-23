package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.sim.bank.Bank;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

class PayoutTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "sk_test_synthetic_simulator_only";
    private static final List<JsonNode> RECEIVED = new CopyOnWriteArrayList<>();
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
            RECEIVED.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
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
                        "--sim.stripe.webhook-secret=synthetic-payout-signing-only",
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
        if (client != null) client.close();
        if (app != null) app.close();
        if (receiver != null) receiver.stop(0);
        if (database != null) database.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        ok("POST", "/sim/clock/advance", "{\"seconds\":240}");
        ok("PUT", "/sim/relay/plan", "{\"rules\":[]}");
        RECEIVED.clear();
        jdbc.sql("truncate sim_processor.charge, sim_processor.event, sim_processor.webhook_delivery, "
                        + "sim_processor.balance_transaction, sim_processor.payout, sim_processor.fault_plan cascade")
                .update();
        jdbc.sql("truncate sim_bank.account, sim_bank.fault_plan, sim_truth.injected_fault cascade")
                .update();
    }

    @Test
    void ac_5_1_threeChargesProduceNetPayoutAndImmutableEventSnapshots() throws Exception {
        for (long amount : new long[] {10000, 5000, 4000}) charge(amount);
        var created = ok("POST", "/sim/payouts", "{}");
        var payout = created.path("payout");
        String id = payout.path("id").asText();
        assertThat(payout.path("amount").asLong()).isEqualTo(18359);
        assertThat(payout.path("automatic").asBoolean()).isTrue();
        assertThat(payout.path("method").asText()).isEqualTo("standard");
        assertThat(payout.path("reconciliation_status").asText()).isEqualTo("in_progress");
        assertThat(payout.path("status").asText()).isEqualTo("pending");
        var lines = lines(id, 100, "").path("data");
        assertThat(lines.size()).isEqualTo(4);
        long net = 0;
        for (var line : lines) {
            assertThat(line.path("net").asLong())
                    .isEqualTo(line.path("amount").asLong() - line.path("fee").asLong());
            if (line.path("type").asText().equals("payout")) {
                assertThat(line.path("amount").asLong()).isEqualTo(-18359);
                assertThat(line.path("id")).isEqualTo(payout.path("balance_transaction"));
            } else net += line.path("net").asLong();
        }
        assertThat(net).isEqualTo(18359);
        ok("POST", "/sim/payouts/" + id + "/reconcile", "{}");
        ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        assertThat(RECEIVED.stream().map(e -> e.path("type").asText()).toList())
                .containsExactly(
                        "charge.succeeded",
                        "charge.succeeded",
                        "charge.succeeded",
                        "payout.created",
                        "payout.reconciliation_completed",
                        "payout.paid");
        assertThat(RECEIVED.get(3).path("data").path("object")).isEqualTo(payout);
        assertThat(RECEIVED.get(4)
                        .path("data")
                        .path("object")
                        .path("reconciliation_status")
                        .asText())
                .isEqualTo("completed");
        assertThat(ok("GET", "/v1/payouts/" + id, "").path("status").asText()).isEqualTo("paid");
        assertThat(request("POST", "/sim/payouts", "{}", KEY).statusCode()).isEqualTo(400);
        String eventId = created.path("event_id").asText();
        var original = ok("GET", "/sim/events/" + eventId, "").path("payload");
        ok("POST", "/sim/events/" + eventId + "/redeliver", "{}");
        var reissued = ok("POST", "/sim/events/" + eventId + "/reissue", "{}");
        var copy = JSON.readTree(
                ok("GET", "/sim/events/" + reissued.path("event_id").asText(), "")
                        .path("payload")
                        .asText());
        assertThat(copy.path("type").asText()).isEqualTo("payout.created");
        assertThat(copy.path("data")).isEqualTo(JSON.readTree(original.asText()).path("data"));
        assertThat(copy.path("api_version").asText()).isEqualTo("2026-09-30.endive");
        assertThat(ok("GET", "/sim/events/" + eventId, "").path("payload")).isEqualTo(original);
    }

    @Test
    void ac_5_18_failedPayoutReturnsFundsExactlyOnceForExampleH() throws Exception {
        charge(10000);
        String first = payout();
        ok("POST", "/sim/payouts/" + first + "/reconcile", "{}");
        var failed = ok("POST", "/sim/payouts/" + first + "/failed", "{\"failure_code\":\"account_closed\"}")
                .path("payout");
        ok("POST", "/sim/payouts/" + first + "/failed", "{\"failure_code\":\"account_closed\"}");
        assertThat(failed.path("failure_code").asText()).isEqualTo("account_closed");
        String next = payout();
        var rows = lines(next, 100, "").path("data");
        assertThat(rows.size()).isEqualTo(2);
        for (var row : rows)
            if (!row.path("type").asText().equals("payout")) {
                assertThat(row.path("type").asText()).isEqualTo("payout_failure");
                assertThat(row.path("amount").asLong()).isEqualTo(9680);
                assertThat(row.path("source").asText()).isEqualTo(first);
                assertThat(row.path("id")).isEqualTo(failed.path("failure_balance_transaction"));
            }
        assertThat(RECEIVED.stream()
                        .filter(e -> e.path("type").asText().equals("payout.failed"))
                        .count())
                .isEqualTo(1);
        assertThat(request("POST", "/sim/payouts/" + first + "/paid", "{}", KEY).statusCode())
                .isEqualTo(409);
        assertThat(lines(first, 100, "").path("data").size()).isEqualTo(2);
    }

    @Test
    void ac_5_10_paginationIsStableAndSecondPageFailureIsPersistent() throws Exception {
        for (int i = 0; i < 24; i++) charge(100);
        String id = payout();
        var first = lines(id, 10, "");
        assertThat(first.path("object").asText()).isEqualTo("list");
        assertThat(first.path("url").asText()).isEqualTo("/v1/balance_transactions");
        assertThat(first.path("has_more").asBoolean()).isTrue();
        String cursor = first.path("data").get(9).path("id").asText();
        ok("POST", "/sim/payouts/" + id + "/faults", "{\"fail_page\":2}");
        for (int i = 0; i < 2; i++)
            assertThat(request(
                                    "GET",
                                    "/v1/balance_transactions?payout=" + id + "&limit=10&starting_after=" + cursor,
                                    "",
                                    KEY)
                            .statusCode())
                    .isEqualTo(500);
        ok("POST", "/sim/payouts/" + id + "/faults", "{\"fail_page\":0}");
        var second = lines(id, 10, cursor);
        var third = lines(id, 10, second.path("data").get(9).path("id").asText());
        assertThat(second.path("data").size()).isEqualTo(10);
        assertThat(second.path("has_more").asBoolean()).isTrue();
        assertThat(third.path("data").size()).isEqualTo(5);
        assertThat(third.path("has_more").asBoolean()).isFalse();
        List<String> ids = new ArrayList<>();
        for (var page : List.of(first, second, third))
            for (var row : page.path("data")) ids.add(row.path("id").asText());
        assertThat(ids).doesNotHaveDuplicates().isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(ok("GET", "/v1/balance_transactions?payout=" + id, "")
                        .path("data")
                        .size())
                .isEqualTo(10);
        assertThat(jdbc.sql("select count(*) from sim_processor.fault_plan")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void ac_5_17_payoutListUsesHalfOpenTimeRangeAndCursor() throws Exception {
        long start =
                Instant.parse(ok("GET", "/sim/clock", "").path("now").asText()).getEpochSecond();
        charge(100);
        String first = payout();
        ok("POST", "/sim/clock/advance", "{\"seconds\":1}");
        charge(100);
        String second = payout();
        String path = "/v1/payouts?created%5Bgte%5D=" + start + "&created%5Blt%5D=" + (start + 2) + "&limit=1";
        var page = ok("GET", path, "");
        assertThat(page.path("data").get(0).path("id").asText()).isEqualTo(second);
        assertThat(page.path("has_more").asBoolean()).isTrue();
        assertThat(ok("GET", path + "&starting_after=" + second, "")
                        .path("data")
                        .get(0)
                        .path("id")
                        .asText())
                .isEqualTo(first);
        assertThat(ok("GET", "/v1/payouts?created%5Bgte%5D=" + (start + 1) + "&created%5Blt%5D=" + (start + 2), "")
                        .path("data")
                        .size())
                .isEqualTo(1);
        for (String query :
                List.of("limit=0", "limit=101", "limit=bad", "starting_after=po_missing", "created%5Bgte%5D=x"))
            assertThat(request("GET", "/v1/payouts?" + query, "", KEY).statusCode())
                    .isEqualTo(400);
        assertThat(request("GET", "/v1/payouts/po_missing", "", KEY).statusCode())
                .isEqualTo(404);
    }

    @Test
    void ac_8_2_readApiRequiresExactTestKey() throws Exception {
        for (String key : List.of("", "sk_test_wrong", "sk_live_synthetic", "rk_test_wrong"))
            for (String path :
                    List.of("/v1/payouts", "/v1/payouts/po_missing", "/v1/balance_transactions?payout=po_missing"))
                assertThat(request("GET", path, "", key).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/v1/payouts", "", KEY).statusCode()).isEqualTo(200);
    }

    @Test
    void ac_5_7_faultsRecordFeeDuplicateAndUnknownRows() throws Exception {
        var charge = ok("POST", "/sim/charges", "{\"amount\":5000,\"fee\":205}");
        String id = payout();
        String txn = charge.path("charge").path("balance_transaction").asText();
        ok(
                "POST",
                "/sim/payouts/" + id + "/faults",
                "{\"duplicate_transaction\":\"" + txn + "\",\"extra_line\":{\"type\":\"charge\",\"amount\":1000}}");
        ok("POST", "/sim/payouts/" + id + "/faults", "{\"extra_line\":{\"type\":\"adjustment\",\"amount\":-20}}");
        var rows = lines(id, 100, "").path("data");
        assertThat(rows.size()).isEqualTo(5);
        int copies = 0;
        for (var row : rows)
            if (row.path("id").asText().equals(txn)) {
                copies++;
                assertThat(row.path("fee").asLong()).isEqualTo(205);
            }
        assertThat(copies).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from sim_processor.fault_plan")
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
        assertThat(ok("GET", "/v1/payouts/" + id, "").path("amount").asLong()).isEqualTo(4795);
    }

    @Test
    void ac_5_17_unsupportedPayoutsCanBeManufactured() throws Exception {
        charge(10000);
        var manual = ok("POST", "/sim/payouts", "{\"automatic\":false,\"method\":\"instant\",\"amount\":-50}")
                .path("payout");
        assertThat(manual.path("automatic").asBoolean()).isFalse();
        assertThat(manual.path("method").asText()).isEqualTo("instant");
        assertThat(manual.path("amount").asLong()).isEqualTo(-50);
        assertThat(manual.path("reconciliation_status").asText()).isEqualTo("not_applicable");
        assertThat(request("POST", "/sim/payouts/" + manual.path("id").asText() + "/reconcile", "{}", KEY)
                        .statusCode())
                .isEqualTo(409);
    }

    @Test
    void ac_2_2_clockHttpReleasesDelayAndHoldTimeoutAndRejectsBackwardTime() throws Exception {
        ok("PUT", "/sim/relay/plan", "{\"rules\":[{\"action\":\"DELAY\",\"delay_seconds\":30}]}");
        charge(100);
        assertThat(RECEIVED).isEmpty();
        ok("POST", "/sim/clock/advance", "{\"seconds\":29}");
        assertThat(RECEIVED).isEmpty();
        ok("POST", "/sim/clock/advance", "{\"seconds\":1}");
        assertThat(RECEIVED).hasSize(1);
        ok("PUT", "/sim/relay/plan", "{\"rules\":[{\"action\":\"HOLD_AND_REORDER\",\"after_events\":1}]}");
        charge(100);
        ok("POST", "/sim/clock/advance", "{\"seconds\":240}");
        assertThat(RECEIVED).hasSize(2);
        var before = ok("GET", "/sim/clock", "");
        for (String body : List.of("{\"seconds\":-1}", "{\"seconds\":1.5}", "{}", "{\"seconds\":9223372036854775807}"))
            assertThat(request("POST", "/sim/clock/advance", body, KEY).statusCode())
                    .isEqualTo(400);
        assertThat(ok("GET", "/sim/clock", "")).isEqualTo(before);
    }

    @Test
    void ac_5_17_concurrentPayoutsCannotSpendSameTransactionsTwice() throws Exception {
        charge(10000);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++)
                tasks.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return request("POST", "/sim/payouts", "{}", KEY).statusCode();
                }));
            assertThat(List.of(
                            tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 400);
        }
        assertThat(jdbc.sql("select count(*) from sim_processor.payout")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void ac_5_13_newTablesAreIsolatedAndChecksRejectInvalidData() throws Exception {
        charge(10000);
        String id = payout();
        ok("POST", "/sim/payouts/" + id + "/faults", "{\"fail_page\":2}");
        for (String role : List.of("app", "readonly", "migrator"))
            try (var connection = TestDatabase.role(database.getJdbcUrl(), role).getConnection();
                    var statement = connection.createStatement()) {
                for (String table : List.of("payout", "balance_transaction", "fault_plan"))
                    assertThatThrownBy(() -> statement.executeQuery("select * from sim_processor." + table))
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("42501");
            }
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            for (String update : List.of(
                    "payout set status='other'",
                    "payout set reconciliation_status='other'",
                    "payout set method='other'",
                    "payout set amount=9007199254740992",
                    "payout set fail_page=-1",
                    "balance_transaction set type='other'",
                    "balance_transaction set net=net+1",
                    "balance_transaction set fee=9007199254740992, net=amount-9007199254740992",
                    "balance_transaction set amount=9007199254740991, fee=-1, net=9007199254740992",
                    "balance_transaction set amount=9007199254740992, net=9007199254740992-fee"))
                assertThatThrownBy(() -> statement.executeUpdate("update sim_processor." + update))
                        .as(update)
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23514");
            assertThatThrownBy(() ->
                            statement.executeUpdate("update sim_processor.balance_transaction set payout='po_missing'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23503");
        }
    }

    @Test
    void ac_5_13_requiredColumnsKeysAndPayoutEventReferenceAreEnforced() throws Exception {
        charge(10000);
        String id = payout();
        ok("POST", "/sim/payouts/" + id + "/faults", "{\"fail_page\":1}");
        try (var connection =
                        TestDatabase.role(database.getJdbcUrl(), "simulator").getConnection();
                var statement = connection.createStatement()) {
            for (String table : List.of("payout", "balance_transaction", "fault_plan")) {
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
                    String selected = columns.stream()
                            .map(c ->
                                    java.util.Objects.equals(c, column) ? "null" : java.util.Objects.requireNonNull(c))
                            .collect(java.util.stream.Collectors.joining(","));
                    assertThatThrownBy(() -> statement.executeUpdate("insert into sim_processor." + table + " select "
                                    + selected + " from sim_processor." + table + " limit 1"))
                            .as(table + "." + column)
                            .isInstanceOf(SQLException.class)
                            .extracting(e -> ((SQLException) e).getSQLState())
                            .isEqualTo("23502");
                }
                assertThatThrownBy(() -> statement.executeUpdate("insert into sim_processor." + table
                                + " select * from sim_processor." + table + " limit 1"))
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23505");
            }
            assertThatThrownBy(() -> statement.executeUpdate("update sim_processor.event set payout_id='po_missing'"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo("23503");
        }
    }

    @Test
    void ac_8_2_restrictedTestKeyWorksAndLiveConfigurationIsRejected() {
        var scenario = scenario(82, java.time.Duration.ZERO);
        var restricted = new dev.ledgerlab.sim.processor.PayoutController(
                scenario.payouts(), scenario.clock(), "rk_test_synthetic", app.getBean(Bank.class));
        assertThat(restricted
                        .list(java.util.Map.of(), "Bearer rk_test_synthetic")
                        .path("data")
                        .isEmpty())
                .isTrue();
        var disabled = new dev.ledgerlab.sim.processor.PayoutController(
                scenario.payouts(), scenario.clock(), "", app.getBean(Bank.class));
        assertThatThrownBy(() -> disabled.list(java.util.Map.of(), "Bearer "))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> new dev.ledgerlab.sim.processor.PayoutController(
                        scenario.payouts(), scenario.clock(), "sk_live_synthetic", app.getBean(Bank.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("sk_live_synthetic");
    }

    @Test
    void ac_5_18_paidThenFailedAndRepeatedTransitionsRemainConsistent() throws Exception {
        charge(10000);
        String id = payout();
        ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        ok("POST", "/sim/payouts/" + id + "/failed", "{}");
        var reconciled = ok("POST", "/sim/payouts/" + id + "/reconcile", "{}");
        assertThat(ok("POST", "/sim/payouts/" + id + "/reconcile", "{}").path("event_id"))
                .isEqualTo(reconciled.path("event_id"));
        assertThat(jdbc.sql("select count(*) from sim_processor.event where payout_id=?")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(4);
        assertThat(ok("GET", "/v1/payouts/" + id, "").path("status").asText()).isEqualTo("failed");
        assertThat(lines(payout(), 100, "").path("data").size()).isEqualTo(2);
    }

    @Test
    void ac_5_17_pendingBalanceBecomesAvailableAtConfiguredDelay() throws Exception {
        var scenario = scenario(81, java.time.Duration.ofSeconds(30));
        scenario.processor().create(JSON.readTree("{\"amount\":10000}"));
        assertThatThrownBy(() -> scenario.payouts().create(JSON.readTree("{}")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        scenario.clock().advance(java.time.Duration.ofSeconds(29));
        assertThatThrownBy(() -> scenario.payouts().create(JSON.readTree("{}")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        scenario.clock().advance(java.time.Duration.ofSeconds(1));
        var result = JSON.valueToTree(scenario.payouts().create(JSON.readTree("{}")));
        assertThat(result.path("payout").path("amount").asLong()).isEqualTo(9680);
        assertThat(scenario.payouts()
                        .lines(result.path("payout").path("id").asText(), java.util.Map.of())
                        .path("data")
                        .size())
                .isEqualTo(2);
    }

    @Test
    void ac_4_13_sameSeedAndOperationsReproduceChargePayoutFailureAndEventIds() throws Exception {
        List<List<String>> runs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            reset();
            var scenario = scenario(77, java.time.Duration.ZERO);
            scenario.processor().create(JSON.readTree("{\"amount\":10000,\"fee\":320}"));
            String id = JSON.valueToTree(scenario.payouts().create(JSON.readTree("{}")))
                    .path("payout")
                    .path("id")
                    .asText();
            scenario.payouts().transition(id, "reconcile", JSON.readTree("{}"));
            scenario.payouts().transition(id, "failed", JSON.readTree("{}"));
            scenario.payouts().create(JSON.readTree("{}"));
            runs.add(jdbc.sql("select id from sim_processor.charge union all select id from sim_processor.payout "
                            + "union all select id from sim_processor.balance_transaction union all select id from sim_processor.event "
                            + "union all select id from sim_processor.fault_plan order by id")
                    .query(String.class)
                    .list());
        }
        assertThat(runs.get(0)).isEqualTo(runs.get(1)).hasSize(13);
    }

    @Test
    void ac_5_19_invalidAndOverflowInputsRollBackWithoutPartialEventsOrFaults() throws Exception {
        for (String body : List.of(
                "{\"amount\":9223372036854775807}",
                "{\"amount\":100,\"fee\":9223372036854775807}",
                "{\"amount\":100,\"fee\":1.5}")) {
            assertThat(request("POST", "/sim/charges", body, KEY).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.sql("select count(*) from sim_processor.charge")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_processor.event")
                        .query(Long.class)
                        .single())
                .isZero();
        ok("POST", "/sim/charges", "{\"amount\":50,\"fee\":60}");
        assertThat(request("POST", "/sim/payouts", "{}", KEY).statusCode()).isEqualTo(400);
        charge(10000);
        String id = payout();
        for (String body : List.of(
                "{}",
                "{\"fail_page\":-1}",
                "{\"fail_page\":2147483648}",
                "{\"duplicate_transaction\":\"txn_missing\"}",
                "{\"extra_line\":{\"type\":\"refund\",\"amount\":10}}",
                "{\"unknown\":true}",
                "{\"fail_page\":2,\"extra_line\":{\"amount\":9223372036854775807}}")) {
            assertThat(request("POST", "/sim/payouts/" + id + "/faults", body, KEY)
                            .statusCode())
                    .isEqualTo(400);
        }
        assertThat(jdbc.sql("select fail_page from sim_processor.payout where id=?")
                        .param(id)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_processor.fault_plan")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        for (String body : List.of("{\"method\":\"wire\"}", "{\"automatic\":1}"))
            assertThat(request("POST", "/sim/payouts", body, KEY).statusCode()).isEqualTo(400);
    }

    @Test
    void ac_5_4_paidNotifiesBankAndClockAdvanceExportsActualShortDeposit() throws Exception {
        charge(10000);
        String id = payout();
        ok(
                "PUT",
                "/sim/bank/plan",
                JSON.createObjectNode()
                        .put("payout_ref", id)
                        .put("scenario", "http-t07")
                        .put("fault", "SHORT")
                        .put("parameter", 1)
                        .put("memo", "银行, \"memo\"")
                        .toString());
        var paid = ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        ok("POST", "/sim/events/" + paid.path("event_id").asText() + "/redeliver", "{}");
        ok("POST", "/sim/events/" + paid.path("event_id").asText() + "/reissue", "{}");
        assertThat(ok("GET", "/sim/bank/transactions", "").isEmpty()).isTrue();
        assertThat(jdbc.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        var due = jdbc.sql("select due_date from sim_bank.incoming_transfer")
                .query(java.time.LocalDate.class)
                .single();
        var time = app.getBean(dev.ledgerlab.sim.processor.SimulationClock.class);
        long seconds = java.time.Duration.between(
                        time.instant(),
                        due.atTime(12, 0)
                                .atZone(java.time.ZoneId.of("America/Toronto"))
                                .toInstant())
                .getSeconds();
        ok("POST", "/sim/clock/advance", "{\"seconds\":" + seconds + "}");
        assertThat(jdbc.sql("select balance from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isEqualTo(9679);
        var lines = ok("GET", "/sim/bank/transactions", "");
        assertThat(lines.size()).isEqualTo(1);
        assertThat(lines.get(0).path("amount").asLong()).isEqualTo(9679);
        var response = request("GET", "/sim/bank/statements/" + due, "", KEY);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).contains("text/csv", "UTF-8");
        assertThat(response.body()).contains("\"银行, \"\"memo\"\"\"").endsWith("T,1,9679,0,0,9679\r\n");
        assertThat(request("GET", "/sim/bank/statements/" + due + "?corrupt=balance", "", KEY)
                        .body())
                .endsWith("T,1,9679,0,0,9680\r\n");
        assertThat(request("GET", "/sim/bank/statements/" + due, "", KEY).body())
                .isEqualTo(response.body());
        assertThat(request("GET", "/sim/bank/statements/" + due + "?corrupt=bad", "", KEY)
                        .statusCode())
                .isEqualTo(400);
        assertThat(request("GET", "/sim/bank/statements/2025-01-01", "", KEY).statusCode())
                .isEqualTo(400);
        assertThat(request("GET", "/sim/bank/statements/not-a-date", "", KEY).statusCode())
                .isEqualTo(400);
        assertThat(request("PUT", "/sim/bank/plan", "{\"parameter\":0.5}", KEY).statusCode())
                .isEqualTo(400);
        var zoned = time.withZone(java.time.ZoneId.of("America/Toronto"));
        assertThat(zoned.getZone()).isEqualTo(java.time.ZoneId.of("America/Toronto"));
        assertThat(zoned.withZone(java.time.ZoneOffset.UTC).instant()).isEqualTo(time.instant());
        assertThat(time.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void ac_5_13_bankNotificationFailureRollsBackPaidEventAndRetryIsAtomic() throws Exception {
        charge(10000);
        String id = payout();
        ok("PUT", "/sim/bank/plan", "{\"fault\":\"SHORT\",\"parameter\":9680}");
        assertThat(request("POST", "/sim/payouts/" + id + "/paid", "{}", KEY).statusCode())
                .isEqualTo(400);
        assertThat(ok("GET", "/v1/payouts/" + id, "").path("status").asText()).isEqualTo("pending");
        assertThat(jdbc.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_processor.event where type='payout.paid'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_truth.injected_fault")
                        .query(Long.class)
                        .single())
                .isZero();
        ok("PUT", "/sim/bank/plan", "{}");
        ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        assertThat(jdbc.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void ac_5_17_unsupportedPaidPayoutsNeverBecomeBankIncome() throws Exception {
        for (String body : List.of("{\"automatic\":false}", "{\"method\":\"instant\"}", "{\"amount\":-10}")) {
            charge(10000);
            String id =
                    ok("POST", "/sim/payouts", body).path("payout").path("id").asText();
            ok("POST", "/sim/payouts/" + id + "/paid", "{}");
        }
        assertThat(jdbc.sql("select count(*) from sim_bank.incoming_transfer")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(ok("GET", "/sim/bank/transactions", "").isEmpty()).isTrue();
    }

    private static Scenario scenario(long seed, java.time.Duration delay) {
        var source = TestDatabase.role(database.getJdbcUrl(), "simulator");
        var db = JdbcClient.create(source);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var clock = new dev.ledgerlab.sim.processor.SimulationClock(Instant.parse("2026-10-23T12:00:00Z"));
        var real = java.time.Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), java.time.ZoneOffset.UTC);
        var ids = new dev.ledgerlab.sim.processor.SeededIds(seed);
        var sender = new dev.ledgerlab.sim.processor.WebhookSender(
                db,
                client,
                real,
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook",
                "synthetic-test-only");
        var balances = new dev.ledgerlab.sim.processor.BalanceTransactions(db, clock, real, ids, delay);
        var processor = new dev.ledgerlab.sim.processor.Processor(db, manager, clock, real, ids, sender, balances);
        return new Scenario(
                processor,
                new dev.ledgerlab.sim.processor.Payouts(
                        db, manager, balances, processor, clock, real, ids, new Bank(db, manager, clock)),
                clock);
    }

    private record Scenario(
            dev.ledgerlab.sim.processor.Processor processor,
            dev.ledgerlab.sim.processor.Payouts payouts,
            dev.ledgerlab.sim.processor.SimulationClock clock) {}

    private static JsonNode charge(long amount) throws Exception {
        return ok("POST", "/sim/charges", "{\"amount\":" + amount + "}");
    }

    private static String payout() throws Exception {
        return ok("POST", "/sim/payouts", "{}").path("payout").path("id").asText();
    }

    private static JsonNode lines(String payout, int limit, String cursor) throws Exception {
        return ok(
                "GET",
                "/v1/balance_transactions?payout=" + payout + "&limit=" + limit
                        + (cursor.isEmpty() ? "" : "&starting_after=" + cursor),
                "");
    }

    private static JsonNode ok(String method, String path, String body) throws Exception {
        var response = request(method, path, body, KEY);
        assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> request(String method, String path, String body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (!key.isEmpty()) builder.header("Authorization", "Bearer " + key);
        return client.send(
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
