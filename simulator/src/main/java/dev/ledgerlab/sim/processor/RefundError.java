package dev.ledgerlab.sim.processor;

import org.springframework.http.HttpStatus;

/** Stripe-shaped protocol failure, distinct from a refund object's failed status. */
final class RefundError extends RuntimeException {
    private final HttpStatus status;
    private final String type;
    private final String code;

    RefundError(HttpStatus status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String type() {
        return type;
    }

    String code() {
        return code;
    }

    static RefundError invalid(String code, String message) {
        return new RefundError(HttpStatus.BAD_REQUEST, "invalid_request_error", code, message);
    }

    static RefundError timeout() {
        return new RefundError(
                HttpStatus.GATEWAY_TIMEOUT, "api_error", "simulated_timeout", "Simulated refund timeout");
    }
}
