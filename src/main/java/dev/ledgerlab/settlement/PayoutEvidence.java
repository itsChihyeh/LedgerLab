package dev.ledgerlab.settlement;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Processor facts mapped by ingestion; no HTTP client or inbox dependency. */
public record PayoutEvidence(
        String eventId,
        String evidenceType,
        String evidenceId,
        String eventType,
        String batchId,
        long amount,
        String currency,
        boolean automatic,
        String method,
        String reconciliationStatus,
        Instant arrivalDate,
        Instant created,
        @Nullable Lines lines) {
    public boolean supported() {
        return automatic
                && method.equals("standard")
                && currency.equalsIgnoreCase("cad")
                && amount > 0
                && !reconciliationStatus.equals("not_applicable");
    }

    public boolean ready() {
        return reconciliationStatus.equals("completed");
    }

    public record Lines(long fetchId, List<Line> items) {
        public Lines {
            items = List.copyOf(items);
        }
    }

    public record Line(
            String id,
            String type,
            @Nullable String source,
            long gross,
            long fee,
            long net,
            String currency,
            Instant occurredAt) {}
}
