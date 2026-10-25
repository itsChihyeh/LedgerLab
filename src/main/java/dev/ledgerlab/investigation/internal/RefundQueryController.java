package dev.ledgerlab.investigation.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class RefundQueryController {
    private final RefundQueries queries;

    RefundQueryController(RefundQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/refunds/{id}")
    RefundQueries.Detail detail(@PathVariable String id) {
        return queries.detail(id);
    }
}
