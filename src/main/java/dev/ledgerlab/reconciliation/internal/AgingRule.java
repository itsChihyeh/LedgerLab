package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.shared.BusinessCalendar;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class AgingRule implements ReconRule {
    static final String LIMITATIONS = "老化只能发现有开始记录、没有结束记录的义务；"
            + "其他 HELD 原因由入口差异负责，等 P4 纠错后重核；回填 inbox 历史之前的状态无法重建；"
            + "旧 HELD 历史缺少原因或明细存档时无法重建，未纳入检查；退款、提现老化留给 P3/P5。";
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;
    private final AgingPolicy policy;
    private final BankEvidence bank;
    private final ReconJson json;
    private final ObjectMapper mapper;

    public AgingRule(
            JdbcClient jdbc,
            BusinessCalendar calendar,
            AgingPolicy policy,
            BankEvidence bank,
            ReconJson json,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.policy = policy;
        this.bank = bank;
        this.json = json;
        this.mapper = mapper;
    }

    @Override
    public RunRequest.Kind kind() {
        return RunRequest.Kind.AGING;
    }

    @Override
    public String version() {
        return "p3-v1";
    }

    @Override
    public List<Finding> evaluate(Context context) {
        return inspect(context).findings;
    }

    @Override
    public Map<Finding.Key, Explanation> checkedScope(Context context) {
        return inspect(context).scope;
    }

    private RuleResults inspect(Context context) {
        var result = new RuleResults(json);
        var asOf = context.request().evidenceAsOf().atOffset(ZoneOffset.UTC);
        Instant cutoff = context.request().cutoffAt();
        jdbc.sql("""
                SELECT p.id, p.processor_payment_ref, p.occurred_at, h.to_status, h.id AS transition_id
                FROM core.payment p JOIN LATERAL (
                    SELECT to_status, id FROM core.payment_transition WHERE subject_id=p.id AND recorded_at < :asOf
                    ORDER BY recorded_at DESC, id DESC LIMIT 1) h ON true
                WHERE p.recorded_at < :asOf AND p.occurred_at < :cutoff ORDER BY p.id
                """)
                .param("asOf", asOf)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> {
                    Instant start =
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
                    boolean overdue = rs.getString("to_status").equals("CAPTURED")
                            && !calendar.dueAt(start, policy.paymentBusinessDays())
                                    .isAfter(cutoff);
                    check(
                            result,
                            "PAYMENT_NOT_SETTLED",
                            "payment",
                            rs.getString("id"),
                            start,
                            policy.paymentBusinessDays(),
                            cutoff,
                            overdue,
                            List.of(Map.of(
                                    "payment_id",
                                    rs.getString("id"),
                                    "processor_ref",
                                    rs.getString("processor_payment_ref"),
                                    "transition_id",
                                    rs.getLong("transition_id"))),
                            rs.getString("to_status"));
                    return 0;
                })
                .list();
        var snapshot = bank.read(context);
        for (var batch : snapshot.batches()) {
            boolean missing = !batch.status().equals("PAYOUT_FAILED") && !snapshot.matched(batch);
            check(
                    result,
                    "DEPOSIT_NOT_RECEIVED",
                    "batch",
                    batch.id(),
                    batch.occurred(),
                    policy.depositBusinessDays(),
                    cutoff,
                    missing
                            && !calendar.dueAt(batch.occurred(), policy.depositBusinessDays())
                                    .isAfter(cutoff),
                    snapshot.refs(batch),
                    missing ? "UNRECEIVED" : batch.status());
        }
        held(context, result);
        parked(context, result);
        return result;
    }

    private void parked(Context context, RuleResults result) {
        Instant asOf = context.request().evidenceAsOf();
        // Inbox age is elapsed time since receipt, independent of the business-day cutoff.
        // Include completed events in scope so later evidence can explain an old aging issue.
        jdbc.sql("""
                SELECT e.event_id, e.received_at, h.id, h.to_status, h.parked_on
                FROM io.inbox_event e JOIN LATERAL (
                    SELECT id, to_status, parked_on FROM io.inbox_status_history
                    WHERE event_id=e.event_id AND recorded_at < :asOf
                    ORDER BY recorded_at DESC, id DESC LIMIT 1
                ) h ON true
                WHERE e.recorded_at < :asOf AND e.received_at < :asOf ORDER BY e.event_id
                """)
                .param("asOf", asOf.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> {
                    Instant start =
                            rs.getObject("received_at", OffsetDateTime.class).toInstant();
                    Instant due = start.plus(policy.parkedEvent());
                    String status = rs.getString("to_status");
                    var expected = new LinkedHashMap<String, Object>();
                    expected.put("start_at", start.toString());
                    expected.put("threshold", policy.parkedEvent().toString());
                    expected.put("due_at", due.toString());
                    expected.put("parked_on", rs.getString("parked_on"));
                    result.check(
                            "EVENT_PARKED_AGED",
                            "event",
                            rs.getString("event_id"),
                            status.equals("PARKED") && !due.isAfter(asOf),
                            expected,
                            Map.of("status", status),
                            List.of(
                                    Map.of("table", "io.inbox_event", "id", rs.getString("event_id")),
                                    Map.of("table", "io.inbox_status_history", "id", rs.getLong("id"))),
                            null,
                            "Inbox event no longer PARKED or not yet due");
                    return 0;
                })
                .list();
    }

    private void check(
            RuleResults result,
            String category,
            String type,
            String id,
            Instant start,
            int days,
            Instant cutoff,
            boolean overdue,
            Object refs,
            String status) {
        Instant due = calendar.dueAt(start, days);
        result.check(
                category,
                type,
                id,
                overdue,
                expected(start, days, cutoff),
                Map.of("status", status),
                refs,
                null,
                "Obligation completed or not yet due: " + category);
        // Only an actual aging finding authorizes cross-kind timing escalation.
        if (overdue && !category.equals("MISSING_IN_LEDGER"))
            result.check(
                    "TIMING_DIFFERENCE",
                    type,
                    id,
                    false,
                    Map.of(),
                    Map.of(),
                    refs,
                    null,
                    "已升级为 " + category + "，到期时刻 " + due);
    }

    private Map<String, Object> expected(Instant start, int days, Instant cutoff) {
        int elapsed = 0;
        var end = calendar.businessDateOf(cutoff);
        for (var date = calendar.businessDateOf(start).plusDays(1); date.isBefore(end); date = date.plusDays(1))
            if (calendar.isBusinessDay(date)) elapsed = Math.incrementExact(elapsed);
        return Map.of(
                "start_at",
                start.toString(),
                "threshold_business_days",
                days,
                "due_at",
                calendar.dueAt(start, days).toString(),
                "elapsed_business_days",
                elapsed,
                "description",
                "已过 " + elapsed + " 个工作日");
    }

    private record Held(String batch, Instant start, String status, String reason, long fetch, String body) {}

    private void held(Context context, RuleResults result) {
        var asOf = context.request().evidenceAsOf().atOffset(ZoneOffset.UTC);
        var cutoff = context.request().cutoffAt();
        // Keep the last UNKNOWN_PAYMENT evidence even after recovery, to explain its old subjects.
        var held = jdbc.sql("""
                SELECT b.batch_id, b.occurred_at, current.to_status, current.hold_reason,
                    old.lines_fetch_id, f.response_body
                FROM core.settlement_batch b
                JOIN LATERAL (SELECT to_status, hold_reason FROM core.batch_transition
                    WHERE subject_id=b.batch_id AND recorded_at < :asOf
                    ORDER BY recorded_at DESC, id DESC LIMIT 1) current ON true
                JOIN LATERAL (SELECT lines_fetch_id FROM core.batch_transition
                    WHERE subject_id=b.batch_id AND recorded_at < :asOf AND hold_reason='UNKNOWN_PAYMENT'
                    ORDER BY recorded_at DESC, id DESC LIMIT 1) old ON true
                JOIN io.processor_fetch f ON f.id=old.lines_fetch_id AND f.complete AND f.purpose='BATCH_LINES'
                    AND f.fetched_at < :asOf AND f.recorded_at < :asOf
                WHERE b.recorded_at < :asOf AND b.occurred_at < :cutoff ORDER BY b.occurred_at, b.batch_id
                """)
                .param("asOf", asOf)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Held(
                        rs.getString(1),
                        rs.getObject(2, OffsetDateTime.class).toInstant(),
                        rs.getString(3),
                        java.util.Objects.toString(rs.getString(4), ""),
                        rs.getLong(5),
                        rs.getString(6)))
                .list();
        Map<String, List<Held>> byPayment = new LinkedHashMap<>();
        for (Held h : held) {
            if (!context.inputHashes().containsKey("processor_fetch:" + h.fetch())) continue;
            for (var page : mapper.readTree(h.body())) {
                for (var line : mapper.readTree(page.asText()).path("data")) {
                    if (!line.path("type").asText().equals("charge")) continue;
                    String ref = line.path("source").asText();
                    var batches = byPayment.computeIfAbsent(ref, ignored -> new ArrayList<>());
                    if (!batches.contains(h)) batches.add(h);
                }
            }
        }
        byPayment.forEach((ref, batches) -> {
            boolean present =
                    jdbc.sql("SELECT count(*) FROM core.payment WHERE processor_payment_ref=? AND recorded_at < ?")
                                    .params(ref, asOf)
                                    .query(Long.class)
                                    .single()
                            != 0;
            var active = batches.stream()
                    .filter(h -> h.status().equals("HELD") && h.reason().equals("UNKNOWN_PAYMENT"))
                    .toList();
            Held first = active.isEmpty() ? batches.getFirst() : active.getFirst();
            boolean overdue = !present
                    && !active.isEmpty()
                    && !calendar.dueAt(first.start(), policy.heldBatchBusinessDays())
                            .isAfter(cutoff);
            check(
                    result,
                    "MISSING_IN_LEDGER",
                    "processor_payment",
                    ref,
                    first.start(),
                    policy.heldBatchBusinessDays(),
                    cutoff,
                    overdue,
                    batches.stream()
                            .map(h -> Map.of("batch_id", h.batch(), "fetch_id", h.fetch(), "processor_ref", ref))
                            .toList(),
                    present ? "PRESENT" : first.status());
        });
    }
}
