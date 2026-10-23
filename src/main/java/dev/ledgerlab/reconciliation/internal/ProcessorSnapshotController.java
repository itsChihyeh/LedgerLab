package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.reconciliation.ProcessorSnapshotReader;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ProcessorSnapshotController {
    private final ProcessorSnapshots snapshots;
    private final ProcessorSnapshotReader reader;

    public ProcessorSnapshotController(ProcessorSnapshots snapshots, ProcessorSnapshotReader reader) {
        this.snapshots = snapshots;
        this.reader = reader;
    }

    public record Request(@Nullable LocalDate businessDate) {}

    @PostMapping("/api/recon/processor-snapshots")
    public ProcessorSnapshots.Result create(@RequestBody Request request) {
        if (request.businessDate() == null) throw new IllegalArgumentException("businessDate is required");
        return snapshots.fetch(request.businessDate(), reader);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalid() {
        return "Invalid processor snapshot request";
    }
}
