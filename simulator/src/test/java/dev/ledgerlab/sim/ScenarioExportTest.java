package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.sim.scenario.ScenarioRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ScenarioExportTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static PostgreSQLContainer database;
    private static ConfigurableApplicationContext app;
    private static ScenarioRunner runner;
    private static JdbcClient jdbc;

    @BeforeAll
    static void start() {
        database = TestDatabase.start();
        app = new SpringApplication(SimulatorApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.password=test-simulator",
                        "--sim.stripe.webhook-secret=synthetic-scenario-only",
                        "--sim.stripe.webhook-url=http://127.0.0.1:1/unused",
                        "--sim.clock.start=2026-11-05T16:00:00Z");
        runner = app.getBean(ScenarioRunner.class);
        jdbc = app.getBean(JdbcClient.class);
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
        if (database != null) database.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ac-5-3", "sample-a-1-3", "sample-h", "sample-a-1-8", "sample-d"})
    void ac_5_13_exportMatchesCommittedBytesAndRepeatsWithoutState(String name) throws Exception {
        Map<String, String> result = runner.run(name);
        assertThat(runner.run(name)).isEqualTo(result);
        Path directory = Path.of("../src/test/resources/scenarios", name);
        if (Boolean.getBoolean("scenarios.export")) {
            for (var file : result.entrySet()) {
                Path target = directory.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            }
        }
        String command =
                "Drift: re-export from repository root with .\\mvnw.cmd -B -f simulator/pom.xml -Dtest=ScenarioExportTest -Dscenarios.export=true test";
        assertThat(directory).withFailMessage(command).exists();
        try (var paths = Files.walk(directory)) {
            assertThat(paths.filter(Files::isRegularFile)
                            .map(p -> directory.relativize(p).toString().replace('\\', '/'))
                            .toList())
                    .withFailMessage(command)
                    .containsExactlyInAnyOrderElementsOf(result.keySet());
        }
        for (var file : result.entrySet())
            assertThat(Files.readAllBytes(directory.resolve(file.getKey())))
                    .as("%s/%s: %s", name, file.getKey(), command)
                    .isEqualTo(file.getValue().getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.sql("select count(*) from sim_processor.event")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(result.values()).noneMatch(s -> s.contains("Stripe-Signature") || s.contains("Authorization"));
    }

    @Test
    void ac_8_7_refundControlActionsUseScenarioClockAndRollbackTheirPlans() {
        var scenario = JSON.readTree("""
                {"format_version":1,"seed":42,"start_clock":"2026-10-23T12:00:00Z","checkpoints":[],
                 "actions":[
                   {"action":"refund_fault","request":{"force_pending":true,"timeout_after_create":1}},
                   {"action":"advance_clock","seconds":60},
                   {"action":"process_refunds"},
                   {"action":"advance_clock","to":"2026-10-23T12:02:00Z"},
                   {"action":"charge","alias":"P1","request":{"amount":5000}},
                   {"action":"refund_fault","request":{"charge":"$P1","suppress_events":true}}
                 ]}
                """);
        var result = runner.run(scenario);
        assertThat(runner.run(scenario)).isEqualTo(result);
        assertThat(JSON.readTree(result.get("events/001.json")).path("created").asLong())
                .isEqualTo(java.time.Instant.parse("2026-10-23T12:02:00Z").getEpochSecond());
        assertThat(jdbc.sql("select count(*) from sim_processor.refund_faults")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_processor.fault_plan")
                        .query(Long.class)
                        .single())
                .isZero();
        ((ObjectNode) scenario.path("actions").get(1)).put("seconds", -1);
        assertThatThrownBy(() -> runner.run(scenario)).hasMessageContaining("Invalid seconds");
    }

    @Test
    void ac_5_13_endpointReturnsSameBundleAndRejectsUnknownNames() throws Exception {
        int port = java.util.Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                .getPort();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/sim/scenarios/sample-h/run"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(response.body())).isEqualTo(JSON.valueToTree(runner.run("sample-h")));
        }
        assertThatThrownBy(() -> runner.run("../sample-h")).hasMessageContaining("400");
        assertThatThrownBy(() -> runner.run("missing")).hasMessageContaining("404");
    }

    @Test
    void ac_5_13_invalidActionsRollbackAndPopulatedSimulatorIsPreserved() throws Exception {
        ObjectNode scenario = scenario("sample-a-1-3");
        ((ObjectNode) scenario.path("actions").get(1)).put("action", "unknown");
        assertThatThrownBy(() -> runner.run(scenario)).hasMessageContaining("Invalid action");
        assertThat(jdbc.sql("select count(*) from sim_processor.event")
                        .query(Long.class)
                        .single())
                .isZero();
        jdbc.sql(
                        "insert into sim_bank.fault_plan values ('*','manual','NONE',0,'preserve',timestamptz '2026-11-10T00:00:00Z')")
                .update();
        try {
            assertThatThrownBy(() -> runner.run("sample-h")).hasMessageContaining("409");
            assertThat(jdbc.sql("select memo from sim_bank.fault_plan")
                            .query(String.class)
                            .single())
                    .isEqualTo("preserve");
        } finally {
            jdbc.sql("delete from sim_bank.fault_plan where target_ref='*'").update();
        }
    }

    @Test
    void ac_5_13_truthIsCopiedFromScenarioRatherThanCalculatedFromEvidence() throws Exception {
        ObjectNode scenario = scenario("sample-a-1-3");
        Map<String, String> before = runner.run(scenario);
        ((ObjectNode) scenario.path("checkpoints").get(0))
                .putArray("expected_findings")
                .addObject()
                .put("category", "INTENTIONALLY_WRONG")
                .put("subject_id", "$P1");
        Map<String, String> after = runner.run(scenario);
        for (var file : before.entrySet())
            if (!file.getKey().equals("truth.json"))
                assertThat(after.get(file.getKey())).isEqualTo(file.getValue());
        assertThat(after.get("truth.json")).contains("INTENTIONALLY_WRONG");
    }

    @Test
    void ac_5_13_repeatedTransitionDoesNotAttachPhantomReadsToEarlierDelivery() throws Exception {
        ObjectNode input = scenario("sample-a-1-3");
        Map<String, String> original = runner.run(input);
        ((tools.jackson.databind.node.ArrayNode) input.path("actions"))
                .insert(3, input.path("actions").get(2).deepCopy());
        assertThat(runner.run(input)).isEqualTo(original);
    }

    @Test
    void ac_5_13_exportCapturesEveryPageAndBankFaultUsesExecutedAmount() throws Exception {
        ObjectNode scenario = scenario("sample-a-1-3");
        var actions = scenario.putArray("actions");
        for (int i = 0; i < 101; i++) {
            var action = actions.addObject().put("action", "charge").put("alias", "P" + i);
            action.putObject("request").put("amount", 10000);
        }
        actions.addObject().put("action", "payout").put("alias", "B1").putObject("request");
        actions.addObject().put("action", "reconcile").put("payout", "B1").putObject("request");
        actions.addObject()
                .put("action", "bank_fault")
                .putObject("request")
                .put("payout_ref", "$B1")
                .put("fault", "SHORT")
                .put("parameter", 1);
        actions.addObject().put("action", "paid").put("payout", "B1").putObject("request");
        actions.addObject().put("action", "advance").put("to", "2026-11-12T12:00:00Z");
        actions.addObject().put("action", "bank_export").put("date", "2026-11-12");
        actions.addObject().put("action", "snapshot").put("business_date", "2026-11-10");
        var files = runner.run(scenario);
        assertThat(JSON.readTree(files.get("rest/001.json")).path("has_more").asBoolean())
                .isTrue();
        assertThat(JSON.readTree(files.get("rest/002.json")).path("has_more").asBoolean())
                .isFalse();
        assertThat(files.get("bank/001.csv")).contains(",CREDIT,977679,").endsWith("T,1,977679,0,0,977679\r\n");
    }

    @Test
    void ac_5_13_simulatorSourcesNeverReferenceApplicationPackages() throws Exception {
        String root = "dev." + "ledgerlab.";
        var pattern = java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(root) + "([a-zA-Z_][a-zA-Z_0-9]*)");
        try (var paths = Files.walk(Path.of("src"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(path));
                while (matcher.find())
                    assertThat(matcher.group(1)).as("Dependency in %s", path).isEqualTo("sim");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-truth",
                "unknown-alias",
                "duplicate-alias",
                "backward-clock",
                "invalid-header",
                "invalid-omit"
            })
    void ac_5_13_invalidScenarioCannotLeavePartialState(String failure) throws Exception {
        ObjectNode input = scenario("sample-a-1-3");
        switch (failure) {
            case "missing-truth" -> ((ObjectNode) input.path("checkpoints").get(0)).remove("expected_findings");
            case "unknown-alias" -> ((ObjectNode) input.path("actions").get(2)).put("payout", "missing");
            case "duplicate-alias" -> ((ObjectNode) input.path("actions").get(1)).put("alias", "P1");
            case "backward-clock" -> ((ObjectNode) input.path("actions").get(5)).put("to", "2026-11-01T00:00:00Z");
            case "invalid-header" -> input.put("format_version", 2);
            case "invalid-omit" -> {
                ObjectNode action = (ObjectNode) input.path("actions").get(3);
                action.put("action", "processor_fault").put("payout", "B1");
                action.putObject("request").put("omit_transaction", "txn_missing");
            }
            default -> throw new AssertionError(failure);
        }
        assertThatThrownBy(() -> runner.run(input)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("select count(*) from sim_processor.charge")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from sim_bank.account")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private static ObjectNode scenario(String name) throws Exception {
        try (var input = ScenarioExportTest.class.getResourceAsStream("/scenarios/" + name + ".json")) {
            return (ObjectNode) JSON.readTree(input);
        }
    }
}
