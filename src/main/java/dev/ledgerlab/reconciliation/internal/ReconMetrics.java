package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.shared.MetricsDatabase;
import dev.ledgerlab.shared.ScrapeSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Open reconciliation issues, read at each scrape through {@code recon.v_issue}. The category label is the closed
 * set of the {@code issue_category} CHECK constraint (a test compares the two, so a new category cannot be added
 * to the database without this list); every category has a series, 0 when none is open.
 */
@Component
final class ReconMetrics implements MeterBinder {
    static final List<String> CATEGORIES = List.of(
            "MISSING_IN_LEDGER",
            "MISSING_IN_STATEMENT",
            "AMOUNT_MISMATCH",
            "FEE_MISMATCH",
            "DUPLICATE_STATEMENT_LINE",
            "BATCH_TOTAL_MISMATCH",
            "REFUND_NOT_IN_STATEMENT",
            "DEPOSIT_MISSING",
            "DEPOSIT_AMOUNT_MISMATCH",
            "UNMATCHED_BANK_LINE",
            "UNAPPLIED_CASH_OPEN",
            "BANK_BALANCE_MISMATCH",
            "BATCH_VERSION_CONFLICT",
            "STATEMENT_VERSION_CONFLICT",
            "TIMING_DIFFERENCE",
            "PAYOUT_STATE_CONFLICT",
            "BATCH_STATE_CONFLICT",
            "REFUND_STATE_CONFLICT",
            "UNSUPPORTED_PAYOUT",
            "EVENT_CONTENT_CONFLICT",
            "EVENT_BUSINESS_CONFLICT",
            "UNSUPPORTED_EVENT",
            "LIVEMODE_EVENT",
            "PAYMENT_NOT_SETTLED",
            "DEPOSIT_NOT_RECEIVED",
            "REFUND_AWAITING_FUNDS_AGED",
            "REFUND_NOT_EXECUTED",
            "PAYOUT_UNRESOLVED",
            "EVENT_PARKED_AGED",
            "UNSUPPORTED_BATCH_LINE");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ScrapeSnapshot<OpenIssues> snapshot = new ScrapeSnapshot<>("recon", this::load);

    ReconMetrics(@Qualifier(MetricsDatabase.DATA_SOURCE) DataSource metricsDataSource, Clock clock) {
        this.jdbc = MetricsDatabase.client(metricsDataSource);
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String category : CATEGORIES) {
            Gauge.builder(
                            "ledgerlab.recon.issues.open",
                            () -> snapshot.read(
                                    category, open -> open.byCategory().getOrDefault(category, 0L)))
                    .tag("category", category)
                    .description("Reconciliation issues in status OPEN")
                    .register(registry);
        }
        Gauge.builder("ledgerlab.recon.issues.oldest.open.age", () -> snapshot.read("age", OpenIssues::oldestAge))
                .baseUnit("seconds")
                .description("Age of the oldest OPEN reconciliation issue since it was recorded; 0 when none")
                .register(registry);
    }

    private OpenIssues load() {
        Instant now = clock.instant();
        Map<String, Long> counts = new HashMap<>();
        Instant[] oldest = new Instant[1];
        jdbc.sql("""
                SELECT category, count(*) AS n, min(recorded_at) AS oldest
                FROM recon.v_issue WHERE status = 'OPEN' GROUP BY category
                """)
                .query((rs, row) -> {
                    counts.put(rs.getString("category"), rs.getLong("n"));
                    Instant first = rs.getObject("oldest", OffsetDateTime.class).toInstant();
                    if (oldest[0] == null || first.isBefore(oldest[0])) oldest[0] = first;
                    return row;
                })
                .list();
        double age = oldest[0] == null
                ? 0
                : Math.max(0, Duration.between(oldest[0], now).toMillis() / 1000.0);
        return new OpenIssues(counts, age);
    }

    private record OpenIssues(Map<String, Long> byCategory, double oldestAge) {}
}
