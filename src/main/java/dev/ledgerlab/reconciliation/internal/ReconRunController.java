package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recon/runs")
public final class ReconRunController {
    private final ReconRuns runs;
    private final ReconProperties properties;

    public ReconRunController(ReconRuns runs, ReconProperties properties) {
        this.runs = runs;
        this.properties = properties;
    }

    public record Request(
            RunRequest.@Nullable Kind kind,
            @Nullable Instant cutoffAt,
            @Nullable Instant evidenceAsOf,
            boolean dryRun) {}

    @PostMapping
    public RunReport run(@RequestBody Request request) {
        if (request.kind() == null || request.cutoffAt() == null || request.evidenceAsOf() == null) {
            throw new IllegalArgumentException("kind, cutoffAt and evidenceAsOf are required");
        }
        return runs.run(new RunRequest(
                request.kind(),
                request.cutoffAt(),
                request.evidenceAsOf(),
                properties.ruleVersion(),
                List.of(),
                request.dryRun()));
    }

    @GetMapping("/{id}")
    public RunReport get(@PathVariable String id) {
        return runs.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalid(IllegalArgumentException error) {
        return java.util.Objects.requireNonNullElse(error.getMessage(), "Invalid reconciliation request");
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String concurrent() {
        return "Concurrent disposition or run; retry the request";
    }
}
