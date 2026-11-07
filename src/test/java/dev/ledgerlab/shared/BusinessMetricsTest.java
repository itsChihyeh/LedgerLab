package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * AC-12.5 against the real application: real PostgreSQL with known rows written as {@code app}, the gauges read
 * through the {@code readonly} role, and the numbers taken from the text a Prometheus server would scrape.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
@Import(BusinessMetricsTest.Controls.class)
class BusinessMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{(.*)})? (\\S+)$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");
    private static boolean seeded;

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

    @LocalManagementPort
    int managementPort;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void seedOnce() {
        clock.set(NOW);
        if (seeded) return;
        seeded = true;
        // Inbox: three RECEIVED (two due, one not due yet), one PARKED, one finished.
        inbox("evt_due_old", "RECEIVED", NOW.minus(Duration.ofMinutes(10)), NOW.minus(Duration.ofMinutes(10)));
        inbox("evt_due_new", "RECEIVED", NOW.minus(Duration.ofMinutes(2)), NOW.minus(Duration.ofMinutes(1)));
        inbox("evt_not_due", "RECEIVED", NOW.minus(Duration.ofMinutes(30)), NOW.plus(Duration.ofMinutes(5)));
        inbox("evt_parked", "PARKED", NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)));
        inbox("evt_done", "PROCESSED", NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(2)));
        // Outbound commands (the attempt limit in the test configuration is 20).
        command(1, "SUBMIT_REFUND", "UNKNOWN", 2);
        command(2, "SUBMIT_REFUND", "UNKNOWN", 20);
        command(3, "SUBMIT_REFUND", "PENDING", 0);
        command(4, "SUBMIT_REFUND", "DONE", 1);
        command(5, "SUBMIT_PAYOUT", "IN_FLIGHT", 1);
        command(6, "QUERY_PAYOUT", "PENDING", 0);
        // Reconciliation issues: three OPEN in two categories, one RESOLVED.
        issue(1, "AMOUNT_MISMATCH", NOW.minus(Duration.ofHours(1)));
        issue(2, "AMOUNT_MISMATCH", NOW.minus(Duration.ofHours(3)));
        issue(3, "EVENT_PARKED_AGED", NOW.minus(Duration.ofMinutes(30)));
        issue(4, "FEE_MISMATCH", NOW.minus(Duration.ofHours(10)));
        jdbc.sql("""
                UPDATE recon.issue SET status = 'RESOLVED', explanation = 'checked', updated_at = :now
                WHERE category = 'FEE_MISMATCH'
                """).param("now", NOW.atOffset(ZoneOffset.UTC)).update();
    }

    @Test
    void ac_12_5_inboxGaugesMatchTheSeededRows() throws Exception {
        Scrape scrape = scrape();
        assertThat(scrape.value("ledgerlab_inbox_backlog", "status", "RECEIVED"))
                .isEqualTo(3);
        assertThat(scrape.value("ledgerlab_inbox_backlog", "status", "PARKED")).isEqualTo(1);
        assertThat(scrape.labelSets("ledgerlab_inbox_backlog"))
                .containsExactlyInAnyOrder(Map.of("status", "RECEIVED"), Map.of("status", "PARKED"));
        // Oldest due event: received ten minutes ago; the older event that is not due yet does not count.
        assertThat(scrape.value("ledgerlab_inbox_oldest_due_age_seconds")).isEqualTo(600);
    }

    @Test
    void ac_12_5_ageGaugesFollowTheInjectedClock() throws Exception {
        clock.set(NOW.plus(Duration.ofMinutes(4)));
        Scrape later = scrape();
        assertThat(later.value("ledgerlab_inbox_oldest_due_age_seconds")).isEqualTo(840);
        assertThat(later.value("ledgerlab_recon_issues_oldest_open_age_seconds"))
                .isEqualTo(3 * 3600 + 4 * 60);
        // At exactly its next-attempt time the event that was not due becomes due, and it is the oldest.
        clock.set(NOW.plus(Duration.ofMinutes(5)));
        Scrape afterDue = scrape();
        assertThat(afterDue.value("ledgerlab_inbox_oldest_due_age_seconds")).isEqualTo((30 + 5) * 60);
    }

    @Test
    void ac_12_5_outboundGaugesCountUnfinishedAndExhaustedCommandsPerKind() throws Exception {
        Scrape scrape = scrape();
        assertThat(scrape.value("ledgerlab_outbound_unfinished", "kind", "SUBMIT_REFUND", "status", "PENDING"))
                .isEqualTo(1);
        assertThat(scrape.value("ledgerlab_outbound_unfinished", "kind", "SUBMIT_REFUND", "status", "UNKNOWN"))
                .isEqualTo(2);
        assertThat(scrape.value("ledgerlab_outbound_unfinished", "kind", "SUBMIT_REFUND", "status", "IN_FLIGHT"))
                .isZero();
        assertThat(scrape.value("ledgerlab_outbound_unfinished", "kind", "SUBMIT_PAYOUT", "status", "IN_FLIGHT"))
                .isEqualTo(1);
        assertThat(scrape.value("ledgerlab_outbound_unfinished", "kind", "QUERY_PAYOUT", "status", "PENDING"))
                .isEqualTo(1);
        assertThat(scrape.value("ledgerlab_outbound_exhausted", "kind", "SUBMIT_REFUND"))
                .isEqualTo(1);
        assertThat(scrape.value("ledgerlab_outbound_exhausted", "kind", "SUBMIT_PAYOUT"))
                .isZero();
        assertThat(scrape.value("ledgerlab_outbound_exhausted", "kind", "QUERY_PAYOUT"))
                .isZero();
        // Refunds and payouts register the same meter names; the text has one HELP and TYPE per name.
        assertThat(scrape.typeLines("ledgerlab_outbound_unfinished")).hasSize(1);
        assertThat(scrape.typeLines("ledgerlab_outbound_exhausted")).hasSize(1);
        assertThat(scrape.labelSets("ledgerlab_outbound_unfinished")).hasSize(9);
    }

    @Test
    void ac_12_5_reconGaugesCountOnlyOpenIssuesPerCategory() throws Exception {
        Scrape scrape = scrape();
        assertThat(scrape.value("ledgerlab_recon_issues_open", "category", "AMOUNT_MISMATCH"))
                .isEqualTo(2);
        assertThat(scrape.value("ledgerlab_recon_issues_open", "category", "EVENT_PARKED_AGED"))
                .isEqualTo(1);
        // The resolved issue is not counted, and a category with nothing open reads zero.
        assertThat(scrape.value("ledgerlab_recon_issues_open", "category", "FEE_MISMATCH"))
                .isZero();
        assertThat(scrape.value("ledgerlab_recon_issues_oldest_open_age_seconds"))
                .isEqualTo(3 * 3600);
    }

    @Test
    void ac_12_5_noLabelValueIsABusinessIdentifier() throws Exception {
        Scrape scrape = scrape();
        List<String> prefixes = List.of("evt_", "cmd_", "iss_", "rr_", "po_", "pay_", "ref_");
        assertThat(scrape.samples()).isNotEmpty();
        for (Sample sample : scrape.samples()) {
            for (String value : sample.labels().values()) {
                for (String prefix : prefixes)
                    assertThat(value).as(sample.name()).doesNotStartWith(prefix);
            }
        }
    }

    @Test
    void ac_12_5_aFailingQueryGivesNaNForItsGaugesAndTheScrapeStillSucceeds() throws Exception {
        Sql.execute(DB.superuser(), "REVOKE SELECT ON core.v_inbox_backlog FROM readonly");
        try {
            HttpResponse<String> response = fetch();
            assertThat(response.statusCode()).isEqualTo(200);
            Scrape scrape = Scrape.parse(response.body());
            assertThat(scrape.value("ledgerlab_inbox_backlog", "status", "RECEIVED"))
                    .isNaN();
            assertThat(scrape.value("ledgerlab_inbox_backlog", "status", "PARKED"))
                    .isNaN();
            assertThat(scrape.value("ledgerlab_inbox_oldest_due_age_seconds")).isNaN();
            // Gauges cut from other queries are not affected.
            assertThat(scrape.value("ledgerlab_recon_issues_open", "category", "AMOUNT_MISMATCH"))
                    .isEqualTo(2);
            assertThat(scrape.value("ledgerlab_outbound_exhausted", "kind", "SUBMIT_REFUND"))
                    .isEqualTo(1);
        } finally {
            Sql.execute(DB.superuser(), "GRANT SELECT ON core.v_inbox_backlog TO readonly");
        }
        assertThat(scrape().value("ledgerlab_inbox_backlog", "status", "RECEIVED"))
                .isEqualTo(3);
    }

    private void inbox(String id, String status, Instant received, Instant nextAttempt) {
        jdbc.sql("""
                INSERT INTO io.inbox_event(event_id, event_type, api_version, livemode, payload, payload_sha256,
                    signed_at, occurred_at, received_at, recorded_at, next_attempt_at, status, result, parked_on)
                VALUES (:id, 'charge.succeeded', 'v', false, 'x'::bytea, encode(sha256('x'::bytea), 'hex'),
                    :received, :received, :received, :received, :next, :status, :result, :parked)
                """)
                .param("id", id)
                .param("received", received.atOffset(ZoneOffset.UTC))
                .param("next", nextAttempt.atOffset(ZoneOffset.UTC))
                .param("status", status)
                .param("result", status.equals("PROCESSED") ? "APPLIED" : null)
                .param("parked", status.equals("PARKED") ? "payment" : null)
                .update();
    }

    private void command(int number, String kind, String status, int attempts) {
        String suffix = "%026d".formatted(number);
        jdbc.sql("""
                INSERT INTO io.outbound_command(id, kind, subject_id, request_id, status, attempts, next_attempt_at,
                    created_at, recorded_at)
                VALUES (:id, :kind, :subject, :subject, :status, :attempts, :now, :now, :now)
                """)
                .param("id", "cmd_" + suffix)
                .param("kind", kind)
                .param("subject", "subject-" + number)
                .param("status", status)
                .param("attempts", attempts)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void issue(int number, String category, Instant recorded) {
        jdbc.sql("""
                INSERT INTO recon.issue(id, category, subject_type, subject_id, detected_by, status, evidence_refs,
                    recorded_at, updated_at)
                VALUES (:id, :category, 'payment', :subject, 'INGESTION', 'OPEN', '[]'::jsonb, :at, :at)
                """)
                .param("id", "iss_" + "%026d".formatted(number))
                .param("category", category)
                .param("subject", "subject-" + number)
                .param("at", recorded.atOffset(ZoneOffset.UTC))
                .update();
    }

    private Scrape scrape() throws Exception {
        HttpResponse<String> response = fetch();
        assertThat(response.statusCode()).isEqualTo(200);
        return Scrape.parse(response.body());
    }

    private HttpResponse<String> fetch() throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private record Sample(String name, Map<String, String> labels, double value) {}

    /** The Prometheus text format, parsed just enough to look values up by name and labels. */
    private record Scrape(List<Sample> samples, List<String> typeLines) {
        static Scrape parse(String text) {
            List<Sample> samples = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (line.startsWith("# TYPE ")) types.add(line);
                if (line.startsWith("#") || line.isBlank()) continue;
                Matcher sample = SAMPLE.matcher(line);
                assertThat(sample.matches()).as(line).isTrue();
                Map<String, String> labels = new LinkedHashMap<>();
                if (sample.group(2) != null) {
                    Matcher label = LABEL.matcher(sample.group(2));
                    while (label.find()) labels.put(label.group(1), label.group(2));
                }
                samples.add(new Sample(sample.group(1), labels, Double.parseDouble(sample.group(3))));
            }
            return new Scrape(samples, types);
        }

        /** The one sample with this name whose labels include all the given pairs; fails if not exactly one. */
        double value(String name, String... labelPairs) {
            List<Sample> found = samples.stream()
                    .filter(s -> s.name().equals(name))
                    .filter(s -> {
                        for (int i = 0; i < labelPairs.length; i += 2)
                            if (!labelPairs[i + 1].equals(s.labels().get(labelPairs[i]))) return false;
                        return true;
                    })
                    .toList();
            assertThat(found).as(name + " " + List.of(labelPairs)).hasSize(1);
            return found.getFirst().value();
        }

        List<Map<String, String>> labelSets(String name) {
            return samples.stream()
                    .filter(s -> s.name().equals(name))
                    .map(Sample::labels)
                    .toList();
        }

        List<String> typeLines(String name) {
            return typeLines.stream()
                    .filter(l -> l.startsWith("# TYPE " + name + " "))
                    .toList();
        }
    }
}
