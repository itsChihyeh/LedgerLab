package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.bank.scheduling-enabled=false"})
@Import(ReconRunHttpTest.Config.class)
class ReconRunHttpTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReconProperties properties;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Clock reconClock() {
            return Clock.fixed(Instant.parse("2026-11-13T14:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        ReconRule fakeRule() {
            return new ReconRule() {
                @Override
                public RunRequest.Kind kind() {
                    return RunRequest.Kind.PROCESSOR;
                }

                @Override
                public String version() {
                    return "processor-v1";
                }

                @Override
                public List<Finding> evaluate(Context context) {
                    return List.of(new Finding("FEE_MISMATCH", "payment", "http_payment", "175", "205", "[]", null));
                }

                @Override
                public Map<Finding.Key, Explanation> checkedScope(Context context) {
                    return Map.of(
                            new Finding.Key("FEE_MISMATCH", "payment", "http_payment"),
                            new Explanation("checked", "[]"));
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
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

    private HttpResponse<String> post(String body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recon/runs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String id) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recon/runs/" + id))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ac_5_7_allReturnsThreeOrderedRunNumbersAndDryRunWritesNothing() throws Exception {
        String body = """
                {"kind":"ALL","cutoffAt":"2026-11-13T05:00:00Z","evidenceAsOf":"2026-11-13T13:00:00Z","dryRun":false}
                """;
        var response = post(body);
        assertThat(response.statusCode()).isEqualTo(200);
        var report = mapper.readTree(response.body());
        assertThat(report.path("runIds").size()).isEqualTo(3);
        var kinds = List.of("PROCESSOR", "BANK", "AGING");
        for (int i = 0; i < 3; i++) {
            assertThat(report.path("runs").get(i).path("kind").asText()).isEqualTo(kinds.get(i));
            assertThat(mapper.readTree(
                            get(report.path("runIds").get(i).asText()).body()))
                    .isEqualTo(report.path("runs").get(i));
        }
        long before =
                jdbc.sql("SELECT count(*) FROM recon.run").query(Long.class).single();
        var dry = post(body.replace("false", "true"));
        assertThat(dry.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(dry.body()).path("runIds").isEmpty()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM recon.run").query(Long.class).single())
                .isEqualTo(before);
    }

    @Test
    void ac_5_10_snapshotEndpointValidatesDateAndArchivesUnavailableRead() throws Exception {
        for (String body : List.of("{}", "{\"businessDate\":\"invalid\"}", "{\"businessDate\":\"2026-11-10\"}")) {
            var response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/recon/processor-snapshots"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (body.contains("2026-11-10")) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(response.body()).path("rejectReason").asText())
                        .isEqualTo("FILE_INCOMPLETE");
            } else assertThat(response.statusCode()).isEqualTo(400);
        }
    }

    @Test
    void ac_5_8_httpReturnsPersistedReportAndDryRunHasNoRunNumber() throws Exception {
        String body = """
                {"kind":"PROCESSOR","cutoffAt":"2026-11-13T05:00:00Z","evidenceAsOf":"2026-11-13T13:00:00Z","dryRun":false}
                """;
        long before =
                jdbc.sql("SELECT count(*) FROM recon.run").query(Long.class).single();
        var dry = post(body.replace("false", "true"));
        assertThat(dry.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(dry.body()).path("id").isNull()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM recon.run").query(Long.class).single())
                .isEqualTo(before);
        var result = post(body);
        assertThat(result.statusCode()).isEqualTo(200);
        var report = mapper.readTree(result.body());
        assertThat(report.path("id").asText()).startsWith("rr_");
        assertThat(report.path("summary").path("FEE_MISMATCH").longValue()).isEqualTo(1);
        var stored = get(report.path("id").asText());
        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(stored.body())).isEqualTo(report);
        assertThat(get("rr_missing").statusCode()).isEqualTo(404);
        assertThat(properties.evidenceGrace()).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    @Test
    void ac_5_9_httpRejectsBadTimeBoundariesAndMissingFields() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"kind\":\"PROCESSOR\"}",
                "{\"kind\":\"UNKNOWN\"}",
                "{\"kind\":\"BANK\",\"cutoffAt\":\"2026-11-13T05:00:00Z\"}",
                "{\"kind\":\"BANK\",\"cutoffAt\":\"2026-11-13T05:00:01Z\",\"evidenceAsOf\":\"2026-11-13T13:00:00Z\"}",
                "{\"kind\":\"BANK\",\"cutoffAt\":\"2026-11-13T05:00:00Z\",\"evidenceAsOf\":\"2026-11-13T13:59:01Z\"}",
                "{\"kind\":\"BANK\",\"cutoffAt\":\"2026-11-13T05:00:00Z\",\"evidenceAsOf\":\"2026-11-13T04:59:59Z\"}")) {
            String complete = body.equals("{}")
                    ? "{\"dryRun\":false}"
                    : body.substring(0, body.length() - 1) + ",\"dryRun\":false}";
            assertThat(post(complete).statusCode()).as(body).isEqualTo(400);
        }
        var custom = new Binder(new MapConfigurationPropertySource(Map.of("ledgerlab.recon.evidence-grace", "2m")))
                .bind("ledgerlab.recon", ReconProperties.class)
                .get();
        assertThat(custom.evidenceGrace()).isEqualTo(java.time.Duration.ofMinutes(2));
        assertThat(custom.ruleVersion()).isEqualTo("p2-v1");
    }
}
