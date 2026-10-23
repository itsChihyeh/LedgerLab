package dev.ledgerlab.reconciliation;

import java.time.Instant;
import java.util.List;

/** Consumer-owned port: ingestion adapts StripeReader; reconciliation never depends on ingestion. */
public interface ProcessorSnapshotReader {
    FetchResult listPayoutsCreated(Instant from, Instant to);

    FetchResult listBalanceTransactionsForPayout(String payoutId);

    /** Items are immutable Stripe-shaped JSON objects; incomplete fetches have no usable items. */
    record FetchResult(long fetchId, boolean complete, Instant fetchedAt, List<String> items) {
        public FetchResult {
            items = complete ? List.copyOf(items) : List.of();
        }
    }
}
