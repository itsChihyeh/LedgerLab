package dev.ledgerlab.ops;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

/**
 * AC-12.8: the alert fires for a stuck inbox of the real application. A signed webhook goes in through the real entry,
 * nothing processes it, the clock moves ten minutes, and the value a Prometheus server would scrape becomes the input
 * series of a {@code promtool} test against the real {@code alerts.yml}. After the worker has processed the event the
 * value is 0 and the same check says the alert is silent.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
@Import(InboxStuckAlertTest.Controls.class)
class InboxStuckAlertTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String METRIC = "ledgerlab_inbox_oldest_due_age_seconds";
    private static final Pattern GAUGE = Pattern.compile("^" + METRIC + " (\\S+)$", Pattern.MULTILINE);

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
    void ac_12_8_aStuckInboxMakesTheRuleFireAndDrainingItSilencesTheRule(@TempDir Path dir) throws Exception {
        clock.set(NOW);
        assertThat(deliver("evt_alert_stuck")).isEqualTo(200);
        // The worker is not scheduled, so the event just sits there while ten minutes pass.
        clock.set(NOW.plus(Duration.ofMinutes(10)));

        double stuck = scrapeAge();
        assertThat(stuck).isGreaterThanOrEqualTo(600);
        OpsAlertRuleTest.write(dir, "stuck.test.yml", OpsAlertRuleTest.testFile(sevenMinutesOf(stuck), "6m", true));
        Promtool.Result firing = Promtool.testRulesGenerated(dir, "stuck.test.yml");
        assertThat(firing.exitCode()).as(firing.output()).isZero();

        // Control: the same real value, claimed silent, is rejected, so the pass above is not vacuous.
        OpsAlertRuleTest.write(
                dir, "claim-silent.test.yml", OpsAlertRuleTest.testFile(sevenMinutesOf(stuck), "6m", false));
        assertThat(Promtool.testRulesGenerated(dir, "claim-silent.test.yml").exitCode())
                .isNotZero();

        assertThat(worker.processAvailable()).isEqualTo(1);

        double drained = scrapeAge();
        assertThat(drained).isZero();
        OpsAlertRuleTest.write(
                dir, "drained.test.yml", OpsAlertRuleTest.testFile(sevenMinutesOf(drained), "6m", false));
        Promtool.Result silent = Promtool.testRulesGenerated(dir, "drained.test.yml");
        assertThat(silent.exitCode()).as(silent.output()).isZero();
    }

    /** The scraped value on every minute from 0 to 6, the shortest run that satisfies {@code for: 5m}. */
    private static String sevenMinutesOf(double value) {
        return (long) value + "x6";
    }

    private double scrapeAge() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher gauge = GAUGE.matcher(response.body());
        assertThat(gauge.find()).as(METRIC).isTrue();
        return Double.parseDouble(gauge.group(1));
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
