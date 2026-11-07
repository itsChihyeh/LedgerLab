package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.ledgerlab.testsupport.LogEvents;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AC-13.2 against the real application: {@code ledgerlab_recon_runs_total} read from the text a Prometheus server
 * would scrape on the management port, with runs driven through the real HTTP endpoint and through
 * {@link ReconRuns}. A run that fails inside its transaction is made by revoking {@code SELECT} on
 * {@code recon.v_input_evidence} from {@code app} for the duration of the call (the run reads it as its first query).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.bank.scheduling-enabled=false"})
@Import(ReconRunMetricsTest.Config.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconRunMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-13T14:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-11-13T05:00:00Z");
    private static final Instant EVIDENCE = Instant.parse("2026-11-13T13:00:00Z");
    private static final List<String> KINDS = List.of("PROCESSOR", "BANK", "AGING");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)\\{(.*)} (\\S+)$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        Clock reconClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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
    static void stop() {
        HTTP.close();
        DB.drop();
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    ReconRuns runs;

    @Autowired
    JdbcClient jdbc;

    private LogEvents events;

    @BeforeEach
    void attachLog() {
        events = LogEvents.attach();
    }

    @AfterEach
    void detachLog() {
        events.close();
    }

    @Test
    @Order(1)
    void ac_13_2_allSixSeriesExistAtZeroBeforeAnyRun() throws Exception {
        Map<String, Double> counts = counts();
        assertThat(counts).hasSize(6);
        for (String kind : KINDS) {
            assertThat(counts).containsEntry(key(kind, "finished"), 0.0).containsEntry(key(kind, "failed"), 0.0);
        }
    }

    @Test
    void ac_13_2_aCommittedRunCountsFinishedForItsKindOnly() throws Exception {
        Map<String, Double> before = counts();
        assertThat(postRun("BANK", CUTOFF, false).statusCode()).isEqualTo(200);
        Map<String, Double> after = counts();
        assertThat(at(after, key("BANK", "finished"))).isEqualTo(at(before, key("BANK", "finished")) + 1);
        for (String series : before.keySet()) {
            if (!series.equals(key("BANK", "finished")))
                assertThat(at(after, series)).as(series).isEqualTo(at(before, series));
        }
    }

    @Test
    void ac_13_2_runAllCountsOneFinishedPerKind() throws Exception {
        Map<String, Double> before = counts();
        assertThat(postRun("ALL", CUTOFF, false).statusCode()).isEqualTo(200);
        Map<String, Double> after = counts();
        for (String kind : KINDS) {
            assertThat(at(after, key(kind, "finished"))).as(kind).isEqualTo(at(before, key(kind, "finished")) + 1);
            assertThat(at(after, key(kind, "failed"))).as(kind).isEqualTo(at(before, key(kind, "failed")));
        }
    }

    @Test
    void ac_13_2_aRunThatFailsInsideItsTransactionCountsFailedAndLeavesNoRowAndNoMessage() throws Exception {
        Map<String, Double> before = counts();
        long rowsBefore = runRows();
        Sql.execute(DB.superuser(), "REVOKE SELECT ON recon.v_input_evidence FROM app");
        Throwable thrown;
        try {
            thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> runs.run(new RunRequest(RunRequest.Kind.BANK, CUTOFF, EVIDENCE, "p5-v1", List.of(), false)));
        } finally {
            Sql.execute(DB.superuser(), "GRANT SELECT ON recon.v_input_evidence TO app");
        }

        // The caller gets the database's own exception, unchanged.
        assertThat(thrown).isInstanceOf(DataAccessException.class);
        Map<String, Double> after = counts();
        assertThat(at(after, key("BANK", "failed"))).isEqualTo(at(before, key("BANK", "failed")) + 1);
        assertThat(at(after, key("BANK", "finished"))).isEqualTo(at(before, key("BANK", "finished")));
        assertThat(runRows()).isEqualTo(rowsBefore);

        List<ILoggingEvent> failed = events.action("recon.run.failed");
        assertThat(failed).hasSize(1);
        ILoggingEvent line = failed.getFirst();
        assertThat(LogEvents.keyValues(line))
                .containsEntry("kind", "BANK")
                .containsEntry("errorClass", thrown.getClass().getName());
        assertThat(line.getMDCPropertyMap()).doesNotContainKey("runId");
        String text = line.getFormattedMessage() + LogEvents.keyValues(line) + line.getMDCPropertyMap();
        assertThat(text).doesNotContain(String.valueOf(thrown.getMessage())).doesNotContain("v_input_evidence");

        // The grant is back: the same run now commits.
        assertThat(runs.run(new RunRequest(RunRequest.Kind.BANK, CUTOFF, EVIDENCE, "p5-v1", List.of(), false))
                        .id())
                .startsWith("rr_");
    }

    @Test
    void ac_13_2_aFailedRunAllCountsFailedForEachOfTheThreeKinds() throws Exception {
        Map<String, Double> before = counts();
        long rowsBefore = runRows();
        Sql.execute(DB.superuser(), "REVOKE SELECT ON recon.v_input_evidence FROM app");
        try {
            assertThatThrownBy(() -> runs.runAll(CUTOFF, EVIDENCE, false)).isInstanceOf(DataAccessException.class);
        } finally {
            Sql.execute(DB.superuser(), "GRANT SELECT ON recon.v_input_evidence TO app");
        }
        Map<String, Double> after = counts();
        for (String kind : KINDS) {
            assertThat(at(after, key(kind, "failed"))).as(kind).isEqualTo(at(before, key(kind, "failed")) + 1);
            assertThat(at(after, key(kind, "finished"))).as(kind).isEqualTo(at(before, key(kind, "finished")));
        }
        assertThat(runRows()).isEqualTo(rowsBefore);
        assertThat(events.action("recon.run.failed")).hasSize(3);
    }

    @Test
    void ac_13_2_dryRunsAndInvalidRequestsDoNotChangeAnyCount() throws Exception {
        Map<String, Double> before = counts();

        // A dry run that succeeds, and a dry run that fails inside its transaction: neither is counted.
        assertThat(postRun("PROCESSOR", CUTOFF, true).statusCode()).isEqualTo(200);
        assertThat(postRun("ALL", CUTOFF, true).statusCode()).isEqualTo(200);
        Sql.execute(DB.superuser(), "REVOKE SELECT ON recon.v_input_evidence FROM app");
        try {
            assertThatThrownBy(() ->
                            runs.run(new RunRequest(RunRequest.Kind.BANK, CUTOFF, EVIDENCE, "p5-v1", List.of(), true)))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            Sql.execute(DB.superuser(), "GRANT SELECT ON recon.v_input_evidence TO app");
        }

        // A cutoff that is not Toronto midnight is rejected by the endpoint with 400 before any transaction.
        assertThat(postRun("BANK", CUTOFF.plusSeconds(1), false).statusCode()).isEqualTo(400);
        assertThat(postRun("ALL", CUTOFF.plusSeconds(1), false).statusCode()).isEqualTo(400);

        assertThat(counts()).isEqualTo(before);
        assertThat(events.action("recon.run.failed")).isEmpty();
    }

    private long runRows() {
        return jdbc.sql("SELECT count(*) FROM recon.run").query(Long.class).single();
    }

    private HttpResponse<String> postRun(String kind, Instant cutoff, boolean dryRun) throws Exception {
        String body = """
                {"kind":"%s","cutoffAt":"%s","evidenceAsOf":"%s","dryRun":%s}
                """.formatted(kind, cutoff, EVIDENCE, dryRun);
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recon/runs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static double at(Map<String, Double> counts, String key) {
        return Objects.requireNonNull(counts.get(key));
    }

    private static String key(String kind, String outcome) {
        return kind + "/" + outcome;
    }

    /** The {@code ledgerlab_recon_runs_total} samples of the management port's text, keyed {@code kind/outcome}. */
    private Map<String, Double> counts() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String line : response.body().split("\n")) {
            Matcher sample = SAMPLE.matcher(line);
            if (!sample.matches() || !sample.group(1).equals("ledgerlab_recon_runs_total")) continue;
            Map<String, String> labels = new LinkedHashMap<>();
            Matcher label = LABEL.matcher(sample.group(2));
            while (label.find()) labels.put(label.group(1), label.group(2));
            assertThat(labels).containsOnlyKeys("kind", "outcome");
            counts.put(
                    key(Objects.requireNonNull(labels.get("kind")), Objects.requireNonNull(labels.get("outcome"))),
                    Double.parseDouble(sample.group(3)));
        }
        return counts;
    }
}
