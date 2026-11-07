package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.sim.processor.Payouts;
import dev.ledgerlab.sim.processor.Processor;
import dev.ledgerlab.sim.processor.Refunds;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FakeStripeContractTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final Path ROOT = Path.of("src/test/resources/contracts/stripe-sandbox");

    @Test
    void ac_8_3_s1FakeEventsAndObjectsMatchRealStripeUsedFields() throws Exception {
        JsonNode manifest = JSON.readTree(Files.readAllBytes(ROOT.resolve("s1-payment/manifest.json")));
        JsonNode schema = JSON.readTree(Files.readAllBytes(ROOT.resolve("used-fields.json")));
        assertThat(manifest.path("scenario").asString()).isEqualTo("s1");
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        var receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try (var database = TestDatabase.start()) {
            try (var app = new SpringApplication(SimulatorApplication.class)
                    .run(
                            "--server.port=0",
                            "--spring.datasource.url=" + database.getJdbcUrl(),
                            "--spring.datasource.password=test-simulator",
                            "--sim.stripe.webhook-secret=synthetic-only",
                            "--sim.stripe.webhook-url=http://127.0.0.1:"
                                    + receiver.getAddress().getPort() + "/hook",
                            "--sim.clock.start=" + manifest.path("recorded_at").asString(),
                            "--sim.seed=606")) {
                var processor = app.getBean(Processor.class);
                List<String> expectedSequence = new ArrayList<>();
                JsonNode fakeCharge = null;
                JsonNode realCharge = null;
                for (JsonNode entry : manifest.path("files")) {
                    if (!entry.path("kind").asString().equals("webhook")) continue;
                    JsonNode real = JSON.readTree(Files.readAllBytes(ROOT.resolve("s1-payment")
                            .resolve(entry.path("file").asString())));
                    expectedSequence.add(real.path("type").asString());
                    realCharge = real.path("data").path("object");
                    var request = JSON.createObjectNode();
                    request.set("amount", realCharge.path("amount"));
                    request.set("currency", realCharge.path("currency"));
                    var result = processor.create(request);
                    fakeCharge = JSON.readTree(JSON.writeValueAsBytes(result.get("charge")));
                    fields(schema, "charge", realCharge, fakeCharge);
                    JsonNode fakeEvent = received.getLast();
                    fields(schema, "event", real, fakeEvent);
                    assertThat(fakeEvent.path("api_version")).isEqualTo(real.path("api_version"));
                    for (String field :
                            List.of("amount", "amount_captured", "currency", "captured", "status", "livemode")) {
                        assertThat(fakeCharge.path(field)).isEqualTo(realCharge.path(field));
                    }
                }
                assertThat(expectedSequence).containsExactly("charge.succeeded");
                assertThat(received.stream().map(e -> e.path("type").asString()).toList())
                        .containsExactlyElementsOf(expectedSequence);
                java.util.Objects.requireNonNull(fakeCharge);
                java.util.Objects.requireNonNull(realCharge);
                // Only after asserting the S1 sequence, allocate a fake payout to expose its charge line
                // through the existing public API (fake lists require a payout filter).
                var payouts = app.getBean(Payouts.class);
                var payout = JSON.valueToTree(payouts.create(JSON.createObjectNode()))
                        .path("payout");
                var lines = payouts.lines(payout.path("id").asString(), Map.of("limit", "100"));
                JsonNode fakeLine = null;
                for (JsonNode line : lines.path("data")) {
                    if (line.path("source").equals(fakeCharge.path("id"))) fakeLine = line;
                }
                java.util.Objects.requireNonNull(fakeLine);
                int checked = 0;
                for (JsonNode entry : manifest.path("files")) {
                    if (!entry.path("kind").asString().equals("api")) continue;
                    var realPage = JSON.readTree(Files.readAllBytes(ROOT.resolve("s1-payment")
                            .resolve(entry.path("file").asString())));
                    for (JsonNode line : realPage.path("data")) {
                        if (!line.path("source").equals(realCharge.path("id"))) continue;
                        fields(schema, "balance_transaction", line, fakeLine);
                        assertThat(fakeLine.path("amount").longValue())
                                .isEqualTo(line.path("amount").longValue());
                        assertThat(fakeLine.path("currency")).isEqualTo(line.path("currency"));
                        // Known difference, design §9.9: fake uses the domestic fee formula;
                        // the S1 bypass card is US-issued. Values are not required to match.
                        assertThat(fakeLine.path("fee").longValue())
                                .isEqualTo(dev.ledgerlab.sim.processor.BalanceTransactions.contractFee(
                                        line.path("amount").longValue()));
                        checked++;
                    }
                }
                assertThat(checked).isPositive();
            }
        } finally {
            receiver.stop(0);
        }
    }

    @FunctionalInterface
    private interface AppBody {
        void run(ConfigurableApplicationContext app, List<JsonNode> received) throws Exception;
    }

    private static void withApp(String clockStart, AppBody body) throws Exception {
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        var receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        try (var database = TestDatabase.start()) {
            try (var app = new SpringApplication(SimulatorApplication.class)
                    .run(
                            "--server.port=0",
                            "--spring.datasource.url=" + database.getJdbcUrl(),
                            "--spring.datasource.password=test-simulator",
                            "--sim.stripe.webhook-secret=synthetic-only",
                            "--sim.stripe.webhook-url=http://127.0.0.1:"
                                    + receiver.getAddress().getPort() + "/hook",
                            "--sim.clock.start=" + clockStart,
                            "--sim.seed=606")) {
                body.run(app, received);
            }
        } finally {
            receiver.stop(0);
        }
    }

    private static JsonNode manifest(String directory) throws Exception {
        return JSON.readTree(Files.readAllBytes(ROOT.resolve(directory).resolve("manifest.json")));
    }

    private static JsonNode fixture(String directory, JsonNode entry) throws Exception {
        return JSON.readTree(Files.readAllBytes(
                ROOT.resolve(directory).resolve(entry.path("file").asString())));
    }

    private static JsonNode createCharge(ConfigurableApplicationContext app) throws Exception {
        var request = JSON.createObjectNode();
        request.put("amount", 11500);
        request.put("currency", "cad");
        return JSON.readTree(JSON.writeValueAsBytes(
                app.getBean(Processor.class).create(request).get("charge")));
    }

    private static List<JsonNode> ofTypePrefix(List<JsonNode> received, String prefix) {
        return received.stream()
                .filter(e -> e.path("type").asString().startsWith(prefix))
                .toList();
    }

    @Test
    void ac_8_3_s2FakeRefundMatchesRealStripeUsedFieldsAndDocumentsEventDifference() throws Exception {
        JsonNode manifest = manifest("s2-refund");
        JsonNode schema = JSON.readTree(Files.readAllBytes(ROOT.resolve("used-fields.json")));
        assertThat(manifest.path("scenario").asString()).isEqualTo("s2");
        JsonNode realCreate = null;
        JsonNode realList = null;
        List<JsonNode> realEvents = new ArrayList<>();
        for (JsonNode entry : manifest.path("files")) {
            String file = entry.path("file").asString();
            if (file.startsWith("01-")) realCreate = fixture("s2-refund", entry);
            if (file.startsWith("04-")) realList = fixture("s2-refund", entry);
            if (entry.path("kind").asString().equals("webhook")) realEvents.add(fixture("s2-refund", entry));
        }
        java.util.Objects.requireNonNull(realCreate);
        java.util.Objects.requireNonNull(realList);
        final JsonNode realRefund = realCreate;
        final JsonNode listed = realList;
        withApp(manifest.path("recorded_at").asString(), (app, received) -> {
            String charge = createCharge(app).path("id").asString();
            String refundId = realRefund.at("/metadata/ledgerlab_refund_id").asString();
            var refunds = app.getBean(Refunds.class);
            var fake = JSON.readTree(JSON.writeValueAsBytes(refunds.create(
                            Map.of(
                                    "charge",
                                    charge,
                                    "amount",
                                    realRefund.path("amount").asString(),
                                    "metadata[ledgerlab_refund_id]",
                                    refundId),
                            refundId)
                    .refund()));
            fields(schema, "refund", realRefund, fake, false);
            for (String field : List.of("amount", "currency", "status"))
                assertThat(fake.path(field)).isEqualTo(realRefund.path(field));
            assertThat(fake.at("/metadata/ledgerlab_refund_id"))
                    .isEqualTo(realRefund.at("/metadata/ledgerlab_refund_id"));

            var fakeEvents = ofTypePrefix(received, "refund.");
            // Known difference, design §9.9: real Stripe follows refund.created with refund.updated
            // (card reference filled in). The fake sends only refund.created for a direct success.
            assertThat(realEvents.stream().map(e -> e.path("type").asString()).toList())
                    .containsExactly("refund.created", "refund.updated");
            assertThat(fakeEvents.stream().map(e -> e.path("type").asString()).toList())
                    .containsExactly("refund.created");
            fields(schema, "event", realEvents.getFirst(), fakeEvents.getFirst(), false);
            fields(
                    schema,
                    "refund",
                    realEvents.getFirst().path("data").path("object"),
                    fakeEvents.getFirst().path("data").path("object"),
                    false);
            assertThat(fakeEvents.getFirst().path("api_version"))
                    .isEqualTo(realEvents.getFirst().path("api_version"));

            var fakeList = JSON.readTree(JSON.writeValueAsBytes(refunds.list(Map.of("charge", charge))));
            assertThat(fakeList.path("has_more").isBoolean()).isTrue();
            assertThat(listed.path("has_more").isBoolean()).isTrue();
            fields(
                    schema,
                    "refund",
                    listed.path("data").get(0),
                    fakeList.path("data").get(0),
                    false);
            fields(
                    schema,
                    "refund",
                    realRefund,
                    JSON.readTree(
                            JSON.writeValueAsBytes(refunds.get(fake.path("id").asString()))),
                    false);
        });
    }

    @Test
    void ac_8_3_s4FakePayoutLifecycleMatchesRealStripeUsedFieldsAndDocumentsDifferences() throws Exception {
        JsonNode manifest = manifest("s4-payout");
        JsonNode schema = JSON.readTree(Files.readAllBytes(ROOT.resolve("used-fields.json")));
        assertThat(manifest.path("scenario").asString()).isEqualTo("s4");
        List<JsonNode> realEvents = new ArrayList<>();
        JsonNode realLines = null;
        for (JsonNode entry : manifest.path("files")) {
            if (entry.path("kind").asString().equals("webhook")) realEvents.add(fixture("s4-payout", entry));
            if (entry.path("type").asString().equals("balance_transaction")) realLines = fixture("s4-payout", entry);
        }
        java.util.Objects.requireNonNull(realLines);
        final JsonNode lines = realLines;
        assertThat(realEvents.stream().map(e -> e.path("type").asString()).toList())
                .containsExactly("payout.created", "payout.reconciliation_completed", "payout.paid");
        withApp(manifest.path("recorded_at").asString(), (app, received) -> {
            createCharge(app);
            var payouts = app.getBean(Payouts.class);
            var created =
                    JSON.valueToTree(payouts.create(JSON.createObjectNode())).path("payout");
            String id = created.path("id").asString();
            payouts.transition(id, "reconcile", JSON.createObjectNode());
            payouts.transition(id, "paid", JSON.createObjectNode());
            var fakeEvents = ofTypePrefix(received, "payout.");
            assertThat(fakeEvents.stream().map(e -> e.path("type").asString()).toList())
                    .isEqualTo(realEvents.stream()
                            .map(e -> e.path("type").asString())
                            .toList());
            for (int i = 0; i < realEvents.size(); i++) {
                fields(schema, "event", realEvents.get(i), fakeEvents.get(i), false);
                fields(
                        schema,
                        "payout",
                        realEvents.get(i).path("data").path("object"),
                        fakeEvents.get(i).path("data").path("object"),
                        false);
                assertThat(fakeEvents.get(i).path("api_version"))
                        .isEqualTo(realEvents.get(i).path("api_version"));
            }
            // Known differences, design §9.9: real Stripe reports payout.created as in_transit with
            // reconciliation already completed; the fake starts pending/in_progress and completes later.
            assertThat(realEvents.getFirst().at("/data/object/status").asString())
                    .isEqualTo("in_transit");
            assertThat(realEvents
                            .getFirst()
                            .at("/data/object/reconciliation_status")
                            .asString())
                    .isEqualTo("completed");
            assertThat(fakeEvents.getFirst().at("/data/object/status").asString())
                    .isEqualTo("pending");
            assertThat(fakeEvents
                            .getFirst()
                            .at("/data/object/reconciliation_status")
                            .asString())
                    .isEqualTo("in_progress");
            assertThat(fakeEvents.getLast().at("/data/object/status").asString())
                    .isEqualTo(realEvents.getLast().at("/data/object/status").asString());

            var fakeLines = payouts.lines(id, Map.of("limit", "100"));
            assertThat(fakeLines.path("has_more").isBoolean()).isTrue();
            assertThat(lines.path("has_more").isBoolean()).isTrue();
            int compared = 0;
            for (String type : List.of("charge", "payout")) {
                JsonNode real = null;
                JsonNode fake = null;
                for (JsonNode line : lines.path("data"))
                    if (line.path("type").asString().equals(type)) real = line;
                for (JsonNode line : fakeLines.path("data"))
                    if (line.path("type").asString().equals(type)) fake = line;
                java.util.Objects.requireNonNull(real, type);
                java.util.Objects.requireNonNull(fake, type);
                fields(schema, "balance_transaction", real, fake, false);
                compared++;
            }
            assertThat(compared).isEqualTo(2);
        });
    }

    private static void fields(JsonNode schema, String type, JsonNode real, JsonNode fake) {
        fields(schema, type, real, fake, true);
    }

    /** strict: identical JSON types; otherwise both only need to be allowed by the declared union. */
    private static void fields(JsonNode schema, String type, JsonNode real, JsonNode fake, boolean strict) {
        assertThat(schema.path(type).isObject()).isTrue();
        for (var field : schema.path(type).properties()) {
            String realType = type(real.at(field.getKey()));
            String fakeType = type(fake.at(field.getKey()));
            assertThat(Arrays.asList(field.getValue().asString().split("\\|")))
                    .as(type + field.getKey())
                    .contains(realType, fakeType);
            if (strict) assertThat(fakeType).as(type + field.getKey()).isEqualTo(realType);
        }
    }

    private static String type(JsonNode value) {
        return value.isMissingNode()
                ? "missing"
                : value.isNull()
                        ? "null"
                        : value.isString()
                                ? "string"
                                : value.isBoolean()
                                        ? "boolean"
                                        : value.isIntegralNumber() && value.canConvertToLong()
                                                ? "integer"
                                                : value.isObject() ? "object" : value.isArray() ? "array" : "number";
    }
}
