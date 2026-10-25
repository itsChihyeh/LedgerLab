package dev.ledgerlab.sim.scenario;

import dev.ledgerlab.sim.bank.Bank;
import dev.ledgerlab.sim.processor.BalanceTransactions;
import dev.ledgerlab.sim.processor.EventDelivery;
import dev.ledgerlab.sim.processor.Payouts;
import dev.ledgerlab.sim.processor.Processor;
import dev.ledgerlab.sim.processor.RefundFaults;
import dev.ledgerlab.sim.processor.Refunds;
import dev.ledgerlab.sim.processor.SeededIds;
import dev.ledgerlab.sim.processor.SimulationClock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Runs real processor and bank services against simulator tables, without contacting the app. */
@Service
public final class ScenarioRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final PlatformTransactionManager manager;

    public ScenarioRunner(JdbcClient jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.manager = manager;
    }

    public Map<String, String> run(String name) {
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw invalid("scenario name");
        try (var input = ScenarioRunner.class.getResourceAsStream("/scenarios/" + name + ".json")) {
            if (input == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown scenario");
            return run(JSON.readTree(input));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read scenario", e);
        }
    }

    public Map<String, String> run(JsonNode scenario) {
        return Objects.requireNonNull(new TransactionTemplate(manager).execute(status -> {
            // Refuse a populated simulator; never erase interactive work. Locks also exclude its writers.
            jdbc.sql(
                            "lock table sim_processor.balance_transaction, sim_processor.charge, sim_processor.payout, sim_processor.fault_plan, sim_processor.refunds, sim_processor.idempotency_keys, sim_processor.refund_faults, sim_bank.account, sim_bank.fault_plan in exclusive mode")
                    .update();
            if (jdbc.sql(
                                    "select (select count(*) from sim_processor.charge)+(select count(*) from sim_processor.payout)+(select count(*) from sim_bank.account)+(select count(*) from sim_bank.fault_plan)+(select count(*) from sim_processor.fault_plan)+(select count(*) from sim_processor.refund_faults)+(select count(*) from sim_processor.idempotency_keys)")
                            .query(Long.class)
                            .single()
                    != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Scenario export requires an empty simulator");
            status.setRollbackOnly();
            return new Execution(scenario).run();
        }));
    }

    private final class Execution {
        private final JsonNode scenario;
        private final SimulationClock clock;
        private final SeededIds ids;
        private final Bank bank;
        private final Processor processor;
        private final Payouts payouts;
        private final Refunds refunds;
        private final RefundFaults refundFaults;
        private final Map<String, String> aliases = new LinkedHashMap<>();
        private final Map<String, String> files = new LinkedHashMap<>();
        private final ArrayNode steps = JSON.createArrayNode();
        private int eventNumber;
        private int responseNumber;
        private int bankNumber;

        Execution(JsonNode scenario) {
            this.scenario = scenario;
            if (scenario.path("format_version").asInt() != 1
                    || !scenario.path("seed").isIntegralNumber()
                    || !scenario.path("seed").canConvertToLong()
                    || !scenario.path("actions").isArray()
                    || !scenario.path("checkpoints").isArray()) throw invalid("scenario header");
            var checkpointNames = new java.util.HashSet<String>();
            for (JsonNode checkpoint : scenario.path("checkpoints")) {
                if (!checkpointNames.add(required(checkpoint, "name"))
                        || !checkpoint.path("expected_findings").isArray()) throw invalid("checkpoint");
                Instant.parse(required(checkpoint, "cutoff_at"));
                Instant.parse(required(checkpoint, "evidence_as_of"));
            }
            clock = new SimulationClock(Instant.parse(required(scenario, "start_clock")));
            ids = new SeededIds(scenario.path("seed").longValue());
            bank = new Bank(jdbc, manager, clock);
            var balances = new BalanceTransactions(jdbc, clock, clock, ids, Duration.ZERO);
            EventDelivery delivery = (id, payload) -> {
                String file = "events/%03d.json".formatted(++eventNumber);
                files.put(file, payload);
                step("event").put("body", file).putArray("responses");
            };
            processor = new Processor(jdbc, manager, clock, clock, ids, delivery, balances);
            refundFaults = new RefundFaults(jdbc, clock, balances, manager);
            refunds = new Refunds(jdbc, manager, balances, processor, delivery, clock, clock, ids, refundFaults);
            payouts = new Payouts(jdbc, manager, balances, processor, clock, clock, ids, bank);
        }

        Map<String, String> run() {
            for (JsonNode action : scenario.path("actions")) {
                switch (required(action, "action")) {
                    case "advance", "advance_clock" -> {
                        Duration delta = action.has("to")
                                ? Duration.between(clock.instant(), Instant.parse(required(action, "to")))
                                : Duration.ofSeconds(seconds(action));
                        clock.advance(delta);
                        bank.advance();
                    }
                    case "refund_fault" -> refundFaults.configure(resolve(action.path("request")));
                    case "refund_accept" -> {
                        String token = required(action, "local_ref");
                        alias(required(action, "alias"), token);
                        var api = step("refund_api").put("local_ref", token).put("key", required(action, "key"));
                        api.set("request", resolve(action.path("request")));
                    }
                    case "refund_submit" -> {
                        JsonNode request = resolve(action.path("request"));
                        Map<String, String> parameters = new java.util.TreeMap<>(Map.of(
                                "charge",
                                required(request, "charge"),
                                "amount",
                                Long.toString(request.path("amount").longValue()),
                                "metadata[ledgerlab_refund_id]",
                                required(request, "refund_id")));
                        var responses = step("outbound").putArray("responses");
                        var submission = refunds.create(parameters, required(request, "refund_id"));
                        if (submission.hang()) throw invalid("scenario refund submission timed out");
                        alias(
                                required(action, "alias"),
                                submission.refund().path("id").asText());
                        String file = "rest/%03d.json".formatted(++responseNumber);
                        files.put(file, JSON.writeValueAsString(submission.refund()));
                        var response = responses
                                .addObject()
                                .put("method", "POST")
                                .put("path", "/v1/refunds")
                                .put("status", 200)
                                .put("body", file)
                                .put("idempotency_key", required(request, "refund_id"));
                        response.set("query", JSON.createObjectNode());
                        response.set("form", JSON.valueToTree(parameters));
                    }
                    case "process_refunds" -> refunds.process();
                    case "charge" -> {
                        var created = processor.create(resolve(action.path("request")));
                        JsonNode charge = (JsonNode) Objects.requireNonNull(created.get("charge"));
                        alias(required(action, "alias"), charge.path("id").asText());
                        if (action.has("line_alias"))
                            alias(
                                    required(action, "line_alias"),
                                    charge.path("balance_transaction").asText());
                    }
                    case "payout" -> {
                        JsonNode payout = (JsonNode) Objects.requireNonNull(
                                payouts.create(resolve(action.path("request"))).get("payout"));
                        alias(required(action, "alias"), payout.path("id").asText());
                    }
                    case "reconcile", "paid", "failed" -> {
                        String id = reference(required(action, "payout"));
                        int deliveriesBefore = eventNumber;
                        payouts.transition(id, required(action, "action"), resolve(action.path("request")));
                        if (eventNumber > deliveriesBefore
                                && action.path("action").asText().equals("reconcile"))
                            pages(
                                    (ArrayNode) steps.get(steps.size() - 1).path("responses"),
                                    "/v1/balance_transactions",
                                    Map.of("payout", id));
                    }
                    case "processor_fault" ->
                        payouts.faults(reference(required(action, "payout")), resolve(action.path("request")));
                    case "bank_fault" -> bank.plan(resolve(action.path("request")));
                    case "external_alias" -> alias(required(action, "alias"), ids.next(required(action, "prefix")));
                    case "snapshot" -> snapshot(LocalDate.parse(required(action, "business_date")));
                    case "bank_export" -> {
                        String file = "bank/%03d.csv".formatted(++bankNumber);
                        files.put(file, bank.statement(LocalDate.parse(required(action, "date")), ""));
                        step("bank").put("body", file);
                        for (JsonNode binding : action.path("aliases")) {
                            var matches = bank.transactions().stream()
                                    .filter(line -> line.reference().equals(reference(required(binding, "payout"))))
                                    .toList();
                            alias(
                                    required(binding, "alias"),
                                    matches.get(binding.path("index").asInt()).bank_txn_id());
                        }
                    }
                    case "checkpoint" -> step("checkpoint").put("name", required(action, "name"));
                    default -> throw invalid("action " + action.path("action"));
                }
            }
            ObjectNode truth = JSON.createObjectNode().put("format_version", 1);
            truth.set("aliases", JSON.valueToTree(aliases));
            truth.set("checkpoints", resolve(scenario.path("checkpoints")));
            files.put("truth.json", pretty(truth));
            files.put(
                    "manifest.json",
                    pretty(JSON.createObjectNode().put("format_version", 1).set("steps", steps)));
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }

        private void snapshot(LocalDate date) {
            ObjectNode step = step("snapshot").put("business_date", date.toString());
            ArrayNode responses = step.putArray("responses");
            ZoneId zone = ZoneId.of("America/Toronto");
            ArrayNode found = pages(
                    responses,
                    "/v1/payouts",
                    Map.of(
                            "created[gte]",
                                    Long.toString(date.atStartOfDay(zone).toEpochSecond()),
                            "created[lt]",
                                    Long.toString(
                                            date.plusDays(1).atStartOfDay(zone).toEpochSecond())));
            for (JsonNode payout : found)
                pages(
                        responses,
                        "/v1/balance_transactions",
                        Map.of("payout", payout.path("id").asText()));
        }

        private ArrayNode pages(ArrayNode responses, String path, Map<String, String> query) {
            var parameters = new java.util.TreeMap<>(query);
            parameters.put("limit", "100");
            ArrayNode items = JSON.createArrayNode();
            while (true) {
                ObjectNode body = path.equals("/v1/payouts")
                        ? payouts.list(parameters)
                        : payouts.lines(Objects.requireNonNull(parameters.get("payout")), parameters);
                String file = "rest/%03d.json".formatted(++responseNumber);
                files.put(file, JSON.writeValueAsString(body));
                var response = responses
                        .addObject()
                        .put("method", "GET")
                        .put("path", path)
                        .put("status", 200)
                        .put("body", file);
                response.set("query", JSON.valueToTree(parameters));
                for (JsonNode item : body.path("data")) items.add(item);
                if (!body.path("has_more").asBoolean()) return items;
                JsonNode data = body.path("data");
                parameters.put(
                        "starting_after", data.get(data.size() - 1).path("id").asText());
            }
        }

        private ObjectNode step(String type) {
            return steps.addObject().put("type", type).put("at", clock.instant().toString());
        }

        private void alias(String alias, String id) {
            if (aliases.putIfAbsent(alias, id) != null) throw invalid("duplicate alias " + alias);
        }

        private String reference(String alias) {
            String result = aliases.get(alias);
            if (result == null) throw invalid("unknown alias " + alias);
            return result;
        }

        private JsonNode resolve(JsonNode node) {
            if (node.isString() && node.asText().startsWith("$"))
                return JSON.getNodeFactory().stringNode(reference(node.asText().substring(1)));
            if (node.isObject()) {
                ObjectNode result = JSON.createObjectNode();
                node.properties().forEach(e -> result.set(e.getKey(), resolve(e.getValue())));
                return result;
            }
            if (node.isArray()) {
                ArrayNode result = JSON.createArrayNode();
                node.forEach(value -> result.add(resolve(value)));
                return result;
            }
            return node.deepCopy();
        }
    }

    private static String required(JsonNode node, String field) {
        if (!node.path(field).isString() || node.path(field).asText().isBlank()) throw invalid(field);
        return node.path(field).asText();
    }

    private static long seconds(JsonNode action) {
        JsonNode value = action.path("seconds");
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw invalid("seconds");
        return value.asLong();
    }

    private static String pretty(JsonNode node) {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node).replace("\r\n", "\n") + "\n";
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + reason);
    }
}
