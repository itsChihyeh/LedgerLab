package dev.ledgerlab.sim.processor;

import dev.ledgerlab.sim.bank.IncomingTransfers;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
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
public final class Payouts {
    private static final String SELECT_PAYOUT = """
            select p.*,
                (select id from sim_processor.balance_transaction where source=p.id and type='payout'
                    order by created, id limit 1) as balance_transaction,
                (select id from sim_processor.balance_transaction where source=p.id and type='payout_failure'
                    order by created, id limit 1) as failure_balance_transaction
            from sim_processor.payout p
            """;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final BalanceTransactions balances;
    private final Processor processor;
    private final SimulationClock simulation;
    private final Clock clock;
    private final SeededIds ids;
    private final IncomingTransfers bank;
    private final JsonMapper json = JsonMapper.builder().build();

    public Payouts(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            BalanceTransactions balances,
            Processor processor,
            SimulationClock simulation,
            Clock clock,
            SeededIds ids,
            IncomingTransfers bank) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.balances = balances;
        this.processor = processor;
        this.simulation = simulation;
        this.clock = clock;
        this.ids = ids;
        this.bank = bank;
    }

    public Map<String, Object> create(JsonNode request) {
        if (!request.isObject()) throw Processor.badRequest("payout");
        boolean automatic = Processor.bool(request, "automatic", true);
        String method = Processor.text(request, "method", "standard");
        if (!List.of("standard", "instant").contains(method)) throw Processor.badRequest("method");
        ObjectNode event = Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            var available = balances.available();
            long net = 0;
            for (var line : available) net = Math.addExact(net, line.path("net").asLong());
            if (net <= 0) throw Processor.badRequest("available balance");
            long amount = BalanceTransactions.money(Processor.integer(request, "amount", net));
            Instant now = simulation.instant();
            String id = ids.next("po");
            jdbc.sql("""
                    insert into sim_processor.payout
                    (id,amount,currency,automatic,method,status,reconciliation_status,arrival_date,created,recorded_at)
                    values (?,?,'cad',?,?,'pending',?,?,?,?)
                    """)
                    .param(id)
                    .param(amount)
                    .param(automatic)
                    .param(method)
                    .param(automatic && method.equals("standard") ? "in_progress" : "not_applicable")
                    .param(Timestamp.from(now.plus(Duration.ofDays(1))))
                    .param(Timestamp.from(now))
                    .param(Timestamp.from(clock.instant()))
                    .update();
            for (var line : available)
                jdbc.sql("update sim_processor.balance_transaction set payout = ? where id = ?")
                        .param(id)
                        .param(line.path("id").asText())
                        .update();
            balances.add("payout", id, Math.negateExact(amount), 0, "cad", id, now);
            if (request.has("amount") || request.has("automatic") || request.has("method")) balances.fault(id, request);
            return store(id, "payout.created");
        }));
        return processor.deliver(event);
    }

    public Map<String, Object> transition(String id, String action, JsonNode request) {
        if (!request.isObject()) throw Processor.badRequest("payout transition");
        if (!List.of("reconcile", "paid", "failed").contains(action)) throw Processor.badRequest("action");
        ObjectNode event = Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            ObjectNode before = get(id);
            String old = before.path("status").asText();
            String type;
            if (action.equals("reconcile")) {
                if (before.path("reconciliation_status").asText().equals("completed"))
                    return existing(id, "payout.reconciliation_completed");
                if (before.path("reconciliation_status").asText().equals("not_applicable")) throw conflict();
                jdbc.sql("update sim_processor.payout set reconciliation_status = 'completed' where id = ?")
                        .param(id)
                        .update();
                type = "payout.reconciliation_completed";
            } else {
                type = "payout." + action;
                if (old.equals(action)) return existing(id, type);
                // A bank may report failure after Stripe first reported the payout paid.
                if (!(old.equals("pending")
                        || old.equals("in_transit")
                        || (old.equals("paid") && action.equals("failed")))) throw conflict();
                String code =
                        action.equals("failed") ? Processor.text(request, "failure_code", "account_closed") : null;
                jdbc.sql("update sim_processor.payout set status = ?, failure_code = ? where id = ?")
                        .param(action)
                        .param(code)
                        .param(id)
                        .update();
                if (action.equals("failed"))
                    balances.add(
                            "payout_failure", id, before.path("amount").asLong(), 0, "cad", null, simulation.instant());
                if (action.equals("paid")
                        && before.path("automatic").asBoolean()
                        && before.path("method").asText().equals("standard")
                        && before.path("currency").asText().equals("cad")
                        && before.path("amount").asLong() > 0)
                    bank.receive(id, before.path("amount").asLong());
            }
            return store(id, type);
        }));
        // Existing transitions return their original event without generating another delivery.
        if (event.path("already_applied").asBoolean())
            return Map.of("payout", get(id), "event_id", event.path("id").asText());
        return processor.deliver(event);
    }

    private ObjectNode existing(String id, String type) {
        String eventId = jdbc.sql(
                        "select id from sim_processor.event where payout_id = ? and type = ? order by recorded_at, id limit 1")
                .param(id)
                .param(type)
                .query(String.class)
                .single();
        return json.createObjectNode().put("id", eventId).put("already_applied", true);
    }

    private ObjectNode store(String id, String type) {
        ObjectNode event = processor.event(get(id), type);
        processor.store(event);
        return event;
    }

    public ObjectNode get(String id) {
        return jdbc.sql(SELECT_PAYOUT + " where p.id = ?")
                .param(id)
                .query(this::object)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private ObjectNode object(ResultSet row, int number) throws SQLException {
        ObjectNode result = json.createObjectNode();
        for (String field : List.of("id", "currency", "method", "status", "reconciliation_status", "failure_code"))
            result.put(field, row.getString(field));
        result.put("object", "payout")
                .put("amount", row.getLong("amount"))
                .put("automatic", row.getBoolean("automatic"))
                .put("livemode", false);
        for (String field : List.of("created", "arrival_date"))
            result.put(field, row.getTimestamp(field).toInstant().getEpochSecond());
        result.put("balance_transaction", row.getString("balance_transaction"));
        result.put("failure_balance_transaction", row.getString("failure_balance_transaction"));
        result.put("source_type", "card").put("type", "bank_account");
        for (String field : List.of(
                "description",
                "destination",
                "failure_message",
                "original_payout",
                "reversed_by",
                "statement_descriptor")) result.putNull(field);
        result.putObject("metadata");
        return result;
    }

    public ObjectNode list(Map<String, String> parameters) {
        long from = parameter(parameters, "created[gte]", 0);
        long until = parameter(parameters, "created[lt]", 253402300799L);
        if (from > until) throw Processor.badRequest("created range");
        var rows = jdbc.sql(
                        SELECT_PAYOUT + " where p.created >= ? and p.created < ? order by p.created desc, p.id desc")
                .param(timestamp(from))
                .param(timestamp(until))
                .query(this::object)
                .list();
        return page(rows, parameters, "/v1/payouts", 0, null);
    }

    public ObjectNode lines(String id, Map<String, String> parameters) {
        get(id);
        var plan = jdbc.sql("select fail_page, duplicate_transaction from sim_processor.payout where id = ?")
                .param(id)
                .query((row, number) -> new Plan(row.getInt(1), row.getString(2)))
                .single();
        return page(balances.forPayout(id), parameters, "/v1/balance_transactions", plan.failPage(), plan.duplicate());
    }

    private ObjectNode page(
            List<ObjectNode> rows,
            Map<String, String> parameters,
            String url,
            int failPage,
            @Nullable String duplicate) {
        long requested = parameter(parameters, "limit", 10);
        if (requested < 1 || requested > 100) throw Processor.badRequest("limit");
        int limit = (int) requested;
        int offset = 0;
        String cursor = parameters.get("starting_after");
        if (cursor != null) {
            int found = -1;
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i).path("id").asText().equals(cursor)) found = i;
            if (found < 0) throw Processor.badRequest("starting_after");
            offset = found + 1;
        }
        if (failPage > 0 && offset / limit + 1 == failPage)
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Injected page failure");
        int end = Math.min(rows.size(), offset + limit);
        ObjectNode result =
                json.createObjectNode().put("object", "list").put("url", url).put("has_more", end < rows.size());
        var data = result.putArray("data");
        for (int i = offset; i < end; i++) {
            ObjectNode row = rows.get(i);
            data.add(row);
            // Deliberately malformed page: duplicate stays beside its source so cursors cannot loop.
            if (row.path("id").asText().equals(duplicate)) data.add(row.deepCopy());
        }
        return result;
    }

    public ObjectNode faults(String id, JsonNode request) {
        return Objects.requireNonNull(transaction.execute(status -> {
            balances.lock();
            get(id);
            if (!request.isObject() || request.isEmpty()) throw Processor.badRequest("fault plan");
            var allowed = List.of("fail_page", "duplicate_transaction", "extra_line");
            if (!allowed.containsAll(request.propertyNames())) throw Processor.badRequest("fault plan field");
            if (request.has("fail_page")) {
                long page = Processor.integer(request, "fail_page", null);
                if (page < 0 || page > Integer.MAX_VALUE) throw Processor.badRequest("fail_page");
                jdbc.sql("update sim_processor.payout set fail_page = ? where id = ?")
                        .param((int) page)
                        .param(id)
                        .update();
            }
            if (request.has("duplicate_transaction")) {
                String duplicate = request.path("duplicate_transaction").isNull()
                        ? null
                        : Processor.text(request, "duplicate_transaction", "");
                if (duplicate != null
                        && balances.forPayout(id).stream()
                                .noneMatch(row -> row.path("id").asText().equals(duplicate)))
                    throw Processor.badRequest("duplicate_transaction");
                jdbc.sql("update sim_processor.payout set duplicate_transaction = ? where id = ?")
                        .param(duplicate)
                        .param(id)
                        .update();
            }
            if (request.has("extra_line")) {
                JsonNode extra = request.path("extra_line");
                String type = Processor.text(extra, "type", "charge");
                if (!List.of("charge", "adjustment").contains(type)) throw Processor.badRequest("extra_line.type");
                long amount = Processor.integer(extra, "amount", null);
                long fee = Processor.integer(extra, "fee", 0L);
                String source = Processor.text(extra, "source", ids.next(type.equals("charge") ? "ch" : "adj"));
                balances.add(type, source, amount, fee, "cad", id, simulation.instant());
            }
            balances.fault(id, request);
            return get(id);
        }));
    }

    private static long parameter(Map<String, String> parameters, String key, long fallback) {
        String value = parameters.get(key);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw Processor.badRequest(key);
        }
    }

    private static Timestamp timestamp(long value) {
        if (value < 0 || value > 253402300799L) throw Processor.badRequest("created range");
        return Timestamp.from(Instant.ofEpochSecond(value));
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Invalid payout transition");
    }

    private record Plan(int failPage, @Nullable String duplicate) {}
}
