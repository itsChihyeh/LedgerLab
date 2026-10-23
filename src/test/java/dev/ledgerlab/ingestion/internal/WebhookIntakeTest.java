package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
@Import(WebhookIntakeTest.Controls.class)
class WebhookIntakeTest {
    private static final Instant NOW = Instant.parse("2026-10-21T14:00:00Z");
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

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

        @Bean
        @Primary
        CrashAfterCommit testAfterCommit() {
            return new CrashAfterCommit();
        }
    }

    static final class CrashAfterCommit implements IntakeCommitted {
        final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void afterCommit() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated crash after inbox commit");
            }
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    CrashAfterCommit crash;

    @Autowired
    IngestionIssues issues;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    InboxIntake intake;

    @Autowired
    StripeEventParser parser;

    @BeforeEach
    void resetControls() {
        clock.set(NOW);
        crash.failNext.set(false);
    }

    @Test
    void ac_2_1_badMissingAndTamperedSignaturesNeverWrite() throws Exception {
        byte[] body = event("evt_bad_signature", 10000, NOW);
        long before = count("io.inbox_event");
        assertThat(post(body, null)).isEqualTo(401);
        assertThat(post(body, StripeSignatures.sign(body, NOW.getEpochSecond(), "wrong_secret")))
                .isEqualTo(401);
        byte[] changed = event("evt_bad_signature", 90000, NOW);
        assertThat(post(changed, StripeSignatures.sign(body, NOW.getEpochSecond())))
                .isEqualTo(401);
        assertThat(count("io.inbox_event")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {-301, -300, 300, 301})
    void ac_2_2_signatureWindowIncludesBothBoundaries(int delta) throws Exception {
        String id = "evt_boundary_" + delta;
        byte[] body = event(id, 10000, NOW);
        int expected = Math.abs(delta) <= 300 ? 200 : 401;
        assertThat(post(body, StripeSignatures.sign(body, NOW.getEpochSecond() + delta)))
                .isEqualTo(expected);
        assertThat(eventCount(id)).isEqualTo(expected == 200 ? 1 : 0);
    }

    @Test
    void ac_2_2_oldEventUsesSeparateOccurredSignedAndReceivedTimes() throws Exception {
        Instant created = NOW.minusSeconds(3 * 86400);
        clock.set(NOW.plusSeconds(10));
        byte[] body = event("evt_old", 10000, created);
        assertThat(post(body, StripeSignatures.sign(body, NOW.getEpochSecond())))
                .isEqualTo(200);
        Map<String, @Nullable Object> row = row("evt_old");
        assertThat(row.get("occurred_at")).isEqualTo(Timestamp.from(created));
        assertThat(row.get("signed_at")).isEqualTo(Timestamp.from(NOW));
        Timestamp received = Timestamp.from(clock.instant());
        assertThat(row)
                .containsEntry("received_at", received)
                .containsEntry("recorded_at", received)
                .containsEntry("next_attempt_at", received)
                .containsEntry("status", "RECEIVED");
        assertThat((byte[]) row.get("payload")).isEqualTo(body);
    }

    @Test
    void ac_2_3_sameContentIsAnUnchangedSingleEvent() throws Exception {
        byte[] body = event("evt_duplicate", 10000, NOW);
        assertThat(deliver(body)).isEqualTo(200);
        Map<String, @Nullable Object> before = row("evt_duplicate");
        clock.set(NOW.plusSeconds(20));
        assertThat(deliver(body)).isEqualTo(200);
        assertThat(eventCount("evt_duplicate")).isEqualTo(1);
        assertThat(conflictCount("evt_duplicate")).isZero();
        assertThat(row("evt_duplicate")).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.sql("SELECT outcome FROM io.webhook_delivery WHERE event_id = 'evt_duplicate' ORDER BY id")
                        .query(String.class)
                        .list())
                .containsExactly("NEW", "DUPLICATE");
        assertThat(jdbc.sql("SELECT received_at FROM io.webhook_delivery WHERE event_id = 'evt_duplicate' ORDER BY id")
                        .query(Timestamp.class)
                        .list())
                .containsExactly(Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(20)));
        // Posting-count invariance after business processing belongs to the subsequent worker task.
    }

    @Test
    void ac_2_4_conflictsPreserveEveryOriginalColumnAndOpenOnlyOneIssue() throws Exception {
        byte[] original = event("evt_conflict", 10000, NOW);
        byte[] conflict = event("evt_conflict", 9000, NOW);
        assertThat(deliver(original)).isEqualTo(200);
        Map<String, @Nullable Object> before = row("evt_conflict");
        assertThat(deliver(conflict)).isEqualTo(200);
        assertThat(conflictCount("evt_conflict")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT payload FROM io.inbox_conflict WHERE event_id = 'evt_conflict'")
                        .query(byte[].class)
                        .single())
                .isEqualTo(conflict);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM recon.issue i JOIN io.inbox_event e ON i.subject_id = e.event_id
                JOIN io.inbox_conflict c ON c.event_id = e.event_id
                WHERE i.category = 'EVENT_CONTENT_CONFLICT' AND i.subject_type = 'event'
                AND i.subject_id = 'evt_conflict' AND i.detected_by = 'INGESTION' AND i.status = 'OPEN'
                AND i.expected ->> 'payload_sha256' = e.payload_sha256
                AND i.actual ->> 'payload_sha256' = c.payload_sha256
                AND jsonb_array_length(i.evidence_refs) > 0
                """).query(Long.class).single()).isEqualTo(1);
        Map<String, @Nullable Object> issueBefore = jdbc.sql(
                        "SELECT * FROM recon.issue WHERE subject_id = 'evt_conflict'")
                .query()
                .singleRow();
        clock.set(NOW.plusSeconds(10));
        assertThat(deliver(event("evt_conflict", 8000, NOW))).isEqualTo(200);
        assertThat(conflictCount("evt_conflict")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT * FROM recon.issue WHERE subject_id = 'evt_conflict'")
                        .query()
                        .listOfRows())
                .containsExactly(issueBefore);
        assertThat(row("evt_conflict")).usingRecursiveComparison().isEqualTo(before);
        assertThat(jdbc.sql("SELECT outcome FROM io.webhook_delivery WHERE event_id = 'evt_conflict' ORDER BY id")
                        .query(String.class)
                        .list())
                .containsExactly("NEW", "CONFLICT", "CONFLICT");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM io.webhook_delivery d JOIN io.inbox_conflict c
                ON d.event_id = c.event_id AND d.payload_sha256 = c.payload_sha256
                WHERE d.event_id = 'evt_conflict' AND d.signed_at = c.signed_at
                    AND d.received_at = c.received_at AND d.recorded_at = c.recorded_at
                """).query(Long.class).single()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NEW", "DUPLICATE", "CONFLICT"})
    void ac_1_14_deliveriesRollbackWithTheIntakeTransaction(String outcome) throws Exception {
        String id = "evt_delivery_rollback_" + outcome;
        byte[] original = event(id, 10000, NOW);
        if (!outcome.equals("NEW")) assertThat(deliver(original)).isEqualTo(200);
        long before = count("io.webhook_delivery");
        byte[] body = outcome.equals("CONFLICT") ? event(id, 9000, NOW) : original;
        transactions.executeWithoutResult(status -> {
            intake.receive(parser.parse(body), body, NOW);
            assertThat(count("io.webhook_delivery")).isEqualTo(before + 1);
            status.setRollbackOnly();
        });
        assertThat(count("io.webhook_delivery")).isEqualTo(before);
        assertThat(eventCount(id)).isEqualTo(outcome.equals("NEW") ? 0 : 1);
        assertThat(conflictCount(id)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM recon.issue WHERE subject_id = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void ac_4_4_committedEventSurvivesResponseFailureAndRetry() throws Exception {
        byte[] body = event("evt_crash", 10000, NOW);
        crash.failNext.set(true);
        assertThat(deliver(body)).isBetween(500, 599);
        assertThat(eventCount("evt_crash")).isEqualTo(1);
        assertThat(deliver(body)).isEqualTo(200);
        assertThat(eventCount("evt_crash")).isEqualTo(1);
        assertThat(conflictCount("evt_crash")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "null", "[]", "{\"id\":\"evt_missing\"}"})
    void ac_2_1_signedInvalidJsonNeverWrites(String json) throws Exception {
        long before = count("io.inbox_event");
        assertThat(deliver(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(400);
        assertThat(count("io.inbox_event")).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "type", "api_version", "livemode", "created"})
    void ac_2_1_eachRequiredFieldMustBePresentAndTyped(String field) throws Exception {
        tools.jackson.databind.ObjectMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();
        tools.jackson.databind.node.ObjectNode event =
                (tools.jackson.databind.node.ObjectNode) mapper.readTree(event("evt_invalid_field", 10000, NOW));
        long before = count("io.inbox_event");
        event.remove(field);
        assertThat(deliver(mapper.writeValueAsBytes(event))).isEqualTo(400);
        event.putNull(field);
        assertThat(deliver(mapper.writeValueAsBytes(event))).isEqualTo(400);
        event.put(field, "");
        assertThat(deliver(mapper.writeValueAsBytes(event))).isEqualTo(400);
        assertThat(count("io.inbox_event")).isEqualTo(before);
    }

    @Test
    void ac_8_2_intakeRetainsLiveAndUnknownEventsForLaterWorkerRejection() throws Exception {
        String json = new String(event("evt_worker_policy", 10000, NOW), StandardCharsets.UTF_8)
                .replace("charge.succeeded", "unknown.event")
                .replace("false", "true");
        assertThat(deliver(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(200);
        assertThat(row("evt_worker_policy")).containsEntry("status", "RECEIVED").containsEntry("livemode", true);
        // AC-8.2 business rejection and issue creation will be verified by the worker task.
    }

    @Test
    void ac_2_4_issueApiRequiresAndParticipatesInCallerTransaction() {
        assertThatThrownBy(() -> issues.open("EVENT_CONTENT_CONFLICT", "event", "evt_rollback", "{}", "{}", "[]"))
                .isInstanceOf(IllegalStateException.class);
        transactions.executeWithoutResult(status -> {
            issues.open("EVENT_CONTENT_CONFLICT", "event", "evt_rollback", "{}", "{}", "[]");
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM recon.issue WHERE subject_id = 'evt_rollback'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private int deliver(byte[] body) throws Exception {
        return post(body, StripeSignatures.sign(body, clock.instant().getEpochSecond()));
    }

    private int post(byte[] body, @Nullable String signature) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (signature != null) {
            request.header("Stripe-Signature", signature);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static byte[] event(String id, long amount, Instant created) {
        return """
                { "id":"%s", "type":"charge.succeeded", "api_version":"2026-09-30.endive",
                  "livemode":false, "created":%d, "data":{"object":{"amount":%d}} }
                """.formatted(id, created.getEpochSecond(), amount).getBytes(StandardCharsets.UTF_8);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long eventCount(String id) {
        return jdbc.sql("SELECT count(*) FROM io.inbox_event WHERE event_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private long conflictCount(String id) {
        return jdbc.sql("SELECT count(*) FROM io.inbox_conflict WHERE event_id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    private Map<String, @Nullable Object> row(String id) {
        return jdbc.sql("SELECT * FROM io.inbox_event WHERE event_id = :id")
                .param("id", id)
                .query()
                .singleRow();
    }
}
