package dev.ledgerlab.sim.processor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class Refunds {
    private static final String SELECT = """
            select r.*, c.currency, c.payment_intent from sim_processor.refunds r
            join sim_processor.charge c on c.id=r.charge
            """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final BalanceTransactions balances;
    private final Processor processor;
    private final EventDelivery sender;
    private final SimulationClock simulation;
    private final Clock clock;
    private final SeededIds ids;
    private final RefundFaults faults;
    private final JsonMapper json = JsonMapper.builder().build();

    public Refunds(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            BalanceTransactions balances,
            Processor processor,
            EventDelivery sender,
            SimulationClock simulation,
            Clock clock,
            SeededIds ids,
            RefundFaults faults) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.balances = balances;
        this.processor = processor;
        this.sender = sender;
        this.simulation = simulation;
        this.clock = clock;
        this.ids = ids;
        this.faults = faults;
        simulation.onAdvance(this::process);
        processor.onCharge(this::process);
    }

    public Submission create(Map<String, String> parameters, String key) {
        if (key.isBlank() || key.length() > 255)
            throw RefundError.invalid("parameter_invalid", "Idempotency-Key required (1-255 characters)");
        for (String name : parameters.keySet())
            if (!List.of("charge", "amount", "metadata[ledgerlab_refund_id]").contains(name))
                throw RefundError.invalid("parameter_unknown", name);
        String charge = parameters.getOrDefault("charge", "");
        if (charge.isBlank()) throw RefundError.invalid("parameter_missing", "charge required");
        long amount = positiveAmount(parameters.getOrDefault("amount", ""));
        var canonical = new TreeMap<>(parameters);
        canonical.put("amount", Long.toString(amount));
        String fingerprint = fingerprint(json.writeValueAsString(canonical));
        ObjectNode metadata = json.createObjectNode();
        if (parameters.containsKey("metadata[ledgerlab_refund_id]"))
            metadata.put("ledgerlab_refund_id", parameters.get("metadata[ledgerlab_refund_id]"));

        Created created = Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            if (faults.consume(charge, "timeout_before_create"))
                return new Created(json.createObjectNode(), List.of(), true, false);
            var previous = jdbc.sql(
                            "select request_fingerprint,response from sim_processor.idempotency_keys where key=? and not purged")
                    .param(key)
                    .query((row, n) -> new Cached(row.getString(1), row.getString(2)))
                    .optional();
            if (previous.isPresent()) {
                if (!previous.get().fingerprint().equals(fingerprint))
                    throw new RefundError(
                            HttpStatus.BAD_REQUEST,
                            "idempotency_error",
                            "idempotency_key_in_use",
                            "Same key used with different parameters");
                return new Created(
                        (ObjectNode) json.readTree(previous.get().response()),
                        List.of(),
                        false,
                        faults.consume(charge, "timeout_after_create"));
            }
            JsonNode original = charge(charge);
            if (!original.path("captured").asBoolean()
                    || !original.path("status").asText().equals("succeeded")
                    || original.path("livemode").asBoolean()
                    || !original.path("currency").asText().equals("cad"))
                throw RefundError.invalid("charge_invalid", "Only captured test CAD charges are supported");
            long reserved = 0;
            for (long value : jdbc.sql(
                            "select amount from sim_processor.refunds where charge=? and status in ('pending','succeeded')")
                    .param(charge)
                    .query(Long.class)
                    .list()) reserved = Math.addExact(reserved, value);
            long remaining = Math.subtractExact(original.path("amount_captured").asLong(), reserved);
            if (remaining <= 0) throw RefundError.invalid("charge_already_refunded", "Charge already fully refunded");
            if (amount > remaining)
                throw RefundError.invalid("amount_too_large", "Amount exceeds remaining charge amount");
            String id = ids.next("re");
            jdbc.sql("""
                    insert into sim_processor.refunds(id,charge,amount,status,metadata,created,recorded_at)
                    values (?,?,?,'pending',?::jsonb,?,?)
                    """)
                    .param(id)
                    .param(charge)
                    .param(amount)
                    .param(json.writeValueAsString(metadata))
                    .param(Timestamp.from(simulation.instant()))
                    .param(Timestamp.from(clock.instant()))
                    .update();
            ObjectNode plan = faults.effective(charge);
            if (!plan.has("fail_next") && !plan.path("force_pending").asBoolean() && available() >= amount)
                execute(id, amount);
            var events = new ArrayList<ObjectNode>();
            event(get(id), "refund.created", plan, events);
            if (plan.has("fail_next")) {
                fail(id, plan.path("fail_next").asText());
                faults.consumeFailure(charge);
                event(get(id), "refund.updated", plan, events);
                event(get(id), "refund.failed", plan, events);
            }
            ObjectNode response = get(id);
            jdbc.sql("""
                    insert into sim_processor.idempotency_keys(key,request_fingerprint,response,created,purged,recorded_at)
                    values (?,?,?,?,false,?) on conflict (key) do update set request_fingerprint=excluded.request_fingerprint,
                    response=excluded.response,created=excluded.created,purged=false,recorded_at=excluded.recorded_at
                    """)
                    .param(key)
                    .param(fingerprint)
                    .param(json.writeValueAsString(response))
                    .param(Timestamp.from(simulation.instant()))
                    .param(Timestamp.from(clock.instant()))
                    .update();
            return new Created(response, events, false, faults.consume(charge, "timeout_after_create"));
        }));
        if (created.before()) throw RefundError.timeout();
        // Like existing simulator charge/payout delivery, HTTP is outside the funds transaction.
        // Thus event_before_response is guaranteed, including waiting for the relay's HTTP completion.
        deliver(created.events());
        return new Submission(created.refund(), created.after());
    }

    public ObjectNode get(String id) {
        return jdbc.sql(SELECT + " where r.id=?")
                .param(id)
                .query(this::object)
                .optional()
                .orElseThrow(() -> new RefundError(
                        HttpStatus.NOT_FOUND, "invalid_request_error", "resource_missing", "No such refund"));
    }

    public ObjectNode list(Map<String, String> parameters) {
        String charge = parameters.getOrDefault("charge", "");
        if (!charge.isEmpty()) charge(charge);
        int limit;
        try {
            limit = Integer.parseInt(parameters.getOrDefault("limit", "10"));
        } catch (NumberFormatException e) {
            throw RefundError.invalid("parameter_invalid_integer", "limit");
        }
        if (limit < 1 || limit > 100) throw RefundError.invalid("parameter_invalid_integer", "limit must be 1-100");
        List<ObjectNode> rows = jdbc.sql(
                        SELECT + " where (?='' or r.charge=?) order by r.created desc, r.creation_order desc")
                .param(charge)
                .param(charge)
                .query(this::object)
                .list();
        int offset = 0;
        String cursor = parameters.getOrDefault("starting_after", "");
        if (parameters.containsKey("starting_after")) {
            int found = -1;
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).path("id").asText().equals(cursor)) found = i;
            if (found < 0) throw RefundError.invalid("resource_missing", "Invalid starting_after cursor");
            offset = Math.addExact(found, 1);
        }
        if (faults.effective(charge).path("list_page_timeout").asLong() == Math.addExact(offset / limit, 1))
            throw RefundError.timeout();
        int end = Math.min(rows.size(), Math.addExact(offset, limit));
        ObjectNode response = json.createObjectNode()
                .put("object", "list")
                .put("url", "/v1/refunds")
                .put("has_more", end < rows.size());
        var data = response.putArray("data");
        rows.subList(offset, end).forEach(data::add);
        return response;
    }

    public int process() {
        List<ObjectNode> events = Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            var pending = jdbc.sql(SELECT + " where r.status='pending' order by r.created,r.creation_order")
                    .query(this::object)
                    .list();
            var produced = new ArrayList<ObjectNode>();
            for (ObjectNode refund : pending) {
                ObjectNode plan = faults.effective(refund.path("charge").asText());
                long amount = refund.path("amount").asLong();
                if (!plan.path("force_pending").asBoolean() && available() >= amount) {
                    String id = refund.path("id").asText();
                    execute(id, amount);
                    event(get(id), "refund.updated", plan, produced);
                }
            }
            return produced;
        }));
        deliver(events);
        return events.size();
    }

    public ObjectNode transition(String id, String action, JsonNode request) {
        List<ObjectNode> events = Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            ObjectNode refund = get(id);
            String target = action.equals("succeed") ? "succeeded" : "failed";
            if (refund.path("status").asText().equals(target)) return List.<ObjectNode>of();
            if (!refund.path("status").asText().equals("pending")) throw conflict("Refund is already terminal");
            if (action.equals("succeed")) {
                if (available() < refund.path("amount").asLong()) throw conflict("Insufficient available balance");
                execute(id, refund.path("amount").asLong());
            } else if (action.equals("fail")) {
                String reason = Processor.text(request, "reason", "declined");
                if (reason.isBlank()) throw RefundError.invalid("parameter_invalid", "reason");
                fail(id, reason);
            } else throw RefundError.invalid("parameter_invalid", "action");
            ObjectNode plan = faults.effective(refund.path("charge").asText());
            var produced = new ArrayList<ObjectNode>();
            event(get(id), "refund.updated", plan, produced);
            if (action.equals("fail")) event(get(id), "refund.failed", plan, produced);
            return produced;
        }));
        deliver(events);
        return get(id);
    }

    public int purge(JsonNode request) {
        Instant before =
                request.has("before") ? Instant.parse(Processor.text(request, "before", "")) : simulation.instant();
        if (before.isAfter(simulation.instant()))
            throw RefundError.invalid("parameter_invalid", "before is in the future");
        return Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            return jdbc.sql(
                            "update sim_processor.idempotency_keys set purged=true where not purged and created < ? and created <= ?")
                    .param(Timestamp.from(before))
                    .param(Timestamp.from(simulation.instant().minus(Duration.ofHours(24))))
                    .update();
        }));
    }

    private JsonNode charge(String id) {
        return jdbc.sql("select row_to_json(c)::text from sim_processor.charge c where id=?")
                .param(id)
                .query(String.class)
                .optional()
                .map(json::readTree)
                .orElseThrow(() -> RefundError.invalid("resource_missing", "No such charge"));
    }

    private long available() {
        long total = 0;
        for (ObjectNode row : balances.available())
            total = Math.addExact(total, row.path("net").asLong());
        return total;
    }

    private void execute(String id, long amount) {
        String line = balances.add("refund", id, Math.negateExact(amount), 0, "cad", null, simulation.instant());
        jdbc.sql("update sim_processor.refunds set status='succeeded',balance_transaction=? where id=?")
                .param(line)
                .param(id)
                .update();
    }

    private void fail(String id, String reason) {
        jdbc.sql("update sim_processor.refunds set status='failed',failure_reason=? where id=?")
                .param(reason)
                .param(id)
                .update();
    }

    private void event(ObjectNode refund, String type, ObjectNode plan, List<ObjectNode> events) {
        if (plan.path("suppress_events").asBoolean()) return;
        ObjectNode event = processor.event(refund, type);
        processor.store(event);
        events.add(event);
    }

    private void deliver(List<ObjectNode> events) {
        for (ObjectNode event : events) sender.deliver(event.path("id").asText(), json.writeValueAsString(event));
    }

    private ObjectNode object(ResultSet row, int number) throws SQLException {
        ObjectNode result = json.createObjectNode().put("object", "refund");
        for (String field : List.of(
                "id", "charge", "status", "currency", "payment_intent", "failure_reason", "balance_transaction"))
            result.put(field, row.getString(field));
        result.put("amount", row.getLong("amount"))
                .put("created", row.getTimestamp("created").toInstant().getEpochSecond());
        result.set("metadata", json.readTree(row.getString("metadata")));
        return result;
    }

    private static long positiveAmount(String value) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            long amount = Long.parseLong(value);
            if (amount <= 0 || amount >= 9007199254740992L) throw new NumberFormatException();
            return amount;
        } catch (NumberFormatException e) {
            throw RefundError.invalid("parameter_invalid_integer", "amount must be positive integer cents");
        }
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RefundError conflict(String message) {
        return new RefundError(HttpStatus.CONFLICT, "invalid_request_error", "refund_state_conflict", message);
    }

    public record Submission(ObjectNode refund, boolean hang) {}

    private record Cached(String fingerprint, String response) {}

    private record Created(ObjectNode refund, List<ObjectNode> events, boolean before, boolean after) {}
}
