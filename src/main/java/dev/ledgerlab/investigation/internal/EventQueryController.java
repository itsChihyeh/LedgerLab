package dev.ledgerlab.investigation.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class EventQueryController {
    private final EventQueries queries;

    EventQueryController(EventQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/events/{eventId}")
    EventQueries.EventDetail event(@PathVariable String eventId) {
        return queries.detail(eventId);
    }
}
