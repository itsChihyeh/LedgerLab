package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.payouts.PayoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
final class PayoutController {
    private final PayoutApi api;
    private final ObjectMapper mapper;

    PayoutController(PayoutApi api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/payouts", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> reserve(
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            @RequestBody(required = false) @Nullable String raw) {
        if (key == null || !key.matches("[!-~]{1,255}")) throw new PayoutException("IDEMPOTENCY_KEY_REQUIRED");
        var result = api.accept(key, PayoutJson.request(mapper, raw));
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body());
    }

    @ExceptionHandler(PayoutException.class)
    ResponseEntity<ProblemDetail> rejected(PayoutException error) {
        int status = switch (error.code()) {
            case "IDEMPOTENCY_KEY_REQUIRED", "INVALID_AMOUNT", "INVALID_REQUEST", "INVALID_MERCHANT_PAYOUT_REF" -> 400;
            case "IDEMPOTENCY_KEY_REUSED" -> 422;
            default -> 409;
        };
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), error.code());
        detail.setProperty("code", error.code());
        if (error.reason() != null) detail.setProperty("reason", error.reason());
        return ResponseEntity.status(status).body(detail);
    }
}
