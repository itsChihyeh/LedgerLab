package dev.ledgerlab.sim.processor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class BalanceTransactions {
    private final JdbcClient jdbc;
    private final SimulationClock simulation;
    private final Clock clock;
    private final SeededIds ids;
    private final Duration availabilityDelay;
    private final JsonMapper json = JsonMapper.builder().build();

    public BalanceTransactions(
            JdbcClient jdbc,
            SimulationClock simulation,
            Clock clock,
            SeededIds ids,
            @Value("${sim.stripe.availability-delay:PT0S}") Duration availabilityDelay) {
        if (availabilityDelay.isNegative()) throw new IllegalArgumentException("Negative availability delay");
        this.jdbc = jdbc;
        this.simulation = simulation;
        this.clock = clock;
        this.ids = ids;
        this.availabilityDelay = availabilityDelay;
    }

    // Transaction-scoped database lock: serializes balance allocation even across simulator processes.
    void lock() {
        jdbc.sql("lock table sim_processor.balance_transaction in share row exclusive mode")
                .update();
    }

    void charge(ObjectNode charge, JsonNode request) {
        long amount = charge.path("amount").asLong();
        long fee = Processor.integer(request, "fee", contractFee(amount));
        String id = add(
                "charge",
                charge.path("id").asText(),
                amount,
                fee,
                charge.path("currency").asText(),
                null,
                Instant.ofEpochSecond(charge.path("created").asLong()).plus(availabilityDelay));
        charge.put("balance_transaction", id);
        if (request.has("fee")) fault(charge.path("id").asText(), request);
    }

    public static long contractFee(long amount) {
        return Math.addExact(30, Math.addExact(Math.multiplyExact(amount, 29), 500) / 1000);
    }

    static long money(long amount) {
        if (amount <= -9007199254740992L || amount >= 9007199254740992L) throw Processor.badRequest("amount range");
        return amount;
    }

    String add(
            String type,
            String source,
            long amount,
            long fee,
            String currency,
            @Nullable String payout,
            Instant availableOn) {
        long net = money(Math.subtractExact(amount, fee));
        String id = ids.next("txn");
        jdbc.sql("""
                insert into sim_processor.balance_transaction
                (id,type,source,amount,fee,net,currency,created,available_on,payout,reporting_category,recorded_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .param(id)
                .param(type)
                .param(source)
                .param(money(amount))
                .param(money(fee))
                .param(net)
                .param(currency)
                .param(Timestamp.from(simulation.instant()))
                .param(Timestamp.from(availableOn))
                .param(payout)
                .param(type.equals("charge") ? "charge" : type.equals("payout_failure") ? "payout_reversal" : type)
                .param(Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    void fault(String target, JsonNode request) {
        jdbc.sql("insert into sim_processor.fault_plan (id,target_id,request,recorded_at) values (?,?,?,?)")
                .param(ids.next("fault"))
                .param(target)
                .param(json.writeValueAsString(request))
                .param(Timestamp.from(clock.instant()))
                .update();
    }

    List<ObjectNode> available() {
        return jdbc.sql("""
                select * from sim_processor.balance_transaction
                where payout is null and type <> 'payout' and currency = 'cad' and available_on <= ?
                order by created desc, id desc
                """)
                .param(Timestamp.from(simulation.instant()))
                .query(this::object)
                .list();
    }

    List<ObjectNode> forPayout(String id) {
        return jdbc.sql(
                        "select * from sim_processor.balance_transaction where payout = ? order by created desc, id desc")
                .param(id)
                .query(this::object)
                .list();
    }

    private ObjectNode object(ResultSet row, int number) throws SQLException {
        ObjectNode result = json.createObjectNode();
        for (String field : List.of("id", "type", "source", "currency", "reporting_category"))
            result.put(field, row.getString(field));
        for (String field : List.of("amount", "fee", "net")) result.put(field, row.getLong(field));
        for (String field : List.of("created", "available_on"))
            result.put(field, row.getTimestamp(field).toInstant().getEpochSecond());
        result.put("object", "balance_transaction");
        result.put(
                "status",
                row.getTimestamp("available_on").toInstant().isAfter(simulation.instant()) ? "pending" : "available");
        result.putNull("description").putNull("exchange_rate");
        result.putArray("fee_details");
        return result;
    }
}
