package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.reconciliation.IssueDisposition;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recon/issues/{id}")
final class IssueDispositionController {
    private final IssueDisposition disposition;

    IssueDispositionController(IssueDisposition disposition) {
        this.disposition = disposition;
    }

    record Acceptance(@Nullable String reason) {}

    record Resolution(@Nullable String note) {}

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(@PathVariable String id, @RequestBody Acceptance request) {
        disposition.accept(id, requireText(request.reason()));
    }

    @PostMapping("/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolve(@PathVariable String id, @RequestBody Resolution request) {
        disposition.resolve(id, requireText(request.note()));
    }

    private static String requireText(@Nullable String text) {
        if (text == null || text.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Disposition explanation is required");
        return text;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String missing() {
        return "Issue not found";
    }

    @ExceptionHandler({IllegalStateException.class, ConcurrencyFailureException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    String conflict() {
        return "Issue is no longer OPEN or was concurrently changed";
    }
}
