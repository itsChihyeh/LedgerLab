package dev.ledgerlab.demo.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The demo's HTTP surface (ADR-0017). It exists only in the {@code demo} profile; elsewhere these paths are 404. */
@RestController
@Profile("demo")
@RequestMapping("/api/demo")
final class DemoController {
    private final DemoActions actions;
    private final DemoEvents events;
    private final DemoStats stats;
    private final DemoArchive archive;

    DemoController(DemoActions actions, DemoEvents events, DemoStats stats, DemoArchive archive) {
        this.actions = actions;
        this.events = events;
        this.stats = stats;
        this.archive = archive;
    }

    @PostMapping("/actions/{action}")
    ResponseEntity<Map<String, Object>> act(@PathVariable String action) {
        return answer(() -> actions.run(action));
    }

    @PostMapping("/seed")
    ResponseEntity<Map<String, Object>> seed() {
        return answer(actions::seed);
    }

    @PostMapping("/visit")
    ResponseEntity<Void> visit() {
        stats.countVisit();
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@RequestHeader(value = "Last-Event-ID", required = false) @Nullable String last) {
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-cache").body(events.subscribe(last));
        } catch (DemoEvents.TooManyStreams full) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    @GetMapping("/summary")
    Map<String, Object> summary() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("day", stats.day().toString());
        body.put("counters", stats.counters());
        body.put("actionsToday", stats.actionCounts(stats.day()));
        body.put("pageViews", stats.pageViews());
        body.put("invariants", actions.lastInvariants());
        return body;
    }

    @GetMapping("/archive")
    Map<String, Object> archive() {
        return archive.read();
    }

    private ResponseEntity<Map<String, Object>> answer(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (DemoActions.UnknownAction unknown) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "UNKNOWN_ACTION"));
        } catch (DemoActions.Busy busy) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "ACTION_IN_PROGRESS", "message", "The previous action is still running"));
        } catch (DemoActions.Failed failed) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(failed.body);
        }
    }
}
