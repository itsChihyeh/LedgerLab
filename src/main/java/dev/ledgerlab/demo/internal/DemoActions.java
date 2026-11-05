package dev.ledgerlab.demo.internal;

import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Runs one whitelisted action at a time for the whole site (a single in-memory lock): pushes each step to the
 * feed, then checks all twelve money invariants and publishes the verdict. Only the fixed action names are
 * accepted; there is no way to pass anything else to the scenarios.
 */
@Component
@Profile("demo")
final class DemoActions {
    static final List<String> NAMES = List.of(
            "pay",
            "replay-webhook",
            "batch-first",
            "double-refund",
            "drop-refund-response",
            "bank-drop-receipt",
            "kill-worker",
            "bad-statement");

    private static final Logger LOG = LoggerFactory.getLogger(DemoActions.class);

    /** Not one of the whitelisted names. */
    static final class UnknownAction extends RuntimeException {
        UnknownAction() {
            super("Unknown action");
        }
    }

    /** Another action is running. */
    static final class Busy extends RuntimeException {
        Busy() {
            super("Another action is still running");
        }
    }

    /** The scenario could not finish; what happened so far is still reported. */
    static final class Failed extends RuntimeException {
        final transient Map<String, Object> body;

        Failed(Map<String, Object> body, String message) {
            super(message);
            this.body = body;
        }
    }

    private final DemoScenarios scenarios;
    private final DemoEvents events;
    private final DemoStats stats;
    private final DemoClients clients;
    private final Clock clock;
    private final IdGenerator ids;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<@Nullable Map<String, Object>> lastInvariants = new AtomicReference<>();

    DemoActions(
            DemoScenarios scenarios,
            DemoEvents events,
            DemoStats stats,
            DemoClients clients,
            Clock clock,
            IdGenerator ids) {
        this.scenarios = scenarios;
        this.events = events;
        this.stats = stats;
        this.clients = clients;
        this.clock = clock;
        this.ids = ids;
    }

    Map<String, Object> run(String action) {
        if (!NAMES.contains(action)) throw new UnknownAction();
        return exclusively(action, run -> {
            switch (action) {
                case "pay" -> scenarios.pay(run);
                case "replay-webhook" -> scenarios.replayWebhook(run);
                case "batch-first" -> scenarios.batchFirst(run);
                case "double-refund" -> scenarios.doubleRefund(run);
                case "drop-refund-response" -> scenarios.dropRefundResponse(run);
                case "bank-drop-receipt" -> scenarios.bankDropReceipt(run);
                case "kill-worker" -> scenarios.killWorker(run);
                case "bad-statement" -> scenarios.badStatement(run);
                default -> throw new UnknownAction();
            }
            stats.countAction(action);
            if (action.equals("double-refund")) stats.countAction("over-refund-rejected");
            return Map.of();
        });
    }

    Map<String, Object> seed() {
        return exclusively("seed", run -> Map.of("seeded", scenarios.seed(run)));
    }

    private interface Script {
        Map<String, Object> apply(ActionRun run);
    }

    private Map<String, Object> exclusively(String action, Script script) {
        if (!lock.tryLock()) throw new Busy();
        try {
            ActionRun run = new ActionRun(
                    ids.next("act"),
                    action,
                    clock,
                    (owner, step) -> events.publish(
                            "step",
                            Map.of(
                                    "actionId", owner.actionId,
                                    "action", action,
                                    "n", step.n(),
                                    "label", step.label(),
                                    "at", step.at())));
            Map<String, Object> extra;
            try {
                extra = script.apply(run);
            } catch (ActionFailed | DemoCrashHooks.SimulatedCrash failure) {
                throw failed(run, String.valueOf(failure.getMessage()));
            } catch (RuntimeException unexpected) {
                LOG.warn("Demo action {} failed", action, unexpected);
                throw failed(run, "The action failed unexpectedly");
            }
            Map<String, Object> verdict = checkInvariants(run.actionId);
            events.publish("counters", stats.counters());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actionId", run.actionId);
            body.put("steps", run.steps());
            body.put("invariants", verdict);
            body.putAll(extra);
            return body;
        } finally {
            lock.unlock();
        }
    }

    private Failed failed(ActionRun run, String message) {
        run.step("Stopped: " + message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actionId", run.actionId);
        body.put("error", "ACTION_FAILED");
        body.put("message", message);
        body.put("steps", run.steps());
        body.put("invariants", checkInvariants(run.actionId));
        events.publish("counters", stats.counters());
        return new Failed(body, message);
    }

    /** Runs the twelve checks through the application's own audit endpoint and publishes the verdict. */
    Map<String, Object> checkInvariants(@Nullable String actionId) {
        JsonNode audit = clients.app().get("/api/audit/invariants").body();
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> failing = new ArrayList<>();
        for (JsonNode check : audit.path("checks")) {
            String id = check.path("id").asString();
            boolean ok = check.path("ok").asBoolean();
            checks.add(Map.of("id", id, "ok", ok));
            if (!ok) failing.add(id);
        }
        List<Object> violations = new ArrayList<>();
        for (JsonNode violation : audit.path("violations")) {
            if (violations.size() >= 20) break;
            violations.add(Map.of(
                    "invariant", violation.path("invariant").asString(),
                    "detail", violation.path("detail").asString()));
        }
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("actionId", actionId == null ? "" : actionId);
        verdict.put("checkedAt", audit.path("checkedAt").asString());
        verdict.put("ok", failing.isEmpty() && !checks.isEmpty());
        verdict.put("checks", checks);
        verdict.put("failed", failing);
        verdict.put("violations", violations);
        lastInvariants.set(verdict);
        events.publish("invariants", verdict);
        return verdict;
    }

    Map<String, Object> lastInvariants() {
        Map<String, Object> known = lastInvariants.get();
        return known != null ? known : checkInvariants(null);
    }
}
