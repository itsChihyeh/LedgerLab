package dev.ledgerlab;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.ScenarioHttpReplay;
import dev.ledgerlab.testsupport.ScenarioReplay;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

class ScenarioTruthTest {
    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock scenarioClock() {
            return new MutableClock(Instant.parse("2026-11-05T16:00:00Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ac-5-3", "sample-a-1-3", "sample-h"})
    void ac_5_7_ac_5_13_externalScenarioTruthHasNoFalsePositivesOrNegatives(String name) throws Exception {
        var db = TestDatabase.freshBlank();
        Path directory = Path.of("src/test/resources/scenarios", name);
        try (var processor = new ScenarioHttpReplay(directory);
                var http = HttpClient.newHttpClient();
                var app = new SpringApplication(LedgerLabApplication.class, Config.class)
                        .run(
                                "--server.port=0",
                                "--spring.datasource.url=" + db.url(),
                                "--spring.datasource.password=" + TestDatabase.APP_PASSWORD,
                                "--spring.flyway.password=" + TestDatabase.MIGRATOR_PASSWORD,
                                "--ledgerlab.readonly-datasource.password=" + TestDatabase.READONLY_PASSWORD,
                                "--ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET,
                                "--ledgerlab.stripe.api-key=sk_test_scenario_synthetic_only",
                                "--ledgerlab.stripe.api-base-url=" + processor.baseUrl(),
                                "--ledgerlab.inbox.scheduling-enabled=false",
                                "--ledgerlab.settlement.scheduling-enabled=false",
                                "--ledgerlab.bank.scheduling-enabled=false")) {
            int port = Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                    .getPort();
            new ScenarioReplay(
                            directory,
                            processor,
                            http,
                            "http://127.0.0.1:" + port,
                            app.getBean(MutableClock.class),
                            app.getBean(InboxWorker.class),
                            app.getBean(JdbcClient.class))
                    .run();
            assertThat(app.getBean(LedgerAudit.class).check()).isEmpty();
        } finally {
            db.drop();
        }
    }

    @Test
    void ac_5_13_applicationAndTestsNeverReferenceSimulatorPackages() throws Exception {
        String prohibited = "dev.ledgerlab." + "sim";
        try (var paths = Files.walk(Path.of("src"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList())
                assertThat(Files.readString(path)).as("Dependency in %s", path).doesNotContain(prohibited);
        }
    }
}
