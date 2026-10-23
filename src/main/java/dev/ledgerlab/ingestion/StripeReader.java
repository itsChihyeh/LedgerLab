package dev.ledgerlab.ingestion;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read-only processor evidence. Callers decide readiness, admission and business effects. */
public interface StripeReader {
    FetchResult<Payout> retrievePayout(String payoutId, FetchPurpose purpose, @Nullable String eventId);

    FetchResult<BalanceTransaction> listBalanceTransactionsForPayout(
            String payoutId, FetchPurpose purpose, @Nullable String eventId);

    /** Stripe's created interval is [from, to); boundaries must be whole epoch seconds. */
    FetchResult<Payout> listPayoutsCreated(Instant from, Instant to, FetchPurpose purpose, @Nullable String eventId);

    enum FetchPurpose {
        BATCH_LINES,
        PAYOUT,
        RECON_SNAPSHOT
    }

    /** Failed fetches expose no partial items; fetchId still identifies the archived evidence. */
    record FetchResult<T>(
            long fetchId,
            String responseSha256,
            Instant fetchedAt,
            List<T> items,
            @Nullable String failure) {
        public FetchResult {
            items = failure == null ? List.copyOf(items) : List.of();
        }

        public boolean complete() {
            return failure == null;
        }
    }

    record Payout(
            String id,
            long amount,
            String currency,
            boolean automatic,
            String method,
            String status,
            String reconciliationStatus,
            Instant arrivalDate,
            Instant created) {}

    record BalanceTransaction(
            String id,
            String type,
            @Nullable String source,
            long amount,
            long fee,
            long net,
            String currency,
            Instant created) {}
}
