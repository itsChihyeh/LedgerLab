package dev.ledgerlab.investigation.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class CorrectionTraceController {
    private final CorrectionTraceQueries queries;

    CorrectionTraceController(CorrectionTraceQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/correction-traces/{id}")
    CorrectionTraceQueries.Trace trace(@PathVariable String id) {
        return queries.trace(id);
    }

    @GetMapping("/api/entries/{id}")
    CorrectionTraceQueries.EntryDetail entry(@PathVariable String id) {
        return queries.entry(id);
    }
}
