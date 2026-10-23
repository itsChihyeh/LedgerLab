package dev.ledgerlab.sim.processor;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class Processor {
    private static final String API_VERSION = "2026-09-30.endive";
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final SimulationClock simulation;
    private final Clock clock;
    private final SeededIds ids;
    private final WebhookSender sender;
    private final BalanceTransactions balances;
    private final JsonMapper json = JsonMapper.builder().build();

    public Processor(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            SimulationClock simulation,
            Clock clock,
            SeededIds ids,
            WebhookSender sender,
            BalanceTransactions balances) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.simulation = simulation;
        this.clock = clock;
        this.ids = ids;
        this.sender = sender;
        this.balances = balances;
    }

    public Map<String, Object> create(JsonNode request) {
        long amount = integer(request, "amount", null);
        ObjectNode charge = json.createObjectNode();
        charge.put("id", ids.next("ch"))
                .put("object", "charge")
                .put("payment_intent", ids.next("pi"))
                .put("amount", amount)
                .put("amount_captured", integer(request, "amount_captured", amount))
                .put("currency", text(request, "currency", "cad"))
                .put("captured", bool(request, "captured", true))
                .put("status", text(request, "status", "succeeded"))
                .put("livemode", bool(request, "livemode", false))
                .put("created", simulation.instant().getEpochSecond());
        ObjectNode event = event(charge, "charge.succeeded");
        transaction.executeWithoutResult(status -> {
            balances.lock();
            jdbc.sql("""
                    insert into sim_processor.charge
                    (id, payment_intent, amount, amount_captured, currency, captured, status, livemode, created, recorded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .param(charge.path("id").asText())
                    .param(charge.path("payment_intent").asText())
                    .param(amount)
                    .param(charge.path("amount_captured").asLong())
                    .param(charge.path("currency").asText())
                    .param(charge.path("captured").asBoolean())
                    .param(charge.path("status").asText())
                    .param(charge.path("livemode").asBoolean())
                    .param(charge.path("created").asLong())
                    .param(Timestamp.from(clock.instant()))
                    .update();
            balances.charge(charge, request);
            store(event);
        });
        sender.deliver(event.path("id").asText(), json.writeValueAsString(event));
        return Map.of("charge", charge, "event_id", event.path("id").asText());
    }

    public Map<String, Object> redeliver(String id) {
        sender.deliver(id, payload(id));
        return Map.of("event_id", id);
    }

    public Map<String, Object> reissue(String id) {
        JsonNode original = json.readTree(payload(id));
        JsonNode charge = original.path("data").path("object");
        ObjectNode event = event(charge, original.path("type").asText());
        store(event);
        sender.deliver(event.path("id").asText(), json.writeValueAsString(event));
        return Map.of(
                charge.path("object").asText(),
                charge,
                "event_id",
                event.path("id").asText());
    }

    public Map<String, Object> inspect(String id) {
        return Map.of(
                "payload",
                payload(id),
                "deliveries",
                jdbc.sql("""
                select id, event_id, target_url, signed_at, http_status, error, recorded_at
                from sim_processor.webhook_delivery where event_id = ? order by id
                """).param(id).query().listOfRows());
    }

    private String payload(String id) {
        return jdbc.sql("select payload from sim_processor.event where id = ?")
                .param(id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    ObjectNode event(JsonNode charge, String type) {
        ObjectNode event = json.createObjectNode();
        event.put("id", ids.next("evt"))
                .put("object", "event")
                .put("api_version", API_VERSION)
                .put("created", simulation.instant().getEpochSecond())
                .put("livemode", charge.path("livemode").asBoolean())
                .put("type", type);
        event.putObject("data").set("object", charge);
        return event;
    }

    void store(ObjectNode event) {
        JsonNode object = event.path("data").path("object");
        boolean payout = object.path("object").asText().equals("payout");
        jdbc.sql("""
                insert into sim_processor.event (id, charge_id, type, api_version, created, payload, recorded_at, payout_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .param(event.path("id").asText())
                .param(payout ? null : object.path("id").asText())
                .param(event.path("type").asText())
                .param(API_VERSION)
                .param(event.path("created").asLong())
                .param(json.writeValueAsString(event))
                .param(Timestamp.from(clock.instant()))
                .param(payout ? object.path("id").asText() : null)
                .update();
    }

    Map<String, Object> deliver(ObjectNode event) {
        sender.deliver(event.path("id").asText(), json.writeValueAsString(event));
        return Map.of(
                "payout",
                event.path("data").path("object"),
                "event_id",
                event.path("id").asText());
    }

    static long integer(JsonNode request, String field, @org.jspecify.annotations.Nullable Long fallback) {
        JsonNode value = request.path(field);
        if (value.isMissingNode() && fallback != null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw badRequest(field);
        return value.asLong();
    }

    static String text(JsonNode request, String field, String fallback) {
        JsonNode value = request.path(field);
        if (value.isMissingNode()) return fallback;
        if (!value.isString()) throw badRequest(field);
        return value.asText();
    }

    static boolean bool(JsonNode request, String field, boolean fallback) {
        JsonNode value = request.path(field);
        if (value.isMissingNode()) return fallback;
        if (!value.isBoolean()) throw badRequest(field);
        return value.asBoolean();
    }

    static ResponseStatusException badRequest(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
    }
}
