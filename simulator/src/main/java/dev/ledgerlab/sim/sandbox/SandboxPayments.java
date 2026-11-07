package dev.ledgerlab.sim.sandbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
@Profile("sandbox")
public final class SandboxPayments {
    private static final List<String> CARDS = List.of("pm_card_bypassPending", "pm_card_ca");
    private static final List<String> STATES = List.of(
            "succeeded",
            "processing",
            "requires_payment_method",
            "requires_confirmation",
            "requires_action",
            "requires_capture",
            "canceled");
    private final SandboxStripe stripe;
    private final JdbcClient jdbc;
    private final Clock clock;

    public SandboxPayments(SandboxStripe stripe, JdbcClient jdbc, Clock clock) {
        this.stripe = stripe;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public synchronized Map<String, Object> generate(JsonNode request) {
        if (!request.isObject()
                || !request.path("count").isIntegralNumber()
                || !request.path("count").canConvertToInt()
                || !request.path("seed").isIntegralNumber()
                || !request.path("seed").canConvertToLong()
                || !CARDS.contains(request.path("card").asText(""))) throw invalid();
        int count = request.path("count").asInt();
        if (count < 1 || count > 50) throw invalid();
        String card = request.path("card").asText();
        long seed = request.path("seed").asLong();
        var random = new Random(seed);
        var results = new java.util.ArrayList<Payment>();
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            long amount = Math.addExact(500L, random.nextInt(19501));
            var prior = jdbc.sql("""
                    select * from sim_sandbox.payment_attempt
                    where run_id=? and card=? and seed=? and ordinal=? order by id desc limit 1
                    """)
                    .param(stripe.runId())
                    .param(card)
                    .param(seed)
                    .param(ordinal)
                    .query(SandboxPayments::read)
                    .optional();
            if (prior.isPresent() && prior.get().successful()) {
                results.add(prior.get());
                continue;
            }
            var first = jdbc.sql("""
                    select recorded_at from sim_sandbox.payment_attempt
                    where run_id=? and card=? and seed=? and ordinal=? order by id limit 1
                    """)
                    .param(stripe.runId())
                    .param(card)
                    .param(seed)
                    .param(ordinal)
                    .query(Timestamp.class)
                    .optional();
            if (first.isPresent()
                    && !clock.instant().isBefore(first.get().toInstant().plus(Duration.ofHours(23)))) {
                var expired =
                        new Payment(card, seed, ordinal, amount, "", "", "failed", 0, "IDEMPOTENCY_WINDOW_EXPIRED");
                append(expired);
                results.add(expired);
                continue;
            }
            // Commit intent before HTTP so a crash cannot reset the safe retry window.
            append(new Payment(card, seed, ordinal, amount, "", "", "started", 0, ""));
            Payment result = parse(card, seed, ordinal, amount, stripe.pay(card, seed, ordinal, amount));
            append(result);
            results.add(result);
        }
        long succeeded = results.stream().filter(Payment::successful).count();
        return Map.of("count", count, "succeeded", succeeded, "failed", count - succeeded, "payments", results);
    }

    private Payment parse(String card, long seed, int ordinal, long amount, SandboxStripe.Reply reply) {
        JsonNode body = reply.body();
        String pi = SandboxStripe.objectId(body.path("id"), "pi_");
        JsonNode charge = body.path("latest_charge");
        String ch = SandboxStripe.objectId(charge.isObject() ? charge.path("id") : charge, "ch_");
        String status = body.path("status").asText("");
        String error = reply.error();
        if (error.isEmpty()) {
            if (!body.path("livemode").isBoolean() || body.path("livemode").asBoolean()) error = "LIVEMODE";
            else if (pi.isEmpty()
                    || !STATES.contains(status)
                    || !body.path("amount").isIntegralNumber()
                    || !body.path("amount").canConvertToLong()
                    || body.path("amount").asLong() != amount
                    || !body.path("currency").asText().equals("cad")) error = "INVALID_RESPONSE";
            else if (status.equals("succeeded")) {
                if (!charge.isObject() && !ch.isEmpty()) {
                    var fetched = stripe.get("/v1/charges/" + ch);
                    charge = fetched.body();
                    error = fetched.error();
                }
                if (error.isEmpty()
                        && (ch.isEmpty()
                                || !ch.equals(SandboxStripe.objectId(charge.path("id"), "ch_"))
                                || !charge.path("livemode").isBoolean()
                                || charge.path("livemode").asBoolean()
                                || !charge.path("payment_method_details")
                                        .path("type")
                                        .asText()
                                        .equals("card"))) error = "UNSUPPORTED_CHARGE";
            }
        }
        if (!STATES.contains(status)) status = "failed";
        return new Payment(card, seed, ordinal, amount, pi, ch, status, reply.status(), error);
    }

    private void append(Payment p) {
        jdbc.sql("""
                insert into sim_sandbox.payment_attempt
                  (run_id,card,seed,ordinal,amount,payment_intent,charge,status,http_status,error_code,recorded_at)
                values (?,?,?,?,?,?,?,?,nullif(?,0),?,?)
                """)
                .param(stripe.runId())
                .param(p.card())
                .param(p.seed())
                .param(p.ordinal())
                .param(p.amount())
                .param(p.paymentIntent())
                .param(p.charge())
                .param(p.status())
                .param(p.httpStatus())
                .param(p.errorCode())
                .param(Timestamp.from(clock.instant()))
                .update();
    }

    public List<Map<String, @org.jspecify.annotations.Nullable Object>> records() {
        return jdbc.sql("select * from sim_sandbox.payment_attempt where run_id=? order by ordinal,id")
                .param(stripe.runId())
                .query()
                .listOfRows();
    }

    public Map<String, Object> summary() {
        var latest =
                jdbc.sql("""
                select distinct on (card,seed,ordinal) * from sim_sandbox.payment_attempt
                where run_id=? order by card,seed,ordinal,id desc
                """).param(stripe.runId()).query(SandboxPayments::read).list();
        var cards = new java.util.LinkedHashMap<String, Object>();
        for (String card : CARDS) {
            long amount = 0, count = 0;
            for (Payment p : latest)
                if (p.card().equals(card)) {
                    amount = Math.addExact(amount, p.amount());
                    count = Math.incrementExact(count);
                }
            cards.put(card, Map.of("count", count, "amount", amount));
        }
        long succeeded = latest.stream().filter(Payment::successful).count();
        long pending = latest.stream().filter(p -> p.status().equals("started")).count();
        long payouts = jdbc.sql("""
                select count(distinct payout) from sim_sandbox.bank_sync,
                  lateral jsonb_array_elements_text(received_payouts) as payout where run_id=?
                """).param(stripe.runId()).query(Long.class).single();
        return Map.of(
                "run_id",
                stripe.runId(),
                "cards",
                cards,
                "succeeded",
                succeeded,
                "failed",
                latest.size() - succeeded - pending,
                "pending",
                pending,
                "payouts_received",
                payouts);
    }

    private static Payment read(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Payment(
                rs.getString("card"),
                rs.getLong("seed"),
                rs.getInt("ordinal"),
                rs.getLong("amount"),
                rs.getString("payment_intent"),
                rs.getString("charge"),
                rs.getString("status"),
                rs.getInt("http_status"),
                rs.getString("error_code"));
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sandbox payment request");
    }

    public record Payment(
            String card,
            long seed,
            int ordinal,
            long amount,
            String paymentIntent,
            String charge,
            String status,
            int httpStatus,
            String errorCode) {
        boolean successful() {
            return status.equals("succeeded") && errorCode.isEmpty();
        }
    }
}
