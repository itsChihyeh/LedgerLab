package dev.ledgerlab.investigation.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class PaymentQueryController {
    private final PaymentQueries queries;

    PaymentQueryController(PaymentQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/payments/{ref}")
    PaymentQueries.PaymentDetail detail(@PathVariable String ref) {
        return queries.detail(ref);
    }

    @GetMapping("/api/payments")
    PaymentQueries.PaymentList recent(@RequestParam(defaultValue = "20") int limit) {
        return queries.recent(limit);
    }
}
