package dev.ledgerlab.sim.processor;

import dev.ledgerlab.sim.bank.Bank;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public final class PayoutController {
    private final Payouts payouts;
    private final SimulationClock clock;
    private final String key;
    private final Bank bank;
    private final JsonMapper json = JsonMapper.builder().build();

    public PayoutController(
            Payouts payouts, SimulationClock clock, @Value("${sim.stripe.api-key:}") String key, Bank bank) {
        if (!key.isEmpty() && !(key.startsWith("sk_test_") || key.startsWith("rk_test_")))
            throw new IllegalArgumentException("Simulator API key must be a test key");
        this.payouts = payouts;
        this.clock = clock;
        this.key = key;
        this.bank = bank;
    }

    @PostMapping("/sim/payouts")
    public Map<String, Object> create(@RequestBody(required = false) @Nullable JsonNode request) {
        return payouts.create(request == null ? json.createObjectNode() : request);
    }

    @PostMapping("/sim/payouts/{id}/{action:reconcile|paid|failed}")
    public Map<String, Object> transition(
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody(required = false) @Nullable JsonNode request) {
        return payouts.transition(id, action, request == null ? json.createObjectNode() : request);
    }

    @PostMapping("/sim/payouts/{id}/faults")
    public JsonNode faults(@PathVariable String id, @RequestBody JsonNode request) {
        return payouts.faults(id, request);
    }

    @GetMapping("/sim/clock")
    public Map<String, Object> clock() {
        return Map.of("now", clock.instant().toString());
    }

    @PostMapping("/sim/clock/advance")
    public Map<String, Object> advance(@RequestBody JsonNode request) {
        long seconds = Processor.integer(request, "seconds", null);
        if (seconds < 0) throw Processor.badRequest("seconds");
        clock.advance(Duration.ofSeconds(seconds));
        bank.advance();
        return clock();
    }

    @GetMapping("/v1/payouts/{id}")
    public JsonNode get(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization) {
        authenticate(authorization);
        return payouts.get(id);
    }

    @GetMapping("/v1/payouts")
    public JsonNode list(
            @RequestParam Map<String, String> parameters,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization) {
        authenticate(authorization);
        return payouts.list(parameters);
    }

    @GetMapping("/v1/balance_transactions")
    public JsonNode lines(
            @RequestParam Map<String, String> parameters,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization) {
        authenticate(authorization);
        String id = parameters.get("payout");
        if (id == null || id.isBlank()) throw Processor.badRequest("payout");
        return payouts.lines(id, parameters);
    }

    private void authenticate(@Nullable String authorization) {
        if (key.isEmpty() || !("Bearer " + key).equals(authorization))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler({ArithmeticException.class, DateTimeException.class, IllegalArgumentException.class})
    public org.springframework.http.ResponseEntity<Map<String, String>> invalidRange(RuntimeException error) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid numeric or time range"));
    }
}
