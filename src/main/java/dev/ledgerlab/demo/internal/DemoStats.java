package dev.ledgerlab.demo.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Everything the demo shows as a number. The page counters come from real queries on the {@code readonly} role
 * (ledger, inbox and reconciliation views); only the per-day action and visit tallies are written, by the
 * {@code app} role, and they hold dates and counts, nothing else (AC-9.6).
 */
@Component
@Profile("demo")
final class DemoStats {
    /** One settled payment that still has refundable money. */
    record Refundable(String processorRef, long free) {}

    private final JdbcClient read;
    private final JdbcClient write;
    private final Clock clock;

    DemoStats(@Qualifier("demoReadonlyDataSource") DataSource readonly, JdbcClient write, Clock clock) {
        this.read = JdbcClient.create(readonly);
        this.write = write;
        this.clock = clock;
    }

    void countAction(String action) {
        write.sql("""
                INSERT INTO demo.action_count(day, action, count, recorded_at) VALUES (:day, :action, 1, :now)
                ON CONFLICT (day, action) DO UPDATE SET count = demo.action_count.count + 1
                """)
                .param("day", today())
                .param("action", action)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    void countVisit() {
        write.sql("""
                INSERT INTO demo.page_view(day, count, recorded_at) VALUES (:day, 1, :now)
                ON CONFLICT (day) DO UPDATE SET count = demo.page_view.count + 1
                """)
                .param("day", today())
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** What the page counters show. All but {@code overRefundsRejected} come straight from the ledger evidence. */
    Map<String, Long> counters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("payments", number("SELECT count(*) FROM core.v_payment"));
        counters.put("entries", number("SELECT count(*) FROM ledger.v_journal_entry"));
        counters.put("refunds", number("SELECT count(*) FROM core.v_refund"));
        counters.put("batches", number("SELECT count(*) FROM core.v_settlement_batch"));
        counters.put(
                "duplicatesAbsorbed", number("SELECT count(*) FROM core.v_event_delivery WHERE outcome = 'DUPLICATE'"));
        counters.put("parkedThenResolved", number("""
                SELECT (SELECT count(DISTINCT p.event_id) FROM core.v_inbox_status_history p
                        WHERE p.to_status = 'PARKED' AND EXISTS (
                            SELECT 1 FROM core.v_inbox_status_history d
                            WHERE d.event_id = p.event_id AND d.id > p.id AND d.to_status = 'PROCESSED'))
                     + (SELECT count(DISTINCT h.subject_id) FROM core.v_batch_transition h
                        WHERE h.to_status = 'HELD' AND EXISTS (
                            SELECT 1 FROM core.v_batch_transition n
                            WHERE n.subject_id = h.subject_id AND n.id > h.id AND n.to_status <> 'HELD'))
                """));
        // A refused refund rolls back completely and leaves no row, so this one is the tally of refusals the
        // double-refund action observed from the refund API.
        counters.put("overRefundsRejected", number("""
                SELECT coalesce(sum(count), 0) FROM demo.v_action_count WHERE action = 'over-refund-rejected'
                """));
        counters.put("unknownRecovered", unknownRecovered());
        counters.put("workerCrashesRecovered", number("""
                SELECT count(*) FROM (
                    SELECT a.command_id FROM core.v_refund_attempt_summary a
                    GROUP BY a.command_id
                    HAVING count(*) >= 2
                       AND bool_or(a.attempt_no = 1 AND a.response_status BETWEEN 200 AND 299)) crashed
                """));
        counters.put("discrepanciesOpened", number("SELECT count(*) FROM recon.v_issue"));
        return counters;
    }

    /** Refund and payout commands whose result was unknown at some point and were later resolved. */
    private long unknownRecovered() {
        long refunds = number("""
                SELECT count(*) FROM (
                    SELECT a.command_id FROM core.v_refund_attempt_summary a
                    GROUP BY a.command_id
                    HAVING bool_or(a.response_status IS NULL) AND count(*) >= 2) lost
                """);
        long payouts = number("""
                SELECT count(DISTINCT u.payout_id) FROM core.v_payout_status_history u
                WHERE u.to_status = 'UNKNOWN' AND EXISTS (
                    SELECT 1 FROM core.v_payout_status_history r
                    WHERE r.payout_id = u.payout_id AND r.id > u.id AND r.to_status IN ('ACCEPTED', 'PAID', 'FAILED'))
                """);
        return Math.addExact(refunds, payouts);
    }

    /** The tallies of one day, for each of the eight actions. */
    Map<String, Long> actionCounts(LocalDate day) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String action : DemoActions.NAMES) counts.put(action, 0L);
        read.sql("SELECT action, count FROM demo.v_action_count WHERE day = :day")
                .param("day", day)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(entry -> counts.merge(entry.getKey(), entry.getValue(), Long::sum));
        counts.remove("over-refund-rejected");
        return counts;
    }

    long discrepancies() {
        return number("SELECT count(*) FROM recon.v_issue");
    }

    Map<String, Long> pageViews() {
        Map<String, Long> views = new LinkedHashMap<>();
        views.put(
                "today",
                read.sql("SELECT coalesce(sum(count), 0) FROM demo.v_page_view WHERE day = :day")
                        .param("day", today())
                        .query(Long.class)
                        .single());
        views.put("total", number("SELECT coalesce(sum(count), 0) FROM demo.v_page_view"));
        return views;
    }

    LocalDate day() {
        return today();
    }

    boolean hasPayments() {
        return number("SELECT count(*) FROM core.v_payment") > 0;
    }

    Optional<String> latestChargeEvent() {
        return read.sql("""
                SELECT event_id FROM core.v_event WHERE event_type = 'charge.succeeded'
                ORDER BY received_at DESC, event_id DESC LIMIT 1
                """).query(String.class).optional();
    }

    /** Settled payments with the money still free to refund, the freest first (refunds in flight count as used). */
    List<Refundable> refundable() {
        return read.sql("""
                SELECT p.processor_payment_ref,
                       p.amount - coalesce((SELECT sum(r.amount) FROM core.v_refund r
                                            WHERE r.payment_id = p.id AND r.status NOT IN ('FAILED', 'CANCELLED')), 0)
                FROM core.v_payment p WHERE p.status = 'SETTLED'
                ORDER BY 2 DESC, p.recorded_at DESC
                """)
                .query((rs, row) -> new Refundable(rs.getString(1), rs.getLong(2)))
                .list();
    }

    private long number(String sql) {
        return read.sql(sql).query(Long.class).single();
    }
}
