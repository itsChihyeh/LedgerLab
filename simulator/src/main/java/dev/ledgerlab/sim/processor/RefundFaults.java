package dev.ledgerlab.sim.processor;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class RefundFaults {
    private static final List<String> COUNTERS =
            List.of("timeout_before_create", "timeout_after_create", "list_page_timeout");
    private static final List<String> FLAGS = List.of("force_pending", "suppress_events", "event_before_response");
    private final JdbcClient jdbc;
    private final Clock clock;
    private final BalanceTransactions balances;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public RefundFaults(
            JdbcClient jdbc, Clock clock, BalanceTransactions balances, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.balances = balances;
        this.transaction = new TransactionTemplate(manager);
    }

    public JsonNode configure(JsonNode request) {
        if (!request.isObject()) throw RefundError.invalid("parameter_invalid", "Fault plan must be an object");
        String target = Processor.text(request, "charge", "*");
        if (!(target.equals("*") || target.startsWith("ch_") && target.length() > 3))
            throw RefundError.invalid("parameter_invalid", "Invalid fault charge");
        ObjectNode plan = json.createObjectNode();
        for (var entry : request.properties()) {
            String name = entry.getKey();
            if (name.equals("charge")) continue;
            if (COUNTERS.contains(name)) {
                long value = Processor.integer(request, name, null);
                if (value < 0 || value > Integer.MAX_VALUE) throw RefundError.invalid("parameter_invalid", name);
                plan.put(name, value);
            } else if (FLAGS.contains(name)) {
                plan.put(name, Processor.bool(request, name, false));
            } else if (name.equals("fail_next")) {
                String reason = Processor.text(request, name, "");
                if (reason.isBlank()) throw RefundError.invalid("parameter_invalid", name);
                plan.put(name, reason);
            } else throw RefundError.invalid("parameter_unknown", name);
        }
        transaction.executeWithoutResult(status -> {
            balances.lock();
            save(target, plan);
            balances.fault("refund:" + target, request);
        });
        return plan;
    }

    ObjectNode effective(String charge) {
        ObjectNode result = read("*");
        result.setAll(read(charge));
        return result;
    }

    // Caller holds the common funds lock, including while consuming a global counter.
    boolean consume(String charge, String name) {
        String target = read(charge).has(name) ? charge : "*";
        ObjectNode plan = read(target);
        if (plan.path(name).asLong() <= 0) return false;
        plan.put(name, Math.subtractExact(plan.path(name).asLong(), 1));
        save(target, plan);
        return true;
    }

    void consumeFailure(String charge) {
        String target = read(charge).has("fail_next") ? charge : "*";
        ObjectNode plan = read(target);
        plan.remove("fail_next");
        save(target, plan);
    }

    private ObjectNode read(String target) {
        return jdbc.sql("select plan::text from sim_processor.refund_faults where target=?")
                .param(target)
                .query(String.class)
                .optional()
                .map(value -> (ObjectNode) json.readTree(value))
                .orElseGet(json::createObjectNode);
    }

    private void save(String target, ObjectNode plan) {
        jdbc.sql("""
                insert into sim_processor.refund_faults(target,plan,recorded_at) values (?,?::jsonb,?)
                on conflict (target) do update set plan=excluded.plan, recorded_at=excluded.recorded_at
                """)
                .param(target)
                .param(json.writeValueAsString(plan))
                .param(Timestamp.from(clock.instant()))
                .update();
    }
}
