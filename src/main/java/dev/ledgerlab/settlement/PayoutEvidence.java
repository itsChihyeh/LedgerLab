package dev.ledgerlab.settlement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public record Lines(long fetchId, List<Line> items, Map<String, RefundIdentity> refunds) {
        public Lines(long fetchId, List<Line> items) {
            this(fetchId, items, Map.of());
        }

        public Lines {
            items = List.copyOf(items);
            refunds = Map.copyOf(refunds);
        }
    }

    /** Identity from an archived retrieve, not a separate execution observation. */
    public record RefundIdentity(@Nullable String refundId, long amount, String chargeRef) {}

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
