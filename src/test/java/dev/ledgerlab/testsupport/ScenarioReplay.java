package dev.ledgerlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ingestion.internal.InboxWorker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Consumes exported files only. SQL is used for assertions and external-to-local identity alignment. */
public final class ScenarioReplay {
    private final Path directory;
    private final ScenarioHttpReplay processor;
    private final HttpClient http;
    private final String base;
    private final MutableClock clock;
    private final InboxWorker worker;
    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, List<JsonNode>> reports = new LinkedHashMap<>();
    private final Runnable outbound;

    public ScenarioReplay(
            Path directory,
            ScenarioHttpReplay processor,
            HttpClient http,
            String base,
            MutableClock clock,
            InboxWorker worker,
            JdbcClient jdbc) {
        this(directory, processor, http, base, clock, worker, jdbc, () -> {
            throw new AssertionError("Scenario requires an outbound worker");
        });
    }

    public ScenarioReplay(
            Path directory,
            ScenarioHttpReplay processor,
            HttpClient http,
            String base,
            MutableClock clock,
            InboxWorker worker,
            JdbcClient jdbc,
            Runnable outbound) {
        this.directory = directory;
        this.processor = processor;
        this.http = http;
        this.base = base;
        this.clock = clock;
        this.worker = worker;
        this.jdbc = jdbc;
        this.outbound = outbound;
    }

    public void run() throws Exception {
        runUntil("");
    }

    /** Replay through a named checkpoint, retaining the same independent truth assertions. */
    public void runUntil(String checkpointName) throws Exception {
        JsonNode manifest = json.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        JsonNode truth = json.readTree(Files.readAllBytes(directory.resolve("truth.json")));
        assertThat(manifest.path("format_version").asInt()).isEqualTo(1);
        assertThat(truth.path("format_version").asInt()).isEqualTo(1);
        for (JsonNode step : manifest.path("steps")) {
            clock.set(Instant.parse(step.path("at").asText()));
            processor.expect(step.path("responses"));
            switch (step.path("type").asText()) {
                case "refund_api" -> {
                    var request = HttpRequest.newBuilder(URI.create(base + "/api/refunds"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", step.path("key").asString())
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    step.path("request").toString()))
                            .build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                    processor.bind(
                            step.path("local_ref").asString(),
                            json.readTree(response.body()).path("refund_id").asString());
                }
                case "outbound" -> outbound.run();
                case "event" -> {
                    byte[] raw = processor
                            .substitute(Files.readString(
                                    directory.resolve(step.path("body").asText())))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    var request = HttpRequest.newBuilder(URI.create(base + "/webhooks/stripe"))
                            .header("Content-Type", "application/json")
                            .header(
                                    "Stripe-Signature",
                                    StripeSignatures.sign(raw, clock.instant().getEpochSecond()))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                            .build();
                    assertThat(http.send(request, HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                            .isEqualTo(200);
                    int attempts = 0;
                    while (worker.processAvailable() != 0)
                        assertThat(++attempts).isLessThan(100);
                    assertThat(jdbc.sql("select status from io.inbox_event where event_id=?")
                                    .param(json.readTree(raw).path("id").asText())
                                    .query(String.class)
                                    .single())
                            .isEqualTo("PROCESSED");
                    assertThat(jdbc.sql("select payload from io.inbox_event where event_id=?")
                                    .param(json.readTree(raw).path("id").asText())
                                    .query(byte[].class)
                                    .single())
                            .isEqualTo(raw);
                }
                case "snapshot" -> {
                    JsonNode result = post(
                            "/api/recon/processor-snapshots",
                            "application/json",
                            json.writeValueAsBytes(Map.of(
                                    "businessDate", step.path("business_date").asText())));
                    assertThat(result.path("status").asText()).isEqualTo("ACCEPTED");
                }
                case "bank" -> {
                    JsonNode result = post(
                            "/api/bank-statements",
                            "text/csv",
                            Files.readAllBytes(
                                    directory.resolve(step.path("body").asText())));
                    assertThat(result.path("status").asText()).isEqualTo("ACCEPTED");
                }
                case "checkpoint" -> {
                    List<JsonNode> matches = new ArrayList<>();
                    truth.path("checkpoints").forEach(c -> {
                        if (c.path("name").equals(step.path("name"))) matches.add(c);
                    });
                    assertThat(matches).hasSize(1);
                    check(json.readTree(processor.substitute(matches.getFirst().toString())));
                }
                default -> throw new AssertionError("Unknown replay step: " + step);
            }
            processor.assertConsumed();
            if (step.path("type").asText().equals("checkpoint")
                    && step.path("name").asText().equals(checkpointName)) break;
        }
        // Later imports and runs cannot rewrite earlier reports.
        for (var checkpoint : reports.values())
            for (JsonNode report : checkpoint)
                assertThat(get("/api/recon/runs/" + report.path("id").asText())).isEqualTo(report);
    }

    private void check(JsonNode truth) throws Exception {
        List<String> ledgerBefore = ledger();
        byte[] request = json.writeValueAsBytes(Map.of(
                "dryRun",
                false,
                "kind",
                "ALL",
                "cutoffAt",
                truth.path("cutoff_at").asText(),
                "evidenceAsOf",
                truth.path("evidence_as_of").asText()));
        JsonNode result = post("/api/recon/runs", "application/json", request);
        Map<String, JsonNode> actual = findings(result);
        Map<String, JsonNode> expected = new LinkedHashMap<>();
        for (JsonNode finding : truth.path("expected_findings")) {
            String key = key(
                    finding.path("kind").asText(),
                    finding.path("category").asText(),
                    finding.path("subject_type").asText(),
                    finding.path("subject_id").asText());
            assertThat(expected.put(key, finding)).isNull();
        }
        assertThat(actual.keySet())
                .as("Exact truth set at %s (zero false positives/negatives)", truth.path("name"))
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key, expectedFinding) -> {
            JsonNode found = actual.get(key);
            for (String field : List.of("expected", "actual"))
                if (expectedFinding.has(field))
                    contains(json.readTree(found.path(field).asText()), expectedFinding.path(field));
            if (expectedFinding.has("due_at"))
                assertThat(found.path("dueAt").asText())
                        .isEqualTo(expectedFinding.path("due_at").asText());
        });
        long issueCount =
                jdbc.sql("select count(*) from recon.issue").query(Long.class).single();
        JsonNode repeated = post("/api/recon/runs", "application/json", request);
        assertThat(findings(repeated)).isEqualTo(actual);
        assertThat(jdbc.sql("select count(*) from recon.issue")
                        .query(Long.class)
                        .single())
                .isEqualTo(issueCount);
        List<JsonNode> saved = new ArrayList<>();
        result.path("runs").forEach(saved::add);
        if (truth.has("replay_of")) {
            var original = reports.get(truth.path("replay_of").asText());
            assertThat(original).isNotNull();
            for (int i = 0; i < saved.size(); i++) {
                assertThat(saved.get(i).path("findings"))
                        .isEqualTo(original.get(i).path("findings"));
                assertThat(saved.get(i).path("inputHashes"))
                        .isEqualTo(original.get(i).path("inputHashes"));
            }
        }
        reports.put(truth.path("name").asText(), saved);
        for (JsonNode explained : truth.path("explained")) {
            String localId = localIdentity(
                    explained.path("subject_type").asText(),
                    explained.path("subject_id").asText());
            var issue = jdbc.sql(
                            "select status, explanation, evidence_refs::text from recon.issue where category=? and subject_type=? and subject_id=?")
                    .params(
                            explained.path("category").asText(),
                            explained.path("subject_type").asText(),
                            localId)
                    .query()
                    .singleRow();
            assertThat(issue.get("status")).isEqualTo("EXPLAINED");
            assertThat(jdbc.sql(
                                    "select t.evidence_refs::text from recon.issue_transition t join recon.issue i on i.id=t.issue_id where i.category=? and i.subject_type=? and i.subject_id=? and t.to_status='EXPLAINED' order by t.id desc limit 1")
                            .params(
                                    explained.path("category").asText(),
                                    explained.path("subject_type").asText(),
                                    localId)
                            .query(String.class)
                            .single())
                    .contains(explained.path("evidence_contains").asText());
        }
        truth.path("expected_balances")
                .properties()
                .forEach(e -> assertThat(jdbc.sql("select balance from ledger.account where code=?")
                                .param(Integer.parseInt(e.getKey()))
                                .query(Long.class)
                                .single())
                        .isEqualTo(e.getValue().asLong()));
        for (JsonNode entry : truth.path("expected_entries"))
            assertThat(jdbc.sql("select posting_count from ledger.journal_entry where subject_id=? and entry_type=?")
                            .params(
                                    entry.path("subject_id").asText(),
                                    entry.path("entry_type").asText())
                            .query(Integer.class)
                            .single())
                    .isEqualTo(entry.path("posting_count").asInt());
        assertThat(ledger()).isEqualTo(ledgerBefore);
    }

    private Map<String, JsonNode> findings(JsonNode result) {
        Map<String, JsonNode> findings = new LinkedHashMap<>();
        for (JsonNode run : result.path("runs"))
            for (JsonNode observed : run.path("findings")) {
                ObjectNode finding = ((ObjectNode) observed.path("finding")).deepCopy();
                String type = finding.path("subjectType").asText();
                String id = finding.path("subjectId").asText();
                if (type.equals("payment"))
                    id = jdbc.sql("select processor_payment_ref from core.payment where id=?")
                            .param(id)
                            .query(String.class)
                            .single();
                String key =
                        key(run.path("kind").asText(), finding.path("category").asText(), type, id);
                assertThat(findings.put(key, finding))
                        .as("Duplicate finding %s", key)
                        .isNull();
            }
        return findings;
    }

    private String localIdentity(String type, String external) {
        return type.equals("payment")
                ? jdbc.sql("select id from core.payment where processor_payment_ref=?")
                        .param(external)
                        .query(String.class)
                        .single()
                : external;
    }

    private List<String> ledger() {
        List<String> result = new ArrayList<>();
        for (String table : List.of("account", "journal_entry", "posting"))
            result.addAll(jdbc.sql("select row_to_json(t)::text from ledger." + table + " t order by "
                            + (table.equals("account") ? "code" : "id"))
                    .query(String.class)
                    .list());
        return result;
    }

    private static String key(String kind, String category, String type, String id) {
        return kind + "/" + category + "/" + type + "/" + id;
    }

    private static void contains(JsonNode actual, JsonNode expected) {
        if (expected.isObject()) expected.properties().forEach(e -> contains(actual.path(e.getKey()), e.getValue()));
        else if (expected.isArray()) {
            assertThat(actual.size()).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) contains(actual.path(i), expected.get(i));
        } else assertThat(actual).isEqualTo(expected);
    }

    private JsonNode post(String path, String contentType, byte[] body) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s: %s", path, response.body())
                .isEqualTo(200);
        return json.readTree(response.body());
    }

    private JsonNode get(String path) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
