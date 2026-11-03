package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Recorded real Stripe evidence only. No network except the loopback replay server. */
class StripeSandboxContractTest {
    private static final Path ROOT = Path.of("src/test/resources/contracts/stripe-sandbox");
    private static final JsonMapper JSON = new JsonMapper();

    private static List<Path> manifests() throws Exception {
        try (var paths = Files.walk(ROOT)) {
            var manifests = paths.filter(p -> p.getFileName().toString().equals("manifest.json"))
                    .sorted()
                    .toList();
            assertThat(manifests).isNotEmpty();
            return manifests;
        }
    }

    private static JsonNode read(Path file) throws Exception {
        return JSON.readTree(Files.readAllBytes(file));
    }

    @Test
    void ac_8_3_webhooksMapAndPassRealChargeAdmission() throws Exception {
        int checked = 0;
        for (Path manifest : manifests()) {
            for (JsonNode entry : read(manifest).path("files")) {
                if (!entry.path("kind").asString().equals("webhook")) continue;
                byte[] body = Files.readAllBytes(
                        manifest.resolveSibling(".").resolve(entry.path("file").asString()));
                var expected = entry.path("expected");
                var event = new StripeEventParser(JSON).parse(body);
                assertThat(event).isEqualTo(JSON.treeToValue(expected.path("event"), IncomingEvent.class));
                assertThat(event.apiVersion())
                        .isEqualTo(entry.path("api_version").asString());
                // T06 supplies S1. New scenarios must add an explicit replay branch, never silently skip.
                assertThat(event.type()).isEqualTo("charge.succeeded");
                Instant received = Instant.parse(entry.path("recorded_at").asString());
                var charge = new ChargeParser(JSON).parse(event.id(), body, event.livemode(), received);
                assertThat(charge).isEqualTo(JSON.treeToValue(expected.path("charge"), ChargeEvidence.class));
                var db = TestDatabase.freshMigrated();
                try {
                    var source = db.app();
                    var jdbc = JdbcClient.create(source);
                    var clock = Clock.fixed(received, ZoneOffset.UTC);
                    var ids = IdGenerator.seeded(clock, 606);
                    var capture = new PaymentCapture(
                            new JdbcLedger(source, clock, ids),
                            jdbc,
                            new JdbcIngestionIssues(jdbc, ids, clock),
                            ids,
                            clock,
                            JSON);
                    jdbc.sql("""
                            INSERT INTO io.inbox_event
                            (event_id,event_type,api_version,livemode,payload,payload_sha256,
                             signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
                            VALUES (:id,:type,:version,false,:body,encode(sha256(:body),'hex'),
                             :at,:at,:at,:at,'RECEIVED',:at)
                            """)
                            .param("id", event.id())
                            .param("type", event.type())
                            .param("version", event.apiVersion())
                            .param("body", body)
                            .param("at", received.atOffset(ZoneOffset.UTC))
                            .update();
                    var result = new TransactionTemplate(new JdbcTransactionManager(source))
                            .execute(ignored -> capture.capture(charge));
                    assertThat(result).isNotNull();
                    assertThat(result.name())
                            .isEqualTo(expected.path("admission").asString());
                    assertThat(jdbc.sql("SELECT amount FROM core.payment WHERE processor_payment_ref=?")
                                    .param(charge.chargeId())
                                    .query(Long.class)
                                    .single())
                            .isEqualTo(charge.amount());
                } finally {
                    db.drop();
                }
                checked++;
            }
        }
        assertThat(checked).isPositive();
    }

    @Test
    void ac_8_3_recordedApiResponsesReplayThroughReader() throws Exception {
        int checked = 0;
        for (Path manifest : manifests()) {
            for (JsonNode entry : read(manifest).path("files")) {
                if (!entry.path("kind").asString().equals("api")) continue;
                assertThat(entry.path("type").asString()).isEqualTo("balance_transaction");
                byte[] body = Files.readAllBytes(
                        manifest.resolveSibling(".").resolve(entry.path("file").asString()));
                var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                var db = TestDatabase.freshMigrated();
                try {
                    server.createContext(entry.path("path").asString(), exchange -> {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(entry.path("status").intValue(), body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
                    server.start();
                    var source = db.app();
                    var reader = new RestStripeReader(
                            RestClient.builder()
                                    .baseUrl("http://127.0.0.1:"
                                            + server.getAddress().getPort())
                                    .build(),
                            new StripeProperties(StripeSignatures.SECRET, "sk_test_" + "contractSynthetic"),
                            JdbcClient.create(source),
                            new JdbcTransactionManager(source),
                            JSON,
                            Clock.fixed(Instant.parse(entry.path("recorded_at").asString()), ZoneOffset.UTC));
                    // S1 list response tests the same reader mapping before an automatic payout exists.
                    var result = reader.listBalanceTransactionsForPayout(
                            "po_contract", StripeReader.FetchPurpose.BATCH_LINES, null);
                    assertThat(result.complete()).as(result.failure()).isTrue();
                    checked++;
                    var expected = entry.path("expected").path("items");
                    assertThat(result.items()).hasSize(expected.size());
                    for (int i = 0; i < expected.size(); i++) {
                        assertThat(result.items().get(i))
                                .isEqualTo(JSON.treeToValue(expected.get(i), StripeReader.BalanceTransaction.class));
                    }
                } finally {
                    server.stop(0);
                    db.drop();
                }
            }
        }
        assertThat(checked).isPositive();
    }

    @Test
    void ac_8_3_usedFieldsExistWithDeclaredJsonTypes() throws Exception {
        JsonNode schema = read(ROOT.resolve("used-fields.json"));
        Set<String> checked = new HashSet<>();
        for (Path manifest : manifests()) {
            for (JsonNode entry : read(manifest).path("files")) {
                JsonNode body = read(
                        manifest.resolveSibling(".").resolve(entry.path("file").asString()));
                if (entry.path("kind").asString().equals("webhook")) {
                    fields(schema, "event", body);
                    checked.add("event");
                    JsonNode object = body.path("data").path("object");
                    String type = object.path("object").asString();
                    fields(schema, type, object);
                    checked.add(type);
                } else {
                    assertThat(body.path("data").isArray()).isTrue();
                    assertThat(body.path("has_more").isBoolean()).isTrue();
                    for (JsonNode object : body.path("data")) {
                        String type = entry.path("type").asString();
                        fields(schema, type, object);
                        checked.add(type);
                    }
                }
            }
        }
        assertThat(checked).contains("event", "charge", "balance_transaction");
        // Refund/payout recordings belong to T08; the catalog already reflects their actual parsers.
    }

    static void fields(JsonNode schema, String type, JsonNode object) {
        assertThat(schema.path(type).isObject()).as(type).isTrue();
        for (var field : schema.path(type).properties()) {
            JsonNode value = object.at(field.getKey());
            String actual = value.isMissingNode()
                    ? "missing"
                    : value.isNull()
                            ? "null"
                            : value.isString()
                                    ? "string"
                                    : value.isBoolean()
                                            ? "boolean"
                                            : value.isIntegralNumber() && value.canConvertToLong()
                                                    ? "integer"
                                                    : value.isObject()
                                                            ? "object"
                                                            : value.isArray() ? "array" : "number";
            assertThat(Arrays.asList(field.getValue().asString().split("\\|")))
                    .as(type + field.getKey())
                    .contains(actual);
        }
    }

    @Test
    void ac_8_3_fixtureDirectoriesHaveIdenticalSha256AndNoSensitiveData() throws Exception {
        Path mirror = Path.of("simulator").resolve(ROOT);
        try (var mainFiles = Files.walk(ROOT);
                var simFiles = Files.walk(mirror)) {
            var paths = mainFiles
                    .filter(Files::isRegularFile)
                    .map(ROOT::relativize)
                    .sorted()
                    .toList();
            assertThat(simFiles.filter(Files::isRegularFile)
                            .map(mirror::relativize)
                            .sorted()
                            .toList())
                    .isEqualTo(paths);
            for (Path path : paths) {
                byte[] bytes = Files.readAllBytes(ROOT.resolve(path));
                assertThat(MessageDigest.getInstance("SHA-256").digest(bytes))
                        .isEqualTo(
                                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(mirror.resolve(path))));
                assertThat(JSON.readTree(bytes).toString())
                        .doesNotContainPattern("(?i)sk_|rk_|whsec_|_secret_|@|acct_(?!REDACTED(?![A-Za-z0-9_]))");
            }
        }
    }
}
