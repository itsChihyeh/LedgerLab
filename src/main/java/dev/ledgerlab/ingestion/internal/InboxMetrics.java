package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.shared.MetricsDatabase;
import dev.ledgerlab.shared.ScrapeSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Inbox backlog gauges (docs/design/observability.md section 3), read at each scrape through the readonly view
 * {@code core.v_inbox_backlog}. The labels are the two unfinished statuses, a closed set.
 */
@Component
final class InboxMetrics implements MeterBinder {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final ScrapeSnapshot<Backlog> snapshot = new ScrapeSnapshot<>("inbox", this::load);

    InboxMetrics(@Qualifier(MetricsDatabase.DATA_SOURCE) DataSource metricsDataSource, Clock clock) {
        this.jdbc = MetricsDatabase.client(metricsDataSource);
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ledgerlab.inbox.backlog", () -> snapshot.read("received", Backlog::received))
                .tag("status", "RECEIVED")
                .description("Inbox events waiting in this status")
                .register(registry);
        Gauge.builder("ledgerlab.inbox.backlog", () -> snapshot.read("parked", Backlog::parked))
                .tag("status", "PARKED")
                .description("Inbox events waiting in this status")
                .register(registry);
        Gauge.builder("ledgerlab.inbox.oldest.due.age", () -> snapshot.read("age", Backlog::oldestDueAgeSeconds))
                .baseUnit("seconds")
                .description("Age of the oldest RECEIVED event whose next attempt is due; 0 when none")
                .register(registry);
    }

    private Backlog load() {
        Instant now = clock.instant();
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'RECEIVED') AS received,
                       count(*) FILTER (WHERE status = 'PARKED') AS parked,
                       min(received_at) FILTER (WHERE status = 'RECEIVED' AND next_attempt_at <= :now) AS oldest_due
                FROM core.v_inbox_backlog
                """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query((rs, row) -> {
                    var oldest = rs.getObject("oldest_due", OffsetDateTime.class);
                    double age = oldest == null
                            ? 0
                            : Math.max(
                                    0, Duration.between(oldest.toInstant(), now).toMillis() / 1000.0);
                    return new Backlog(rs.getLong("received"), rs.getLong("parked"), age);
                })
                .single();
    }

    private record Backlog(long received, long parked, double oldestDueAgeSeconds) {}
}
