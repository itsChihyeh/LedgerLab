package dev.ledgerlab.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.testsupport.ContainerMounts;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-13.4: an alert travels the whole way. A signed webhook goes into the real application and nothing processes it;
 * the clock moves ten minutes; a real Prometheus scrapes the real management port, evaluates the real rule, and a real
 * Alertmanager delivers the notification to a receiver inside this JVM. Then the worker processes the event and the
 * receiver gets the {@code resolved} notification of the same group.
 *
 * <p>The containers run the repository's own files, except for the time fields and addresses that must change to make
 * a test of seconds out of a rule of minutes. The test first proves that nothing else differs.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "management.server.address=0.0.0.0"})
@Import(AlertDeliveryTest.Controls.class)
class AlertDeliveryTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path PROMETHEUS_CONFIG = Path.of("ops", "prometheus", "prometheus.yml");
    private static final Path RULES = Path.of("ops", "prometheus", "alerts.yml");
    private static final Path ALERTMANAGER_CONFIG = Path.of("ops", "alertmanager", "alertmanager.yml");
    private static final String HOST = "host.testcontainers.internal";
    private static final String ALERTMANAGER_ALIAS = "alertmanager";
    private static final String CHANGED = "<changed by the test>";
    private static final String RULE = "LedgerLabInboxStuck";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @AfterAll
    static void close() {
        HTTP.close();
        DB.drop();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Controls {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    MutableClock clock;

    @Autowired
    InboxWorker worker;

    @Test
    void ac_13_4_aStuckInboxIsDeliveredAsFiringAndThenAsResolved(@TempDir Path dir) throws Exception {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress(0), 0);
        receiver.createContext("/alerts", exchange -> {
            try (exchange) {
                received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                exchange.sendResponseHeaders(200, -1);
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(400, -1);
            }
        });
        receiver.start();
        int receiverPort = receiver.getAddress().getPort();

        Path prometheusDir = Files.createDirectory(dir.resolve("prometheus"));
        Path alertmanagerDir = Files.createDirectory(dir.resolve("alertmanager"));
        writePrometheusFiles(prometheusDir);
        writeAlertmanagerConfig(alertmanagerDir, receiverPort);
        // The images run as nobody; @TempDir is owner-only, which Linux enforces on bind mounts.
        ContainerMounts.readable(prometheusDir);
        ContainerMounts.readable(alertmanagerDir);

        try (Network network = Network.newNetwork()) {
            // Before any container that uses the host names starts.
            Testcontainers.exposeHostPorts(managementPort, receiverPort);
            try (GenericContainer<?> alertmanager = new GenericContainer<>(DockerImageName.parse(Amtool.IMAGE))
                            .withNetwork(network)
                            .withNetworkAliases(ALERTMANAGER_ALIAS)
                            .withFileSystemBind(
                                    alertmanagerDir.toAbsolutePath().toString(),
                                    "/etc/alertmanager",
                                    BindMode.READ_ONLY)
                            .withExposedPorts(9093)
                            .waitingFor(Wait.forHttp("/-/ready").forPort(9093));
                    GenericContainer<?> prometheus = new GenericContainer<>(DockerImageName.parse(Promtool.IMAGE))
                            .withNetwork(network)
                            .withFileSystemBind(
                                    prometheusDir.toAbsolutePath().toString(), "/etc/prometheus", BindMode.READ_ONLY)
                            .withExposedPorts(9090)
                            .waitingFor(Wait.forHttp("/-/ready").forPort(9090))) {
                alertmanager.start();
                prometheus.start();
                long started = System.nanoTime();

                clock.set(NOW);
                assertThat(deliver("evt_alert_delivery")).isEqualTo(200);
                // The worker is not scheduled, so the event just sits there while ten minutes pass.
                clock.set(NOW.plus(Duration.ofMinutes(10)));

                Supplier<String> logs = () -> "--- prometheus ---\n" + prometheus.getLogs() + "--- alertmanager ---\n"
                        + alertmanager.getLogs();
                JsonNode firing = await(
                        "a firing notification for " + RULE, () -> find(received, "firing", null), logs, received);
                JsonNode alert = firing.get("alerts").get(0);
                assertThat(alert.get("labels").get("alertname").asString()).isEqualTo(RULE);
                assertThat(alert.get("labels").get("severity").asString()).isEqualTo("page");
                assertThat(alert.get("annotations").get("summary").asString()).isNotBlank();
                assertThat(alert.get("annotations").get("runbook").asString())
                        .isEqualTo("docs/ops/runbook.md#inbox-stuck");
                String groupKey = firing.get("groupKey").asString();
                assertThat(find(received, "resolved", groupKey)).isEmpty();

                assertThat(worker.processAvailable()).isEqualTo(1);

                JsonNode resolved = await(
                        "a resolved notification of group " + groupKey,
                        () -> find(received, "resolved", groupKey),
                        logs,
                        received);
                assertThat(resolved.get("alerts")
                                .get(0)
                                .get("labels")
                                .get("alertname")
                                .asString())
                        .isEqualTo(RULE);
                System.out.println("AlertDeliveryTest: firing and resolved delivered "
                        + Duration.ofNanos(System.nanoTime() - started).toSeconds()
                        + " s after the containers were up");
            }
        } finally {
            receiver.stop(0);
        }
    }

    // ---- the test copies of the repository files ----

    @Test
    void ac_13_4_theTestCopiesDifferFromTheRealFilesOnlyInTimesAndAddresses(@TempDir Path dir) throws Exception {
        Path prometheusDir = Files.createDirectory(dir.resolve("prometheus"));
        Path alertmanagerDir = Files.createDirectory(dir.resolve("alertmanager"));
        writePrometheusFiles(prometheusDir);
        writeAlertmanagerConfig(alertmanagerDir, 4711);

        assertThat(neutralizedPrometheus(load(prometheusDir.resolve("prometheus.yml"))))
                .isEqualTo(neutralizedPrometheus(load(PROMETHEUS_CONFIG)));
        assertThat(neutralizedRules(load(prometheusDir.resolve("alerts.yml"))))
                .isEqualTo(neutralizedRules(load(RULES)));
        assertThat(neutralizedAlertmanager(load(alertmanagerDir.resolve("alertmanager.yml"))))
                .isEqualTo(neutralizedAlertmanager(load(ALERTMANAGER_CONFIG)));

        // The comparison is not vacuous: the copies do differ in the fields that were allowed to change...
        Map<String, Object> realRule = ruleOf(load(RULES));
        Map<String, Object> copiedRule = ruleOf(load(prometheusDir.resolve("alerts.yml")));
        assertThat(realRule).containsEntry("for", "5m");
        assertThat(copiedRule).containsEntry("for", "5s");
        // ...and the neutralizing is what hides them, so a change anywhere else would show.
        Map<String, Object> tampered = load(prometheusDir.resolve("alerts.yml"));
        ruleOf(tampered).put("expr", "vector(1)");
        assertThat(neutralizedRules(tampered)).isNotEqualTo(neutralizedRules(load(RULES)));
    }

    private void writePrometheusFiles(Path dir) throws IOException {
        Map<String, Object> config = load(PROMETHEUS_CONFIG);
        Map<String, Object> global = map(config.get("global"));
        global.put("scrape_interval", "1s");
        global.put("evaluation_interval", "1s");
        appTargets(config).put("targets", List.of(HOST + ":" + managementPort));
        alertmanagerTargets(config).put("targets", List.of(ALERTMANAGER_ALIAS + ":9093"));
        write(dir.resolve("prometheus.yml"), config);

        Map<String, Object> rules = load(RULES);
        ruleOf(rules).put("for", "5s");
        write(dir.resolve("alerts.yml"), rules);
    }

    private static void writeAlertmanagerConfig(Path dir, int receiverPort) throws IOException {
        Map<String, Object> config = load(ALERTMANAGER_CONFIG);
        Map<String, Object> route = map(config.get("route"));
        for (Map<String, Object> sub : maps(route.get("routes"))) {
            sub.put("group_wait", "1s");
            sub.put("group_interval", "2s");
            sub.put("repeat_interval", "10m");
        }
        for (Map<String, Object> receiver : maps(config.get("receivers")))
            for (Map<String, Object> webhook : maps(receiver.get("webhook_configs")))
                webhook.put("url", "http://" + HOST + ":" + receiverPort + "/alerts");
        write(dir.resolve("alertmanager.yml"), config);
    }

    /** The fields a test copy may change are replaced by a marker; everything else stays and gets compared. */
    private static Map<String, Object> neutralizedPrometheus(Map<String, Object> config) {
        Map<String, Object> global = map(config.get("global"));
        global.put("scrape_interval", CHANGED);
        global.put("evaluation_interval", CHANGED);
        appTargets(config).put("targets", CHANGED);
        alertmanagerTargets(config).put("targets", CHANGED);
        return config;
    }

    private static Map<String, Object> neutralizedRules(Map<String, Object> rules) {
        ruleOf(rules).put("for", CHANGED);
        return rules;
    }

    private static Map<String, Object> neutralizedAlertmanager(Map<String, Object> config) {
        for (Map<String, Object> sub : maps(map(config.get("route")).get("routes"))) {
            sub.put("group_wait", CHANGED);
            sub.put("group_interval", CHANGED);
            sub.put("repeat_interval", CHANGED);
        }
        for (Map<String, Object> receiver : maps(config.get("receivers")))
            for (Map<String, Object> webhook : maps(receiver.get("webhook_configs"))) webhook.put("url", CHANGED);
        return config;
    }

    private static Map<String, Object> appTargets(Map<String, Object> config) {
        for (Map<String, Object> scrape : maps(config.get("scrape_configs")))
            if ("ledgerlab-app".equals(scrape.get("job_name")))
                return maps(scrape.get("static_configs")).get(0);
        throw new AssertionError("no ledgerlab-app scrape job");
    }

    private static Map<String, Object> alertmanagerTargets(Map<String, Object> config) {
        return maps(maps(map(config.get("alerting")).get("alertmanagers"))
                        .get(0)
                        .get("static_configs"))
                .get(0);
    }

    private static Map<String, Object> ruleOf(Map<String, Object> rules) {
        for (Map<String, Object> group : maps(rules.get("groups")))
            for (Map<String, Object> rule : maps(group.get("rules"))) if (RULE.equals(rule.get("alert"))) return rule;
        throw new AssertionError("no rule " + RULE);
    }

    private static Map<String, Object> load(Path file) throws IOException {
        return map(new Yaml().load(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static void write(Path file, Map<String, Object> content) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Files.writeString(file, new Yaml(options).dump(content), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    // ---- waiting and sending ----

    /** The first notification with this status (and group, when given) that the receiver has got. */
    private static Optional<JsonNode> find(List<JsonNode> received, String status, String groupKey) {
        for (JsonNode payload : new ArrayList<>(received))
            if (status.equals(payload.get("status").asString())
                    && (groupKey == null
                            || groupKey.equals(payload.get("groupKey").asString()))) return Optional.of(payload);
        return Optional.empty();
    }

    /** Polls until the supplier has a value, for at most {@link #TIMEOUT}; the failure carries the container logs. */
    private static JsonNode await(
            String what, Supplier<Optional<JsonNode>> check, Supplier<String> logs, List<JsonNode> received)
            throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<JsonNode> found = check.get();
            if (found.isPresent()) return found.get();
            Thread.sleep(250);
        }
        throw new AssertionError(
                "no " + what + " within " + TIMEOUT.toSeconds() + " s; received " + received + "\n" + logs.get());
    }

    private int deliver(String id) throws Exception {
        byte[] body = """
                { "id":"%s", "type":"customer.created", "api_version":"2026-09-30.endive", "livemode":false,
                  "created":%d, "data":{"object":{"amount":10000}} }
                """.formatted(id, NOW.getEpochSecond()).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", StripeSignatures.sign(body, NOW.getEpochSecond()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
