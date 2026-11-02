package dev.ledgerlab.payouts.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class PayoutQueryController {
    private final PayoutQueries queries;

    PayoutQueryController(PayoutQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/payouts")
    PayoutQueries.PayoutList list(
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.list(limit, offset);
    }

    @GetMapping("/api/payouts/{id}")
    PayoutQueries.Detail detail(@PathVariable String id) {
        return queries.detail(id);
    }
}
