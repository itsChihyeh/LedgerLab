package dev.ledgerlab.demo.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads what the system recorded since the last look (webhooks and their outcomes, ledger entries, status
 * changes) and publishes it as {@code timeline} events. Only the {@code readonly} views are read, and only
 * ids, types, statuses and times leave this class.
 */
@Component
@Profile("demo")
final class DemoTimeline {
    /** Commits can land out of order against {@code recorded_at}; every poll therefore looks a little back. */
    private static final Duration OVERLAP = Duration.ofSeconds(5);

    private static final int SEEN = 20_000;

    record Item(String kind, String key, String label, Instant at) {}

    private final JdbcClient jdbc;
    private final DemoEvents events;
    private final DemoStats stats;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN;
        }
    });
    private Instant cursor;

    DemoTimeline(
            @Qualifier("demoReadonlyDataSource") DataSource readonly, DemoEvents events, DemoStats stats, Clock clock) {
        this.jdbc = JdbcClient.create(readonly);
        this.events = events;
        this.stats = stats;
        this.cursor = clock.instant();
    }

    @Scheduled(fixedDelayString = "${ledgerlab.demo.timeline-poll:500ms}")
    synchronized void poll() {
        List<Item> fresh = new ArrayList<>();
        Instant from = cursor.minus(OVERLAP);
        Instant newest = cursor;
        for (Item item : since(from)) {
            if (item.at().isAfter(newest)) newest = item.at();
            if (seen.add(item.kind() + ":" + item.key())) fresh.add(item);
        }
        cursor = newest;
        if (fresh.isEmpty()) return;
        events.publish("timeline", Map.of("items", fresh));
        events.publish("counters", stats.counters());
    }

    List<Item> since(Instant from) {
        return jdbc.sql("""
                SELECT kind, key, label, at FROM (
                    SELECT 'inbox' AS kind, h.id::text AS key,
                           e.event_type || ' ' || h.to_status || coalesce(' ' || h.result, '') AS label,
                           h.recorded_at AS at
                    FROM core.v_inbox_status_history h JOIN core.v_event e ON e.event_id = h.event_id
                    UNION ALL
                    SELECT 'delivery', d.id::text, d.event_type || ' delivery ' || d.outcome, d.received_at
                    FROM core.v_event_delivery d
                    UNION ALL
                    SELECT 'entry', j.seq::text, j.entry_type || ' ' || j.total_amount, j.recorded_at
                    FROM ledger.v_journal_entry j
                    UNION ALL
                    SELECT 'payment', t.id::text,
                           t.subject_id || ' ' || coalesce(t.from_status || ' > ', '') || t.to_status, t.recorded_at
                    FROM core.v_payment_transition t
                    UNION ALL
                    SELECT 'refund', t.id::text,
                           t.subject_id || ' ' || coalesce(t.from_status || ' > ', '') || t.to_status, t.recorded_at
                    FROM core.v_refund_transition t
                    UNION ALL
                    SELECT 'batch', t.id::text,
                           t.subject_id || ' ' || coalesce(t.from_status || ' > ', '') || t.to_status, t.recorded_at
                    FROM core.v_batch_transition t
                    UNION ALL
                    SELECT 'payout', t.id::text,
                           t.payout_id || ' ' || coalesce(t.from_status || ' > ', '') || t.to_status, t.recorded_at
                    FROM core.v_payout_status_history t
                    UNION ALL
                    SELECT 'issue', i.id::text, i.category || ' ' || i.status, i.recorded_at
                    FROM recon.v_issue i
                ) timeline
                WHERE at >= :from ORDER BY at, kind, key LIMIT 500
                """)
                .param("from", from.atOffset(java.time.ZoneOffset.UTC))
                .query((rs, row) -> new Item(
                        rs.getString("kind"),
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getTimestamp("at").toInstant()))
                .list();
    }
}
