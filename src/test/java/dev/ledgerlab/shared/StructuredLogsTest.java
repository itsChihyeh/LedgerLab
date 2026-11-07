package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.reconciliation.IssueDisposition;
import dev.ledgerlab.testsupport.LogEvents;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-12.1 to AC-12.3 against the real application: real PostgreSQL, the real webhook entry with signature
 * verification, the real inbox worker, and the JSON console format switched on.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "logging.structured.format.console=logstash"})
@Import(StructuredLogsTest.Controls.class)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLogsTest {
    private static final Instant NOW = Instant.parse("2026-10-21T14:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Printed straight to stderr by the JVM and Mockito's inline agent, not by the logging system.
    private static final List<String> JVM_NOTICES = List.of("Mockito is currently self-attaching", "WARNING:");
    private static final String MEMO_CANARY = "memo-canary-7f3a91c2";
    private static final String NOTE_CANARY = "operator-note-canary-52be04d8";

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

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    InboxWorker worker;

    @Autowired
    IssueDisposition disposition;

    private LogEvents events;

    @BeforeEach
    void collect() {
        clock.set(NOW);
        events = LogEvents.attach();
    }

    @AfterEach
    void stopCollecting() {
        events.close();
    }

    @Test
    void ac_12_1_everyConsoleLineIsAJsonObjectWithTheStandardFields(CapturedOutput out) throws Exception {
        Reply reply = get("/api/events/evt_nothing_here", null);
        events.await(line -> reply.requestId().equals(line.getMDCPropertyMap().get("requestId")), WAIT);
        List<JsonNode> lines = jsonLines(out);
        assertThat(lines).isNotEmpty();
        for (JsonNode line : lines) {
            assertThat(line.isObject()).isTrue();
            assertThat(line.has("@timestamp")).isTrue();
            assertThat(line.has("level")).isTrue();
            assertThat(line.has("logger_name")).isTrue();
            assertThat(line.has("message")).isTrue();
        }
    }

    @Test
    void ac_12_2_responseHeaderAndAccessLineShareOneRequestId(CapturedOutput out) throws Exception {
        Reply generated = get("/api/events/evt_nothing_here", null);
        assertThat(generated.requestId()).matches("req_[0-9A-HJKMNP-TV-Z]{26}");
        Reply kept = get("/api/events/evt_nothing_here", "client-trace.42_A");
        assertThat(kept.requestId()).isEqualTo("client-trace.42_A");
        Reply withSpace = get("/api/events/evt_nothing_here", "has a space");
        Reply tooLong = get("/api/events/evt_nothing_here", "a".repeat(65));
        for (Reply replaced : List.of(withSpace, tooLong)) {
            assertThat(replaced.requestId()).startsWith("req_");
        }
        for (Reply reply : List.of(generated, kept, withSpace, tooLong)) {
            ILoggingEvent access = events.await(
                    line -> reply.requestId().equals(line.getMDCPropertyMap().get("requestId"))
                            && "http.request".equals(LogEvents.keyValues(line).get("event.action")),
                    WAIT);
            assertThat(LogEvents.keyValues(access))
                    .containsEntry("http.method", "GET")
                    .containsEntry("http.route", "/api/events/{eventId}")
                    .containsEntry("http.status", reply.status());
            assertThat(LogEvents.keyValues(access).get("durationMs")).isInstanceOf(Long.class);
            JsonNode written = jsonLines(out).stream()
                    .filter(line ->
                            reply.requestId().equals(line.path("requestId").asString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(written.path("event.action").asString()).isEqualTo("http.request");
            assertThat(written.path("http.route").asString()).isEqualTo("/api/events/{eventId}");
        }
        assertThat(get("/no/such/route/at/all", null).requestId()).startsWith("req_");
        ILoggingEvent unmatched = events.await(
                line -> "UNMATCHED".equals(LogEvents.keyValues(line).get("http.route")), WAIT);
        assertThat(LogEvents.keyValues(unmatched)).containsEntry("http.status", 404);
    }

    @Test
    void ac_12_2_webhookAndInboxLinesCarryTheSameEventId() throws Exception {
        String id = "evt_log_ignored";
        byte[] body = event(id, "customer.created", false);
        Reply first = deliver(body);
        Reply again = deliver(body);
        assertThat(worker.processAvailable()).isGreaterThanOrEqualTo(1);

        List<ILoggingEvent> received =
                events.matching(line -> id.equals(line.getMDCPropertyMap().get("eventId"))
                        && "webhook.received".equals(LogEvents.keyValues(line).get("event.action")));
        assertThat(received).hasSize(2);
        assertThat(LogEvents.keyValues(received.get(0)))
                .containsEntry("eventType", "customer.created")
                .containsEntry("duplicate", false);
        assertThat(LogEvents.keyValues(received.get(1))).containsEntry("duplicate", true);
        assertThat(received.get(0).getMDCPropertyMap()).containsEntry("requestId", first.requestId());
        assertThat(received.get(1).getMDCPropertyMap()).containsEntry("requestId", again.requestId());

        ILoggingEvent processed = events.await(
                line -> id.equals(line.getMDCPropertyMap().get("eventId"))
                        && "inbox.processed".equals(LogEvents.keyValues(line).get("event.action")),
                WAIT);
        assertThat(LogEvents.keyValues(processed))
                .containsEntry("eventType", "customer.created")
                .containsEntry("status", "IGNORED")
                .containsEntry("attempts", 0);
        // A worker thread has no request, so no request id.
        assertThat(processed.getMDCPropertyMap()).doesNotContainKey("requestId");
    }

    @Test
    void ac_12_2_rejectedWebhooksNameOnlyTheReason() throws Exception {
        byte[] body = event("evt_log_unsigned", "customer.created", false);
        assertThat(post(body, "t=1,v1=" + "0".repeat(64)).status()).isEqualTo(401);
        ILoggingEvent rejected = events.await(
                line -> "invalid_signature".equals(LogEvents.keyValues(line).get("reason")), WAIT);
        assertThat(LogEvents.keyValues(rejected)).containsEntry("event.action", "webhook.rejected");
        assertThat(rejected.getLevel().toString()).isEqualTo("WARN");

        byte[] live = event("evt_log_live", "unknown.event", true);
        assertThat(deliver(live).status()).isEqualTo(200);
        worker.processAvailable();
        ILoggingEvent liveRejected = events.await(
                line -> "evt_log_live".equals(line.getMDCPropertyMap().get("eventId"))
                        && "livemode".equals(LogEvents.keyValues(line).get("reason")),
                WAIT);
        assertThat(LogEvents.keyValues(liveRejected)).containsEntry("event.action", "webhook.rejected");
    }

    @Test
    void ac_12_2_issueLinesCarryIssueIdAndTheEventThatOpenedIt() throws Exception {
        String id = "evt_log_dispute";
        assertThat(deliver(event(id, "charge.dispute.created", false)).status()).isEqualTo(200);
        worker.processAvailable();
        String issueId = jdbc.sql("SELECT id FROM recon.issue WHERE subject_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        ILoggingEvent opened = events.await(
                line -> issueId.equals(line.getMDCPropertyMap().get("issueId"))
                        && "recon.issue.opened".equals(LogEvents.keyValues(line).get("event.action")),
                WAIT);
        assertThat(opened.getMDCPropertyMap()).containsEntry("eventId", id);
        assertThat(LogEvents.keyValues(opened))
                .containsEntry("category", "UNSUPPORTED_EVENT")
                .containsEntry("subjectType", "event")
                .containsEntry("subjectId", id)
                .containsEntry("toStatus", "OPEN");

        disposition.accept(issueId, NOTE_CANARY);
        ILoggingEvent accepted = events.await(
                line -> issueId.equals(line.getMDCPropertyMap().get("issueId"))
                        && "recon.issue.transition"
                                .equals(LogEvents.keyValues(line).get("event.action")),
                WAIT);
        assertThat(LogEvents.keyValues(accepted))
                .containsEntry("fromStatus", "OPEN")
                .containsEntry("toStatus", "ACCEPTED");
        // The ids do not leak into whatever is logged next on this thread.
        assertThat(org.slf4j.MDC.get("issueId")).isNull();
    }

    @Test
    void ac_12_3_noSecretBodySignatureOrFreeTextReachesTheLog(CapturedOutput out) throws Exception {
        List<String> signatures = new ArrayList<>();
        String quiet = "evt_log_scan_quiet";
        byte[] quietBody = event(quiet, "customer.created", false);
        signatures.add(StripeSignatures.sign(quietBody, NOW.getEpochSecond()));
        deliver(quietBody);
        byte[] dispute = event("evt_log_scan_dispute", "charge.dispute.created", false);
        signatures.add(StripeSignatures.sign(dispute, NOW.getEpochSecond()));
        deliver(dispute);
        byte[] live = event("evt_log_scan_live", "unknown.event", true);
        deliver(live);
        byte[] forged = event("evt_log_scan_forged", "customer.created", false);
        post(forged, "t=1,v1=" + "0".repeat(64));
        worker.processAvailable();
        String issueId = jdbc.sql("SELECT id FROM recon.issue WHERE subject_id = 'evt_log_scan_dispute'")
                .query(String.class)
                .single();
        disposition.resolve(issueId, NOTE_CANARY);
        events.await(
                line -> "recon.issue.transition"
                        .equals(LogEvents.keyValues(line).get("event.action")),
                WAIT);
        events.await(
                line -> "evt_log_scan_live".equals(line.getMDCPropertyMap().get("eventId"))
                        && "inbox.processed".equals(LogEvents.keyValues(line).get("event.action")),
                WAIT);

        String everything = out.getAll() + "\n" + events.allText();
        assertThat(everything).contains("webhook.received").contains("inbox.processed");
        assertThat(everything).doesNotContain(MEMO_CANARY).doesNotContain(NOTE_CANARY);
        for (byte[] body : List.of(quietBody, dispute, live, forged)) {
            assertThat(everything).doesNotContain(new String(body, StandardCharsets.UTF_8).strip());
        }
        for (String signature : signatures) {
            assertThat(everything).doesNotContain(signature);
            assertThat(everything).doesNotContain(signature.substring(signature.indexOf("v1=") + 3));
        }
        assertThat(everything.toLowerCase(java.util.Locale.ROOT)).doesNotContain("stripe-signature");
        // Prefixes are assembled here so this source file holds no key-shaped literal.
        assertThat(everything).doesNotContain("whsec" + "_");
        assertThat(Pattern.compile("(?<![A-Za-z0-9])(sk|rk|pk)" + "_[A-Za-z0-9]")
                        .matcher(everything)
                        .find())
                .isFalse();
        for (String password : List.of(
                TestDatabase.APP_PASSWORD,
                TestDatabase.MIGRATOR_PASSWORD,
                TestDatabase.READONLY_PASSWORD,
                TestDatabase.SIMULATOR_PASSWORD)) {
            assertThat(everything).doesNotContain(password);
        }
    }

    private record Reply(int status, String requestId) {}

    private Reply get(String path, @Nullable String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (requestId != null) request.header("X-Request-Id", requestId);
        HttpResponse<Void> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding());
        return new Reply(
                response.statusCode(),
                response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private Reply deliver(byte[] body) throws Exception {
        return post(body, StripeSignatures.sign(body, clock.instant().getEpochSecond()));
    }

    private Reply post(byte[] body, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
        return new Reply(
                response.statusCode(),
                response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private static byte[] event(String id, String type, boolean live) {
        return """
                { "id":"%s", "type":"%s", "api_version":"2026-09-30.endive", "livemode":%s, "created":%d,
                  "data":{"object":{"amount":10000,"description":"%s"}} }
                """.formatted(id, type, live, NOW.getEpochSecond(), MEMO_CANARY).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Every console line from the moment the JSON format took effect. Whatever the JVM printed before Spring Boot
     * initialised logging (the test database container's start-up, in the default pattern) is not the application's
     * output; from the first JSON line on, a line that does not parse is a failure.
     */
    private static List<JsonNode> jsonLines(CapturedOutput out) {
        List<JsonNode> parsed = new ArrayList<>();
        for (String line : out.getAll().split("\\R")) {
            if (line.isBlank() || JVM_NOTICES.stream().anyMatch(line::startsWith)) continue;
            try {
                parsed.add(JSON.readTree(line));
            } catch (RuntimeException notJson) {
                if (parsed.isEmpty()) continue;
                throw new AssertionError("Console line is not JSON: " + line, notJson);
            }
        }
        return parsed;
    }
}
