package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.reconciliation.ProcessorSnapshotReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ProcessorSnapshotAdapter implements ProcessorSnapshotReader {
    private final StripeReader reader;
    private final ObjectMapper mapper;

    public ProcessorSnapshotAdapter(StripeReader reader, ObjectMapper mapper) {
        this.reader = reader;
        this.mapper = mapper;
    }

    @Override
    public FetchResult listPayoutsCreated(Instant from, Instant to) {
        var result = reader.listPayoutsCreated(from, to, StripeReader.FetchPurpose.RECON_SNAPSHOT, null);
        return new FetchResult(
                result.fetchId(),
                result.complete(),
                result.fetchedAt(),
                result.items().stream()
                        .map(p -> mapper.writeValueAsString(Map.of(
                                "id",
                                p.id(),
                                "amount",
                                p.amount(),
                                "currency",
                                p.currency(),
                                "automatic",
                                p.automatic(),
                                "method",
                                p.method(),
                                "status",
                                p.status(),
                                "reconciliation_status",
                                p.reconciliationStatus(),
                                "arrival_date",
                                p.arrivalDate().getEpochSecond(),
                                "created",
                                p.created().getEpochSecond())))
                        .toList());
    }

    @Override
    public FetchResult listBalanceTransactionsForPayout(String payoutId) {
        var result = reader.listBalanceTransactionsForPayout(payoutId, StripeReader.FetchPurpose.RECON_SNAPSHOT, null);
        return new FetchResult(
                result.fetchId(),
                result.complete(),
                result.fetchedAt(),
                result.items().stream()
                        .map(l -> {
                            Map<String, Object> fields = new LinkedHashMap<>();
                            fields.put("id", l.id());
                            fields.put("type", l.type());
                            fields.put("source", l.source());
                            fields.put("amount", l.amount());
                            fields.put("fee", l.fee());
                            fields.put("net", l.net());
                            fields.put("currency", l.currency());
                            fields.put("created", l.created().getEpochSecond());
                            return mapper.writeValueAsString(fields);
                        })
                        .toList());
    }
}
