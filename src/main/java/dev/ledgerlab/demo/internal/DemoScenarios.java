package dev.ledgerlab.demo.internal;

import dev.ledgerlab.shared.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The eight whitelisted scenarios and the seed (section 2.1). Each one drives only the simulator's public
 * control interface and the application's own public API, exactly as the smoke scripts do, and waits for the
 * system's asynchronous work by bounded polling of what the system reports, never by guessing a delay.
 * Missing prerequisites (a settled payment, bank cash) are created first and show up as steps.
 */
@Component
@Profile("demo")
final class DemoScenarios {
    private static final String BANK_ACCOUNT = "MERCHANT-001";

    private final JsonClient app;
    private final JsonClient sim;
    private final DemoStats stats;
    private final DemoCrashHooks crash;
    private final DemoProperties properties;
    private final Clock clock;
    private final IdGenerator ids;
    private final RandomGenerator random = new SecureRandom();
    private final Set<String> importedStatements = new HashSet<>();

    DemoScenarios(
            DemoClients clients,
            DemoStats stats,
            DemoCrashHooks crash,
            DemoProperties properties,
            Clock clock,
            IdGenerator ids) {
        this.app = clients.app();
        this.sim = clients.simulator();
        this.stats = stats;
        this.crash = crash;
        this.properties = properties;
        this.clock = clock;
        this.ids = ids;
    }

    // ------------------------------------------------------------------------------------------ the actions

    void pay(ActionRun run) {
        Charge charge = charge(run, randomAmount(), true);
        run.step("Payment " + charge.ref() + " is CAPTURED and booked once in the ledger");
    }

    void replayWebhook(ActionRun run) {
        String eventId = stats.latestChargeEvent().orElse(null);
        if (eventId == null) {
            run.step("No payment webhook to replay yet; creating a payment first");
            eventId = charge(run, randomAmount(), true).eventId();
        }
        int before = deliveries(eventId).size();
        run.step("Re-delivering " + eventId + " twice, byte for byte");
        for (int i = 0; i < 2; i++) {
            require(sim.post("/sim/events/" + eventId + "/redeliver", Map.of()), "redeliver " + eventId);
        }
        String event = eventId;
        JsonNode delivered = await("duplicate deliveries of " + eventId, () -> {
            JsonNode body = app.get("/api/events/" + event).body();
            return body.path("deliveries").size() >= before + 2 ? Optional.of(body) : Optional.empty();
        });
        long duplicates = count(delivered.path("deliveries"), "outcome", "DUPLICATE");
        run.step("Both repeats were recognised as duplicates (" + duplicates + " DUPLICATE deliveries in total)"
                + " and booked nothing new");
    }

    void batchFirst(ActionRun run) {
        run.step("Telling the webhook relay to delay the next payment event");
        require(
                sim.put(
                        "/sim/relay/plan",
                        Map.of(
                                "rules",
                                List.of(Map.of(
                                        "event_type", "charge.succeeded", "action", "DELAY", "delay_seconds", 60)))),
                "install relay plan");
        Charge charge;
        try {
            charge = charge(run, randomAmount(), false);
            run.step("Charge " + charge.ref() + " created; its webhook is on hold in the relay");
        } finally {
            clearRelayPlan();
        }
        String payout = require(sim.post("/sim/payouts", Map.of()), "create payout")
                .path("payout")
                .path("id")
                .asString();
        await("batch " + payout + " ANNOUNCED", () -> batch(payout, "ANNOUNCED"));
        run.step("Payout " + payout + " reached the ledger before its payment did");
        require(sim.post("/sim/payouts/" + payout + "/reconcile", Map.of()), "reconcile payout");
        JsonNode held = await("batch " + payout + " HELD", () -> batch(payout, "HELD"));
        run.step("Its line items name a payment the ledger has never heard of, so the batch is HELD ("
                + held.path("batch").path("holdReason").asString() + ") and nothing is settled");
        require(sim.post("/sim/clock/advance", Map.of("seconds", 60)), "release the delayed webhook");
        await("batch " + payout + " CONFIRMED", () -> batch(payout, "CONFIRMED"));
        run.step("The late payment webhook arrived and the held batch was re-checked on its own: CONFIRMED, its"
                + " payment SETTLED");
        paid(run, payout);
    }

    void doubleRefund(ActionRun run) {
        DemoStats.Refundable target = refundable(run, 1000);
        long each = Math.max(1, Math.multiplyExact(target.free(), 60L) / 100);
        run.step("Two refunds of " + money(each) + " each (60% of the " + money(target.free())
                + " still refundable) will be sent at the same instant");
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<JsonClient.Reply>> sent = List.of(
                    pool.submit(() -> {
                        gate.await();
                        return refund(run, target.processorRef(), each, "a");
                    }),
                    pool.submit(() -> {
                        gate.await();
                        return refund(run, target.processorRef(), each, "b");
                    }));
            gate.countDown();
            int accepted = 0;
            int rejected = 0;
            for (Future<JsonClient.Reply> future : sent) {
                JsonClient.Reply reply = result(future);
                if (reply.status() == 201) {
                    accepted++;
                    run.step("Refund " + reply.body().path("refund_id").asString() + " accepted ("
                            + reply.body().path("status").asString() + ")");
                } else if (reply.status() == 409
                        && reply.body().path("code").asString().equals("REFUND_EXCEEDS_REMAINING")) {
                    rejected++;
                    run.step("Refund refused: it would exceed the remaining refundable amount");
                } else {
                    throw new ActionFailed("Unexpected refund answer " + reply.status());
                }
            }
            if (accepted != 1 || rejected != 1) {
                throw new ActionFailed(
                        "Expected one accepted and one refused refund, got " + accepted + " and " + rejected);
            }
        }
    }

    void dropRefundResponse(ActionRun run) {
        DemoStats.Refundable target = refundable(run, 500);
        long amount = Math.max(100, target.free() / 3);
        require(
                sim.post("/sim/refunds/faults", Map.of("charge", target.processorRef(), "timeout_after_create", 1)),
                "set refund fault");
        run.step("Fake Stripe will create the next refund and then never answer");
        try {
            JsonClient.Reply accepted = refund(run, target.processorRef(), amount, "a");
            String id =
                    require(accepted, "accept refund", 201).path("refund_id").asString();
            run.step("Refund " + id + " accepted; the call to the processor is about to time out");
            JsonNode detail = awaitRefundCommand(id, 2);
            run.step("The lost answer left the command UNKNOWN; funds stayed reserved, the processor was asked"
                    + " what it has, and found the refund: "
                    + detail.path("refund").path("status").asString() + ", still one refund");
        } finally {
            sim.post("/sim/refunds/faults", Map.of("charge", target.processorRef()));
        }
    }

    void bankDropReceipt(ActionRun run) {
        long amount = 1000;
        ensureCash(run, amount);
        require(
                sim.put("/sim/bank/plan", Map.of("target_ref", "*", "fault", "EXECUTE_DROP_RESPONSE", "parameter", 0)),
                "set bank fault");
        run.step("The bank will execute the next transfer but lose its receipt");
        String payout;
        try {
            String key = "demo-" + ids.next("po").toLowerCase(java.util.Locale.ROOT);
            JsonClient.Reply reserved = app.post(
                    "/api/payouts", Map.of("amount", amount, "merchantPayoutRef", key), Map.of("Idempotency-Key", key));
            payout = require(reserved, "reserve withdrawal", 201)
                    .path("payoutId")
                    .asString();
            run.step("Withdrawal " + payout + " of " + money(amount) + " reserved and sent to the bank");
            String id = payout;
            await("withdrawal " + id + " back to ACCEPTED", () -> {
                JsonNode body = app.get("/api/payouts/" + id).body();
                boolean wasUnknown = false;
                for (JsonNode step : body.path("history")) {
                    wasUnknown |= step.path("toStatus").asString().equals("UNKNOWN");
                }
                return wasUnknown
                                && body.path("payout").path("status").asString().equals("ACCEPTED")
                        ? Optional.of(body)
                        : Optional.empty();
            });
            run.step("No receipt came back, so the withdrawal sat UNKNOWN with the money still reserved; asking the"
                    + " bank found one payment, not two");
        } finally {
            sim.put("/sim/bank/plan", Map.of("target_ref", "*", "fault", "NONE", "parameter", 0));
        }
        String id = payout;
        depositAndImport(run, Set.of(id));
        await("withdrawal " + id + " PAID", () -> {
            JsonNode body = app.get("/api/payouts/" + id).body();
            return body.path("payout").path("status").asString().equals("PAID") ? Optional.of(body) : Optional.empty();
        });
        run.step("The bank statement arrived: withdrawal " + id + " is PAID, debited exactly once");
    }

    void killWorker(ActionRun run) {
        DemoStats.Refundable target = refundable(run, 500);
        long amount = Math.max(100, target.free() / 4);
        crash.arm();
        run.step("Armed a one-off crash: the next command dies after the processor call, before its write-back");
        try {
            JsonClient.Reply accepted = refund(run, target.processorRef(), amount, "a");
            String id =
                    require(accepted, "accept refund", 201).path("refund_id").asString();
            run.step("Refund " + id + " accepted and picked up by a worker");
            await("worker crash on refund " + id, () -> Optional.ofNullable(crash.crashedSubject()));
            run.step("The worker was killed mid-command; the refund exists at the processor but nothing was recorded");
            awaitRefundCommand(id, 2);
            run.step("The lease expired, another lease epoch took over and finished the command without sending a"
                    + " second refund");
        } finally {
            crash.disarm();
        }
    }

    void badStatement(ActionRun run) {
        String first = settledPayout(run);
        String second = settledPayout(run);
        require(
                sim.put(
                        "/sim/bank/plan",
                        Map.of(
                                "payout_ref",
                                first,
                                "scenario",
                                "demo-short-deposit",
                                "fault",
                                "SHORT",
                                "parameter",
                                100)),
                "plan short deposit");
        require(
                sim.put(
                        "/sim/bank/plan",
                        Map.of(
                                "payout_ref",
                                second,
                                "scenario",
                                "demo-unknown-credit",
                                "fault",
                                "UNKNOWN_CREDIT",
                                "parameter",
                                100)),
                "plan unknown credit");
        run.step("The bank will deposit " + first + " short by " + money(100) + " and add an unexplained " + money(100)
                + " credit next to " + second);
        paid(run, first);
        paid(run, second);
        long before = stats.discrepancies();
        depositAndImport(run, Set.of(first, second));
        await(
                "discrepancies for the bad statement",
                () -> stats.discrepancies() >= before + 2 ? Optional.of(true) : Optional.empty());
        run.step("The statement was imported as evidence; the mismatch and the unknown line opened discrepancies,"
                + " and no ledger entry was changed");
    }

    /** The initial scenario of section 5: three settled payments, one finished refund, one BANKED batch. */
    boolean seed(ActionRun run) {
        if (stats.hasPayments()) {
            run.step("The ledger already has data; nothing to seed");
            return false;
        }
        Charge first = charge(run, 12000, true);
        charge(run, 8000, true);
        charge(run, 5000, true);
        String batch = settle(run);
        paid(run, batch);
        Charge funding = charge(run, 6000, true);
        run.step("Payment " + funding.ref() + " keeps the processor funded for the refund");
        JsonClient.Reply accepted = refund(run, first.ref(), 2000, "seed");
        String refund =
                require(accepted, "accept refund", 201).path("refund_id").asString();
        await("refund " + refund + " SUCCEEDED", () -> {
            JsonNode body = app.get("/api/refunds/" + refund).body();
            return body.path("refund").path("status").asString().equals("SUCCEEDED")
                    ? Optional.of(body)
                    : Optional.empty();
        });
        run.step("Refund " + refund + " of " + money(2000) + " SUCCEEDED");
        depositAndImport(run, Set.of(batch));
        await("batch " + batch + " BANKED", () -> batch(batch, "BANKED"));
        run.step("Batch " + batch + " is BANKED: the bank statement matched the settlement");
        return true;
    }

    // ------------------------------------------------------------------------------------------ building blocks

    private record Charge(String ref, String eventId, long amount) {}

    private Charge charge(ActionRun run, long amount, boolean waitCaptured) {
        JsonNode body = require(sim.post("/sim/charges", Map.of("amount", amount)), "create charge");
        Charge charge = new Charge(
                body.path("charge").path("id").asString(), body.path("event_id").asString(), amount);
        run.step("Fake Stripe charged " + money(amount) + " (" + charge.ref() + ") and sent the webhook");
        if (waitCaptured) {
            await("payment " + charge.ref() + " CAPTURED", () -> payment(charge.ref(), "CAPTURED"));
            run.step("The webhook passed signature checks and payment " + charge.ref() + " is CAPTURED");
        }
        return charge;
    }

    /** Creates a payout for everything unsettled, announces it, and confirms it with the processor's detail. */
    private String settle(ActionRun run) {
        JsonNode created = require(sim.post("/sim/payouts", Map.of()), "create payout");
        String payout = created.path("payout").path("id").asString();
        run.step("Fake Stripe created payout " + payout + " of "
                + money(created.path("payout").path("amount").asLong()));
        await("batch " + payout + " ANNOUNCED", () -> batch(payout, "ANNOUNCED"));
        confirm(run, payout);
        return payout;
    }

    private void confirm(ActionRun run, String payout) {
        require(sim.post("/sim/payouts/" + payout + "/reconcile", Map.of()), "reconcile payout");
        await("batch " + payout + " CONFIRMED", () -> batch(payout, "CONFIRMED"));
        run.step("Batch " + payout + " matched the ledger line by line and is CONFIRMED: its payments are SETTLED");
    }

    private String settledPayout(ActionRun run) {
        charge(run, randomAmount() + 1000, true);
        return settle(run);
    }

    private void paid(ActionRun run, String payout) {
        String event = require(sim.post("/sim/payouts/" + payout + "/paid", Map.of()), "mark payout paid")
                .path("event_id")
                .asString();
        await("event " + event, () -> {
            JsonNode body = app.get("/api/events/" + event).body();
            return body.path("status").asString().equals("PROCESSED") ? Optional.of(body) : Optional.empty();
        });
        run.step("Fake Stripe marked " + payout + " paid; no cash is booked until the bank says so");
    }

    /** Moves the bank forward until it has booked a transfer for every reference, then imports its statements. */
    private void depositAndImport(ActionRun run, Set<String> references) {
        for (int day = 0; day < 8 && !booked(references); day++) {
            require(sim.post("/sim/clock/advance", Map.of("seconds", 86_400)), "advance the simulated clock");
        }
        if (!booked(references)) throw new ActionFailed("The bank did not book the transfers");
        run.step("The bank booked the transfers on its next business day");
        JsonNode transactions = require(sim.get("/sim/bank/transactions"), "list bank transactions");
        Set<String> days = new HashSet<>();
        for (JsonNode line : transactions) days.add(line.path("booking_date").asString());
        for (String day : days.stream().sorted().toList()) {
            long lines = count(transactions, "booking_date", day);
            if (!importedStatements.add(day + "/" + lines)) continue;
            byte[] file = sim.getBytes("/sim/bank/statements/" + day);
            JsonClient.Reply imported = app.postCsv("/api/bank-statements", file);
            if (!imported.body().path("status").asString().equals("ACCEPTED")) {
                importedStatements.remove(day + "/" + lines);
                throw new ActionFailed("The bank statement for " + day + " was not accepted");
            }
        }
        run.step("Imported the bank statement files as independent evidence");
    }

    private boolean booked(Set<String> references) {
        Set<String> seen = new HashSet<>();
        for (JsonNode line : require(sim.get("/sim/bank/transactions"), "list bank transactions")) {
            seen.add(line.path("reference").asString());
        }
        return seen.containsAll(references);
    }

    private void ensureCash(ActionRun run, long amount) {
        JsonNode overview = require(app.get("/api/overview"), "read overview");
        if (overview.path("freeCash").asLong() >= amount
                && overview.path("merchantAvailable").asLong() >= amount) {
            return;
        }
        run.step("Not enough bank cash for a withdrawal; settling and banking a payment first");
        charge(run, randomAmount() + amount, true);
        String batch = settle(run);
        paid(run, batch);
        depositAndImport(run, Set.of(batch));
        await("batch " + batch + " BANKED", () -> batch(batch, "BANKED"));
        run.step("Batch " + batch + " is BANKED; the cash is now in the bank");
    }

    /** A settled payment with at least {@code minimum} refundable, and money at the processor to refund from. */
    private DemoStats.Refundable refundable(ActionRun run, long minimum) {
        Optional<DemoStats.Refundable> found = stats.refundable().stream()
                .filter(candidate -> candidate.free() >= minimum)
                .findFirst();
        if (found.isEmpty()) {
            run.step("No settled payment with refundable money; creating and settling one");
            charge(run, randomAmount() + minimum, true);
            String batch = settle(run);
            paid(run, batch);
            found = stats.refundable().stream()
                    .filter(candidate -> candidate.free() >= minimum)
                    .findFirst();
        }
        DemoStats.Refundable target = found.orElseThrow(() -> new ActionFailed("No refundable payment appeared"));
        Charge funding = charge(run, Math.max(2000, target.free()), true);
        run.step("Payment " + funding.ref() + " funds the processor so refunds can execute");
        return target;
    }

    private JsonClient.Reply refund(ActionRun run, String paymentRef, long amount, String suffix) {
        String key = "demo-" + run.actionId.toLowerCase(java.util.Locale.ROOT) + "-" + suffix;
        return app.post(
                "/api/refunds",
                Map.of("payment_ref", paymentRef, "merchant_refund_ref", key, "amount", amount),
                Map.of("Idempotency-Key", key));
    }

    private JsonNode awaitRefundCommand(String refund, int minimumAttempts) {
        return await("refund " + refund + " command DONE", () -> {
            JsonNode body = app.get("/api/refunds/" + refund).body();
            boolean done = body.path("commands").size() == 1
                    && body.path("commands").get(0).path("status").asString().equals("DONE");
            return done && body.path("attempts").size() >= minimumAttempts ? Optional.of(body) : Optional.empty();
        });
    }

    private Optional<JsonNode> payment(String ref, String status) {
        JsonClient.Reply reply = app.get("/api/payments/" + ref);
        return reply.ok() && reply.body().path("status").asString().equals(status)
                ? Optional.of(reply.body())
                : Optional.empty();
    }

    private Optional<JsonNode> batch(String id, String status) {
        JsonClient.Reply reply = app.get("/api/batches/" + id);
        return reply.ok()
                        && reply.body().path("batch").path("status").asString().equals(status)
                ? Optional.of(reply.body())
                : Optional.empty();
    }

    private JsonNode deliveries(String eventId) {
        JsonClient.Reply reply = app.get("/api/events/" + eventId);
        return reply.ok() ? reply.body().path("deliveries") : reply.body().path("none");
    }

    private void clearRelayPlan() {
        sim.put("/sim/relay/plan", Map.of("rules", List.of()));
    }

    // ------------------------------------------------------------------------------------------ small helpers

    private <T> T await(String what, Supplier<Optional<T>> probe) {
        Instant deadline = clock.instant().plus(properties.stepTimeout());
        while (true) {
            Optional<T> result = probe.get();
            if (result.isPresent()) return result.get();
            if (clock.instant().isAfter(deadline)) throw new ActionFailed("Timed out waiting for " + what);
            try {
                // Bounded condition polling of what the system reports, not a guess about worker timing.
                Thread.sleep(Duration.ofMillis(100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ActionFailed("Interrupted while waiting for " + what, e);
            }
        }
    }

    private static JsonNode require(JsonClient.Reply reply, String what) {
        return require(reply, what, 200);
    }

    private static JsonNode require(JsonClient.Reply reply, String what, int status) {
        if (reply.status() != status) throw new ActionFailed("Could not " + what + " (HTTP " + reply.status() + ")");
        return reply.body();
    }

    private static JsonClient.Reply result(Future<JsonClient.Reply> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActionFailed("Interrupted", e);
        } catch (ExecutionException e) {
            throw new ActionFailed("A refund call failed", e);
        }
    }

    private static long count(JsonNode rows, String field, String value) {
        long matches = 0;
        for (JsonNode row : rows) if (row.path(field).asString().equals(value)) matches++;
        return matches;
    }

    private long randomAmount() {
        return random.nextLong(500, 20_001);
    }

    static String money(long cents) {
        return String.format(java.util.Locale.ROOT, "CAD %d.%02d", cents / 100, cents % 100);
    }
}
