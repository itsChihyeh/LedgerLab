package dev.ledgerlab.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.sim.processor.Payouts;
import dev.ledgerlab.sim.processor.Processor;
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

    private static void fields(JsonNode schema, String type, JsonNode real, JsonNode fake) {
        assertThat(schema.path(type).isObject()).isTrue();
        for (var field : schema.path(type).properties()) {
            String realType = type(real.at(field.getKey()));
            String fakeType = type(fake.at(field.getKey()));
            assertThat(Arrays.asList(field.getValue().asString().split("\\|")))
                    .as(type + field.getKey())
                    .contains(realType, fakeType);
            assertThat(fakeType).as(type + field.getKey()).isEqualTo(realType);
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
