package dev.ledgerlab.settlement.internal;

import dev.ledgerlab.settlement.BatchRecheckException;
import dev.ledgerlab.settlement.BatchRechecks;
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
final class BatchRecheckController {
    private final BatchRechecks rechecks;

    BatchRecheckController(BatchRechecks rechecks) {
        this.rechecks = rechecks;
    }

    @PostMapping("/api/batches/{batchId}/recheck")
    BatchRechecks.RecheckResult recheck(@PathVariable String batchId) {
        return rechecks.recheck(batchId);
    }

    @ExceptionHandler(BatchRecheckException.class)
    ResponseEntity<ProblemDetail> rejected(BatchRecheckException error) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(error.status()), error.code());
        detail.setProperty("code", error.code());
        return ResponseEntity.status(error.status()).body(detail);
    }
}
