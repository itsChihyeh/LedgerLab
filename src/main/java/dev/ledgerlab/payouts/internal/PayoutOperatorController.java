package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.payouts.PayoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "ledgerlab.corrections", name = "enabled", havingValue = "true", matchIfMissing = true)
final class PayoutOperatorController {
    private final PayoutOperatorQueries queries;

    PayoutOperatorController(PayoutOperatorQueries queries) {
        this.queries = queries;
    }

    @PostMapping("/api/payouts/{id}/query")
    ResponseEntity<PayoutOperatorQueries.Result> query(@PathVariable String id) {
        return ResponseEntity.accepted().body(queries.enqueue(id));
    }

    @ExceptionHandler(PayoutException.class)
    ResponseEntity<ProblemDetail> rejected(PayoutException error) {
        var status = error.code().equals("PAYOUT_NOT_FOUND") ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        var detail = ProblemDetail.forStatusAndDetail(status, error.code());
        detail.setProperty("code", error.code());
        return ResponseEntity.status(status).body(detail);
    }
}
