package dev.ledgerlab.sim.bank;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bank-owned payment state; every operation runs in Bank's transaction under its account lock. */
public final class OutgoingPayments {
    static final List<String> FAULTS = List.of(
            "TIMEOUT",
            "EXECUTE_DROP_RESPONSE",
            "UNAVAILABLE",
            "REJECT",
            "REJECT_ON_QUERY",
            "EXECUTE_AFTER_REJECT",
            "RETURN_AFTER_DAYS");
    private static final String ACCOUNT = "SIM-CAD-001";
    private final JdbcClient jdbc;
    private final Clock clock;
    private final BankCalendar calendar = new BankCalendar();
    private final JsonMapper json = JsonMapper.builder().build();

    OutgoingPayments(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Result submit(JsonNode request) {
        if (!request.isObject()
                || !List.of("requestId", "amount", "currency", "destination").containsAll(request.propertyNames())
                || !request.path("requestId").isString()
                || !request.path("requestId").asText().matches("mpo_.+")
                || !request.path("amount").isIntegralNumber()
                || !request.path("amount").canConvertToLong()
                || request.path("amount").asLong() < 1
                || request.path("amount").asLong() > 99999999
                || !request.path("currency").isString()
                || !request.path("destination").isString()
                || request.path("destination").asText().isBlank()) return error(400, "INVALID_REQUEST");
        String id = request.path("requestId").asText();
        JsonNode plan = plan(id);
        truth(plan, id, "PAYMENT_REQUEST", "NONE");
        if (unavailable(plan, id)) return error(503, "UNAVAILABLE");
        var existing = find(id);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (payment.amount() != request.path("amount").asLong()
                    || !payment.currency().equals(request.path("currency").asText())
                    || !payment.destination().equals(request.path("destination").asText()))
                return error(409, "REQUEST_CONFLICT");
            return new Result(200, body(payment), "NONE");
        }
        if (!request.path("currency").asText().equals("CAD")) return error(400, "INVALID_REQUEST");
        if (!FAULTS.contains(plan.path("fault").asText())
                || plan.path("fault").asText().equals("UNAVAILABLE")) {
            plan = plan.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) plan).put("fault", "NONE").put("parameter", 0);
        }
        String fault = plan.path("fault").asText();
        if (fault.equals("TIMEOUT")) {
            truth(plan, id, fault, "PAYOUT_UNRESOLVED");
            return new Result(504, error(504, "TIMEOUT").body(), "TIMEOUT");
        }
        LocalDate execution = calendar.addBusinessDays(
                clock.instant().atZone(ZoneId.of("America/Toronto")).toLocalDate(), 1);
        LocalDate returned = fault.equals("RETURN_AFTER_DAYS")
                ? calendar.addBusinessDays(execution, plan.path("parameter").asLong())
                : null;
        boolean rejected = List.of("REJECT", "EXECUTE_AFTER_REJECT").contains(fault);
        jdbc.sql("""
                insert into sim_bank.outgoing_payment
                    (request_id,payment_id,account_ref,amount,currency,destination,status,reason,
                     execution_date,return_due_date,plan,recorded_at)
                values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """)
                .param(id)
                .param("bpm_" + id.substring(4))
                .param(ACCOUNT)
                .param(request.path("amount").asLong())
                .param("CAD")
                .param(request.path("destination").asText())
                .param(rejected ? "REJECTED" : "ACCEPTED")
                .param(
                        fault.equals("EXECUTE_AFTER_REJECT")
                                ? "BANK_REJECTED"
                                : rejected ? plan.path("memo").asText() : null)
                .param(execution)
                .param(returned)
                .param(plan.toString())
                .param(now())
                .update();
        if (!fault.equals("NONE")) truth(plan, id, fault, category(fault));
        return new Result(201, body(find(id).orElseThrow()), fault.equals("EXECUTE_DROP_RESPONSE") ? fault : "NONE");
    }

    Result get(String id) {
        if (unavailable(plan(id), id)) return error(503, "UNAVAILABLE");
        var existing = find(id);
        if (existing.isEmpty()) return error(404, "NOT_FOUND");
        Payment payment = existing.get();
        if (payment.status().equals("ACCEPTED")
                && payment.plan().path("fault").asText().equals("REJECT_ON_QUERY")) {
            change(id, "REJECTED", payment.plan().path("memo").asText());
            truth(payment.plan(), id, "REJECT_ON_QUERY", "NONE");
        }
        return new Result(200, body(find(id).orElseThrow()), "NONE");
    }

    Result list() {
        if (unavailable(plan("*"), "*")) return error(503, "UNAVAILABLE");
        var result = json.createArrayNode();
        for (String id : jdbc.sql("select request_id from sim_bank.outgoing_payment order by recorded_at,request_id")
                .query(String.class)
                .list())
            result.add(body(find(java.util.Objects.requireNonNull(id)).orElseThrow()));
        return new Result(200, result, "NONE");
    }

    private JsonNode plan(String id) {
        return jdbc.sql("""
                select * from sim_bank.fault_plan where target_ref in (?, '*')
                order by (target_ref=?) desc limit 1
                """)
                .param(id)
                .param(id)
                .query((row, index) -> json.createObjectNode()
                        .put("target_ref", row.getString("target_ref"))
                        .put("scenario", row.getString("scenario"))
                        .put("fault", row.getString("fault"))
                        .put("parameter", row.getLong("parameter"))
                        .put("memo", row.getString("memo")))
                .optional()
                .orElseGet(() -> json.createObjectNode()
                        .put("scenario", "default")
                        .put("fault", "NONE")
                        .put("parameter", 0));
    }

    private boolean unavailable(JsonNode plan, String id) {
        if (!plan.path("fault").asText().equals("UNAVAILABLE")
                || plan.path("parameter").asLong() == 0) return false;
        jdbc.sql("update sim_bank.fault_plan set parameter=parameter-1 where target_ref=?")
                .param(plan.path("target_ref").asText())
                .update();
        truth(plan, id, "UNAVAILABLE", "PAYOUT_UNRESOLVED");
        return true;
    }

    private Optional<Payment> find(String id) {
        return jdbc.sql("select * from sim_bank.outgoing_payment where request_id=?")
                .param(id)
                .query((row, index) -> new Payment(
                        row.getString("request_id"),
                        row.getString("payment_id"),
                        row.getLong("amount"),
                        row.getString("currency"),
                        row.getString("destination"),
                        row.getString("status"),
                        Optional.ofNullable(row.getString("reason")),
                        row.getObject("execution_date", LocalDate.class),
                        json.readTree(row.getString("plan"))))
                .optional();
    }

    private JsonNode body(Payment payment) {
        return json.createObjectNode()
                .put("paymentId", payment.paymentId())
                .put("requestId", payment.id())
                .put("status", payment.status())
                .put("executionDate", payment.execution().toString())
                .put("reason", payment.reason().orElse(null));
    }

    /** Re-query after each event so a newly executed payment's return joins the chronological queue. */
    void executeDue(LocalDate today) {
        while (true) {
            var next = jdbc.sql("""
                    select request_id,execution_date as due,false as is_return from sim_bank.outgoing_payment p
                    where execution_date<=? and
                      ((status='ACCEPTED' and plan->>'fault'<>'REJECT_ON_QUERY') or
                       (status='REJECTED' and plan->>'fault'='EXECUTE_AFTER_REJECT' and reason<>'INSUFFICIENT_FUNDS'))
                      and not exists (select 1 from sim_bank.txn t where t.request_id=p.request_id and direction='DEBIT')
                    union all
                    select request_id,return_due_date,true from sim_bank.outgoing_payment
                    where status='EXECUTED' and return_due_date<=?
                    order by due,is_return desc,request_id limit 1
                    """)
                    .param(today)
                    .param(today)
                    .query((row, index) ->
                            new Due(row.getString(1), row.getObject(2, LocalDate.class), row.getBoolean(3)))
                    .optional();
            if (next.isEmpty()) return;
            Due due = next.get();
            Payment payment = find(due.id()).orElseThrow();
            if (!due.returning() && balanceAt(due.date()) < payment.amount()) {
                change(payment.id(), "REJECTED", "INSUFFICIENT_FUNDS");
                truth(payment.plan(), payment.id(), "INSUFFICIENT_FUNDS", "NONE");
                continue;
            }
            jdbc.sql("""
                    insert into sim_bank.txn (bank_txn_id,account_ref,request_id,direction,amount,currency,
                        status,reference,booking_date,value_date,visible_date,memo,recorded_at)
                    values (?,?,?,?,?,'CAD','BOOKED',?,?,?,?,?,?)
                    """)
                    .param("bt_" + payment.id() + (due.returning() ? "_R" : "_1"))
                    .param(ACCOUNT)
                    .param(payment.id())
                    .param(due.returning() ? "CREDIT" : "DEBIT")
                    .param(payment.amount())
                    .param(payment.id())
                    .param(due.date())
                    .param(due.date())
                    .param(due.date())
                    .param(due.returning() ? "RETURN ACCOUNT_CLOSED" : "Payout " + payment.id())
                    .param(now())
                    .update();
            change(payment.id(), due.returning() ? "RETURNED" : "EXECUTED", due.returning() ? "ACCOUNT_CLOSED" : null);
            truth(payment.plan(), payment.id(), due.returning() ? "PAYMENT_RETURNED" : "PAYMENT_EXECUTED", "NONE");
        }
    }

    private long balanceAt(LocalDate date) {
        long balance = 0;
        for (long amount :
                jdbc.sql("""
                select case when direction='CREDIT' then amount else -amount end from sim_bank.txn
                where account_ref=? and status='BOOKED' and booking_date<=? order by booking_date,bank_txn_id
                """).param(ACCOUNT).param(date).query(Long.class).list())
            balance = Math.addExact(balance, amount);
        return balance;
    }

    private void change(String id, String status, @org.jspecify.annotations.Nullable String reason) {
        jdbc.sql("update sim_bank.outgoing_payment set status=?,reason=? where request_id=?")
                .param(status)
                .param(reason)
                .param(id)
                .update();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private void truth(JsonNode plan, String id, String event, String category) {
        jdbc.sql("""
                insert into sim_truth.injected_fault (scenario,object_ref,fault,expected_category,recorded_at)
                values (?,?,?,?,?)
                """)
                .param(plan.path("scenario").asText())
                .param(id)
                .param(event)
                .param(category)
                .param(now())
                .update();
    }

    private static String category(String fault) {
        return switch (fault) {
            case "TIMEOUT", "UNAVAILABLE", "EXECUTE_DROP_RESPONSE" -> "PAYOUT_UNRESOLVED";
            case "EXECUTE_AFTER_REJECT" -> "PAYOUT_STATE_CONFLICT";
            default -> "NONE";
        };
    }

    private Result error(int status, String code) {
        return new Result(status, json.createObjectNode().put("code", code), "NONE");
    }

    public record Result(int status, JsonNode body, String transport) {}

    private record Due(String id, LocalDate date, boolean returning) {}

    private record Payment(
            String id,
            String paymentId,
            long amount,
            String currency,
            String destination,
            String status,
            Optional<String> reason,
            LocalDate execution,
            JsonNode plan) {}
}
