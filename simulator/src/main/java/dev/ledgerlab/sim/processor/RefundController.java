package dev.ledgerlab.sim.processor;

import java.time.DateTimeException;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public final class RefundController {
    private final Refunds refunds;
    private final RefundFaults faults;
    private final String key;
    private final JsonMapper json = JsonMapper.builder().build();

    public RefundController(Refunds refunds, RefundFaults faults, @Value("${sim.stripe.api-key:}") String key) {
        if (!key.isEmpty() && !(key.startsWith("sk_test_") || key.startsWith("rk_test_")))
            throw new IllegalArgumentException("Simulator API key must be a test key");
        this.refunds = refunds;
        this.faults = faults;
        this.key = key;
    }

    @PostMapping(value = "/v1/refunds", consumes = "application/x-www-form-urlencoded")
    public DeferredResult<ResponseEntity<JsonNode>> create(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization,
            @RequestHeader(value = "Stripe-Version", required = false) @Nullable String version,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {
        authenticate(authorization, version);
        var form = new TreeMap<String, String>();
        parameters.forEach((name, values) -> {
            if (values.size() != 1) throw RefundError.invalid("parameter_invalid", "Repeated form parameter");
            form.put(name, values.getFirst());
        });
        var submission = refunds.create(form, idempotencyKey == null ? "" : idempotencyKey);
        // Release the servlet thread but send no headers/body: the client actually times out.
        // The 30s servlet timeout only bounds abandoned transport resources; faults use simulation time/counters.
        var response = new DeferredResult<ResponseEntity<JsonNode>>(
                30_000L, ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(errorBody(RefundError.timeout())));
        if (!submission.hang()) response.setResult(ResponseEntity.ok(submission.refund()));
        return response;
    }

    @GetMapping("/v1/refunds/{id}")
    public JsonNode get(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization,
            @RequestHeader(value = "Stripe-Version", required = false) @Nullable String version) {
        authenticate(authorization, version);
        return refunds.get(id);
    }

    @GetMapping("/v1/refunds")
    public JsonNode list(
            @RequestParam Map<String, String> parameters,
            @RequestHeader(value = "Authorization", required = false) @Nullable String authorization,
            @RequestHeader(value = "Stripe-Version", required = false) @Nullable String version) {
        authenticate(authorization, version);
        return refunds.list(parameters);
    }

    @PostMapping("/sim/refunds/faults")
    public JsonNode faults(@RequestBody JsonNode request) {
        return faults.configure(request);
    }

    @PostMapping("/sim/refunds/process")
    public Map<String, Integer> process() {
        return Map.of("events", refunds.process());
    }

    @PostMapping("/sim/idempotency/purge")
    public Map<String, Integer> purge(@RequestBody(required = false) @Nullable JsonNode request) {
        return Map.of("purged", refunds.purge(request == null ? json.createObjectNode() : request));
    }

    @PostMapping("/sim/refunds/{id}/{action:succeed|fail}")
    public JsonNode transition(
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody(required = false) @Nullable JsonNode request) {
        return refunds.transition(id, action, request == null ? json.createObjectNode() : request);
    }

    private void authenticate(@Nullable String authorization, @Nullable String version) {
        if (key.isEmpty() || !("Bearer " + key).equals(authorization))
            throw new RefundError(
                    HttpStatus.UNAUTHORIZED, "invalid_request_error", "api_key_invalid", "Invalid API key");
        if (!"2026-09-30.endive".equals(version))
            throw RefundError.invalid("parameter_invalid", "Stripe-Version must be 2026-09-30.endive");
    }

    @ExceptionHandler(RefundError.class)
    public ResponseEntity<JsonNode> error(RefundError error) {
        return ResponseEntity.status(error.status()).body(errorBody(error));
    }

    @ExceptionHandler({
        ArithmeticException.class,
        DateTimeException.class,
        IllegalArgumentException.class,
        ResponseStatusException.class
    })
    public ResponseEntity<JsonNode> invalid(RuntimeException error) {
        return error(RefundError.invalid("parameter_invalid", "Invalid refund parameter"));
    }

    private JsonNode errorBody(RefundError error) {
        var result = json.createObjectNode();
        result.putObject("error")
                .put("type", error.type())
                .put("code", error.code())
                .put("message", error.getMessage());
        return result;
    }
}
