package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.refunds.RefundException;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
final class RefundController {
    private final RefundApi api;
    private final ObjectMapper mapper;
    private final Runnable afterCommit;

    @Autowired
    RefundController(RefundApi api, ObjectMapper mapper) {
        this(api, mapper, () -> {});
    }

    /** Package-local seam between committed service return and HTTP response writing. */
    RefundController(RefundApi api, ObjectMapper mapper, Runnable afterCommit) {
        this.api = api;
        this.mapper = mapper;
        this.afterCommit = afterCommit;
    }

    @PostMapping(value = "/api/refunds", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> accept(
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            @RequestBody(required = false) @Nullable String raw) {
        return send(api.accept(requireKey(key), RefundJson.request(mapper, raw, false)));
    }

    @PostMapping(value = "/api/refunds/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> cancel(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String key,
            @RequestBody(required = false) @Nullable String raw) {
        String checkedKey = requireKey(key);
        if (!id.matches("rfd_[0-9A-HJKMNP-TV-Z]{26}")) throw new RefundException("INVALID_REQUEST");
        return send(api.cancel(checkedKey, id, RefundJson.request(mapper, raw, true)));
    }

    private ResponseEntity<String> send(RefundApi.Response response) {
        afterCommit.run();
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }

    private static String requireKey(@Nullable String key) {
        if (key == null || !key.matches("[!-~]{1,255}")) throw new RefundException("IDEMPOTENCY_KEY_REQUIRED");
        return key;
    }

    @ExceptionHandler(RefundException.class)
    ResponseEntity<ProblemDetail> rejected(RefundException error) {
        int status = switch (error.code()) {
            case "IDEMPOTENCY_KEY_REQUIRED",
                    "INVALID_AMOUNT",
                    "INVALID_REQUEST",
                    "INVALID_MERCHANT_REFUND_REF",
                    "INVALID_CANCEL_REASON" -> 400;
            case "PAYMENT_NOT_FOUND", "REFUND_NOT_FOUND" -> 404;
            case "IDEMPOTENCY_KEY_REUSED" -> 422;
            default -> 409;
        };
        var detail =
                ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatus.valueOf(status), error.code());
        detail.setProperty("code", error.code());
        return ResponseEntity.status(status).body(detail);
    }
}
