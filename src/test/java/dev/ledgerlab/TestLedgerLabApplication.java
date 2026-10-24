package dev.ledgerlab;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.ScenarioHttpReplay;
import dev.ledgerlab.testsupport.ScenarioReplay;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistrar;
import tools.jackson.databind.json.JsonMapper;

/** Test-only backend: fresh PostgreSQL and real HTTP inputs, never direct SQL seeding. */
public class TestLedgerLabApplication {
    private static Path scenarioDirectory() {
        String scenario = System.getenv().getOrDefault("LEDGERLAB_E2E_SCENARIO", "sample-a-1-3");
        if (!scenario.equals("sample-a-1-3") && !scenario.equals("ac-5-3"))
            throw new IllegalArgumentException("Unsupported E2E scenario");
        return Path.of("src/test/resources/scenarios", scenario);
    }

    public static void main(String[] args) throws Exception {
        var app = SpringApplication.from(LedgerLabApplication::main)
                .with(E2eConfiguration.class)
                .run(args)
                .getApplicationContext();
        var clock = app.getBean(MutableClock.class);
        var processor = app.getBean(ScenarioHttpReplay.class);
        var worker = app.getBean(InboxWorker.class);
        try (var http = HttpClient.newHttpClient()) {
            new ScenarioReplay(
                            scenarioDirectory(),
                            processor,
                            http,
                            "http://localhost:8080",
                            clock,
                            worker,
                            app.getBean(JdbcClient.class))
                    .runUntil("first");
        }
        // Loopback-only test control, created after seed assertions pass. Absent from production.
        var control = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8090), 0);
        control.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                String body;
                if (method.equals("GET") && (path.equals("/ready") || path.equals("/clock"))) {
                    body = Long.toString(clock.instant().getEpochSecond());
                } else if (method.equals("POST") && path.equals("/drain")) {
                    int attempts = 0;
                    while (worker.processAvailable() != 0) {
                        if (++attempts >= 100) throw new IllegalStateException("Worker did not drain");
                    }
                    processor.assertConsumed();
                    body = "ok";
                } else if (method.equals("POST")
                        && path.equals("/prepare-b1-replay")
                        && scenarioDirectory().endsWith("sample-a-1-3")) {
                    var manifest = JsonMapper.builder()
                            .build()
                            .readTree(Files.readAllBytes(scenarioDirectory().resolve("manifest.json")));
                    processor.expect(manifest.path("steps").get(2).path("responses"));
                    body = "ok";
                } else {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception | AssertionError error) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        control.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> control.stop(0)));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class E2eConfiguration {
        @Bean
        @Primary
        MutableClock e2eClock() {
            return new MutableClock(Instant.parse("2026-11-05T16:00:00Z"));
        }

        @Bean
        ScenarioHttpReplay e2eProcessor() throws IOException {
            return new ScenarioHttpReplay(scenarioDirectory());
        }

        @Bean
        DynamicPropertyRegistrar e2eProperties(ScenarioHttpReplay processor) {
            TestDatabase.Handle database = TestDatabase.freshBlank();
            return registry -> {
                registry.add("spring.datasource.url", database::url);
                registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
                registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
                registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
                registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
                registry.add("STRIPE_API_KEY", () -> "sk_test_scenario_synthetic_only");
                registry.add("ledgerlab.stripe.api-base-url", processor::baseUrl);
                registry.add("ledgerlab.inbox.scheduling-enabled", () -> "false");
                registry.add("ledgerlab.settlement.scheduling-enabled", () -> "false");
                registry.add("ledgerlab.bank.scheduling-enabled", () -> "false");
                registry.add("server.port", () -> "8080");
            };
        }
    }
}
