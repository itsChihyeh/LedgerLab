package dev.ledgerlab.ingestion.internal;

import static dev.ledgerlab.ingestion.internal.RefundHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    void ac_8_3_webhooksMapAndPassRealAdmission() throws Exception {
        Set<String> types = new HashSet<>();
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
                // A new event type must add an explicit replay branch here, never be skipped silently.
                switch (event.type()) {
                    case "charge.succeeded" -> replayCharge(entry, body, event, expected);
                    case "refund.created", "refund.updated" -> replayRefundEvent(event, body, expected);
                    case "payout.created", "payout.reconciliation_completed", "payout.paid" ->
                        assertThat(StripeReadParser.payout(
                                        JSON.readTree(body).path("data").path("object")))
                                .isEqualTo(JSON.treeToValue(expected.path("payout"), StripeReader.Payout.class));
                    default -> throw new AssertionError("no replay branch for " + event.type());
                }
                types.add(event.type());
            }
        }
        assertThat(types)
                .contains(
                        "charge.succeeded",
                        "refund.created",
                        "refund.updated",
                        "payout.created",
                        "payout.reconciliation_completed",
                        "payout.paid");
    }

    private static void replayRefundEvent(IncomingEvent event, byte[] body, JsonNode expected) {
        var parsed = new RefundParser(JSON).parse(body);
        var refund = expected.path("refund");
        assertThat(parsed.ref()).isEqualTo(refund.path("ref").asString());
        assertThat(parsed.refundId()).isEqualTo(refund.path("refundId").asString());
        assertThat(parsed.charge()).isEqualTo(refund.path("chargeRef").asString());
        assertThat(parsed.amount()).isEqualTo(refund.path("amount").longValue());
        assertThat(parsed.status().name()).isEqualTo(refund.path("status").asString());
        assertThat(parsed.failureReason()).isNull();
        assertThat(parsed.occurredAt())
                .isEqualTo(Instant.parse(refund.path("occurredAt").asString()));
        assertThat(parsed.fact(event.id()).processorRefundRef()).isEqualTo(parsed.ref());
    }

    private static void replayCharge(JsonNode entry, byte[] body, IncomingEvent event, JsonNode expected)
            throws Exception {
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
            assertThat(result.name()).isEqualTo(expected.path("admission").asString());
            assertThat(jdbc.sql("SELECT amount FROM core.payment WHERE processor_payment_ref=?")
                            .param(charge.chargeId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(charge.amount());
        } finally {
            db.drop();
        }
    }

    @Test
    void ac_8_3_recordedApiResponsesReplayThroughAdapters() throws Exception {
        Set<String> types = new HashSet<>();
        for (Path manifest : manifests()) {
            for (JsonNode entry : read(manifest).path("files")) {
                if (!entry.path("kind").asString().equals("api")) continue;
                byte[] body = Files.readAllBytes(
                        manifest.resolveSibling(".").resolve(entry.path("file").asString()));
                switch (entry.path("type").asString()) {
                    case "balance_transaction" -> replayBalanceTransactions(entry, body);
                    case "payout" -> replayPayoutRetrieve(entry, body);
                    case "refund" -> replayRefund(entry, body);
                    default -> throw new AssertionError("no replay branch for " + entry.path("type"));
                }
                types.add(entry.path("type").asString());
            }
        }
        assertThat(types).contains("balance_transaction", "payout", "refund");
    }

    private static RestStripeReader reader(HttpServer server, TestDatabase.Handle db, JsonNode entry) {
        var source = db.app();
        return new RestStripeReader(
                RestClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build(),
                new StripeProperties(StripeSignatures.SECRET, "sk_test_" + "contractSynthetic"),
                JdbcClient.create(source),
                new JdbcTransactionManager(source),
                JSON,
                Clock.fixed(Instant.parse(entry.path("recorded_at").asString()), ZoneOffset.UTC));
    }

    private static HttpServer replayServer(JsonNode entry, byte[] body) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(entry.path("path").asString(), exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(entry.path("status").intValue(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void replayBalanceTransactions(JsonNode entry, byte[] body) throws Exception {
        var server = replayServer(entry, body);
        var db = TestDatabase.freshMigrated();
        try {
            var expectedItems = entry.path("expected").path("items");
            // S1 has no payout yet; its list response exercises the same reader mapping.
            String payoutId = entry.path("expected").path("payoutId").asString("po_contract");
            var result = reader(server, db, entry)
                    .listBalanceTransactionsForPayout(payoutId, StripeReader.FetchPurpose.BATCH_LINES, null);
            assertThat(result.complete()).as(result.failure()).isTrue();
            assertThat(result.items()).hasSize(expectedItems.size());
            for (int i = 0; i < expectedItems.size(); i++) {
                assertThat(result.items().get(i))
                        .isEqualTo(JSON.treeToValue(expectedItems.get(i), StripeReader.BalanceTransaction.class));
            }
        } finally {
            server.stop(0);
            db.drop();
        }
    }

    private static void replayPayoutRetrieve(JsonNode entry, byte[] body) throws Exception {
        var server = replayServer(entry, body);
        var db = TestDatabase.freshMigrated();
        try {
            var expected = JSON.treeToValue(entry.path("expected").path("payout"), StripeReader.Payout.class);
            var result =
                    reader(server, db, entry).retrievePayout(expected.id(), StripeReader.FetchPurpose.PAYOUT, null);
            assertThat(result.complete()).as(result.failure()).isTrue();
            assertThat(result.items()).containsExactly(expected);
        } finally {
            server.stop(0);
            db.drop();
        }
    }

    private static void replayRefund(JsonNode entry, byte[] body) throws Exception {
        var db = TestDatabase.freshMigrated();
        try (var server = new RefundHttpServer()) {
            var expected = entry.path("expected").path("refund");
            Instant at = Instant.parse(entry.path("recorded_at").asString());
            var clock = Clock.fixed(at, ZoneOffset.UTC);
            var jdbc = JdbcClient.create(db.app());
            var gateway = server.gateway(
                    jdbc,
                    new JdbcTransactionManager(db.app()),
                    JSON,
                    clock,
                    new JdbcIngestionIssues(jdbc, IdGenerator.seeded(clock, 607), clock));
            server.handler = exchange ->
                    respond(exchange, entry.path("status").intValue(), new String(body, StandardCharsets.UTF_8));
            String ref = expected.path("ref").asString();
            String charge = expected.path("chargeRef").asString();
            String refundId = expected.path("refundId").asString();
            var response = switch (entry.path("expected").path("operation").asString()) {
                case "create" ->
                    gateway.create(refundId, charge, expected.path("amount").longValue(), refundId);
                case "retrieve" -> gateway.retrieve(ref);
                case "findByRefundId" -> gateway.findByRefundId(charge, refundId);
                default -> throw new AssertionError("no replay branch for refund operation");
            };
            assertThat(response.value()).isInstanceOf(ProcessorRefundGateway.Found.class);
            var found = (ProcessorRefundGateway.Found) response.value();
            assertThat(found.processorRefundRef()).isEqualTo(ref);
            assertThat(found.status().name()).isEqualTo(expected.path("status").asString());
            assertThat(found.failureReason()).isNull();
            var details = java.util.Objects.requireNonNull(found.details());
            assertThat(details.amount()).isEqualTo(expected.path("amount").longValue());
            assertThat(details.chargeRef()).isEqualTo(charge);
            assertThat(details.refundId()).isEqualTo(refundId);
            assertThat(details.receivedAt()).isEqualTo(at);
            assertThat(server.requests).hasSize(1).allSatisfy(request -> {
                assertThat(request.version())
                        .isEqualTo(entry.path("api_version").asString());
                assertThat(request.method()).isEqualTo(entry.path("method").asString());
                assertThat(request.path()).isEqualTo(entry.path("path").asString());
            });
            if (entry.path("method").asString().equals("POST")) {
                var request = server.requests.getFirst();
                assertThat(request.key()).isEqualTo(refundId);
                assertThat(request.form())
                        .contains(
                                "charge=" + charge,
                                "amount=" + expected.path("amount").longValue(),
                                "metadata[ledgerlab_refund_id]=" + refundId);
            }
        } finally {
            db.drop();
        }
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
                } else if (body.path("object").asString().equals("list")) {
                    assertThat(body.path("data").isArray()).isTrue();
                    assertThat(body.path("has_more").isBoolean()).isTrue();
                    for (JsonNode object : body.path("data")) {
                        String type = entry.path("type").asString();
                        fields(schema, type, object);
                        checked.add(type);
                    }
                } else {
                    // Single-object responses: create/retrieve.
                    String type = entry.path("type").asString();
                    fields(schema, type, body);
                    checked.add(type);
                }
            }
        }
        assertThat(checked).contains("event", "charge", "balance_transaction", "refund", "payout");
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
