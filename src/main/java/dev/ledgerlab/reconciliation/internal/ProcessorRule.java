package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.shared.BusinessCalendar;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ProcessorRule implements ReconRule {
    private final JdbcClient jdbc;
    private final BusinessCalendar calendar;
    private final ReconJson json;
    private final ObjectMapper mapper;

    public ProcessorRule(JdbcClient jdbc, BusinessCalendar calendar, ReconJson json, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.json = json;
        this.mapper = mapper;
    }

    @Override
    public RunRequest.Kind kind() {
        return RunRequest.Kind.PROCESSOR;
    }

    @Override
    public String version() {
        return "processor-v1";
    }

    @Override
    public List<Finding> evaluate(Context context) {
        return inspect(context).findings;
    }

    @Override
    public Map<Finding.Key, Explanation> checkedScope(Context context) {
        return inspect(context).scope;
    }

    private record Payment(
            String id,
            String ref,
            long gross,
            long expectedFee,
            Instant occurred,
            @Nullable String batch,
            @Nullable Long fee,
            String status) {}

    private record Line(
            String file,
            int seq,
            String id,
            String type,
            String ref,
            String batch,
            long gross,
            long fee,
            long net,
            Instant occurred) {}

    private record Batch(String id, long net) {}

    private final class Results {
        final List<Finding> findings = new ArrayList<>();
        final Map<Finding.Key, Explanation> scope = new LinkedHashMap<>();

        void check(
                String category,
                String type,
                String id,
                boolean different,
                Object expected,
                Object actual,
                List<Map<String, Object>> refs,
                @Nullable Instant due,
                String explanation) {
            var key = new Finding.Key(category, type, id);
            scope.put(key, new Explanation(explanation, json.write(refs)));
            if (different)
                findings.add(new Finding(
                        category, type, id, json.write(expected), json.write(actual), json.write(refs), due));
        }
    }

    private Results inspect(Context context) {
        Results result = new Results();
        Instant cutoff = context.request().cutoffAt();
        var asOf = context.request().evidenceAsOf().atOffset(ZoneOffset.UTC);
        // Current mutable status/fee/batch are deliberately not used for historical runs.
        List<Payment> payments = jdbc.sql("""
                SELECT p.id, p.processor_payment_ref, p.amount, p.expected_fee, p.occurred_at,
                    s.batch_id, s.fee, h.to_status
                FROM core.payment p
                JOIN LATERAL (SELECT to_status FROM core.payment_transition WHERE subject_id=p.id
                    AND recorded_at < :asOf ORDER BY recorded_at DESC, id DESC LIMIT 1) h ON true
                LEFT JOIN core.settlement_batch_line s ON s.line_type='PAYMENT'
                    AND s.processor_ref=p.processor_payment_ref AND s.recorded_at < :asOf
                WHERE p.recorded_at < :asOf ORDER BY p.id
                """)
                .param("asOf", asOf)
                .query((rs, n) -> new Payment(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getObject(5, OffsetDateTime.class).toInstant(),
                        rs.getString(6),
                        rs.getObject(7, Long.class),
                        rs.getString(8)))
                .list();
        Map<String, Payment> byRef = new LinkedHashMap<>();
        payments.forEach(p -> byRef.put(p.ref(), p));
        List<Batch> batches = jdbc.sql("""
                SELECT b.batch_id, b.net FROM core.settlement_batch b
                WHERE b.recorded_at < :asOf AND b.occurred_at < :cutoff
                  AND EXISTS (SELECT 1 FROM core.batch_transition t WHERE t.subject_id=b.batch_id
                    AND t.to_status='CONFIRMED' AND t.recorded_at < :asOf)
                ORDER BY b.batch_id
                """)
                .param("asOf", asOf)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Batch(rs.getString(1), rs.getLong(2)))
                .list();
        List<Line> lines = lines(context, asOf);
        Map<String, List<Line>> identities = new LinkedHashMap<>();
        lines.forEach(l ->
                identities.computeIfAbsent(l.id(), ignored -> new ArrayList<>()).add(l));
        List<Line> unique = identities.values().stream().map(List::getFirst).toList();
        Map<String, String> coveredBatches = coveredBatches(context, asOf);
        Map<String, List<String>> entries = new LinkedHashMap<>();
        jdbc.sql("SELECT subject_id, id FROM ledger.journal_entry WHERE recorded_at < ? ORDER BY id")
                .param(asOf)
                .query((rs, n) -> entries.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                        .add(rs.getString(2)))
                .list();

        var checkedPayments = new HashSet<String>();
        for (var group : identities.values()) {
            Line line = group.getFirst();
            if (line.type().equals("REFUND")) continue;
            Payment payment = byRef.get(line.ref());
            boolean inScope = payment == null
                    ? line.occurred().isBefore(cutoff)
                    : payment.occurred().isBefore(cutoff);
            if (!inScope) continue;
            var refs = references(payment, group, entries);
            result.check(
                    "DUPLICATE_STATEMENT_LINE",
                    "statement_line",
                    line.id(),
                    group.size() > 1,
                    Map.of("count", 1, "statement_line_id", line.id()),
                    Map.of("count", group.size(), "statement_line_id", line.id()),
                    refs,
                    null,
                    "Latest snapshot contains one occurrence of " + line.id());
            if (!line.type().equals("PAYMENT") || !checkedPayments.add(line.ref())) continue;
            refs = references(
                    payment,
                    lines.stream()
                            .filter(l -> l.type().equals("PAYMENT") && l.ref().equals(line.ref()))
                            .toList(),
                    entries);
            result.check(
                    "MISSING_IN_LEDGER",
                    "processor_payment",
                    line.ref(),
                    payment == null,
                    Map.of("processor_ref", line.ref(), "present", true),
                    Map.of("processor_ref", line.ref(), "present", payment != null),
                    refs,
                    null,
                    "Local payment is present for " + line.ref());
        }

        for (Payment p : payments) {
            if (!p.occurred().isBefore(cutoff) || p.status().equals("VOIDED")) continue;
            List<Line> matched = unique.stream()
                    .filter(l -> l.type().equals("PAYMENT") && l.ref().equals(p.ref()))
                    .toList();
            var refs = references(p, matched, entries);
            // Multiple different line identities are separate facts; report all amounts as evidence.
            if (!matched.isEmpty()) {
                boolean amountDiff = matched.stream()
                        .anyMatch(l -> l.gross() != p.gross()
                                || (p.fee() != null && l.net() != Math.subtractExact(p.gross(), p.fee())));
                Map<String, Object> expected = new LinkedHashMap<>();
                expected.put("payment_id", p.id());
                expected.put("gross", p.gross());
                if (p.fee() != null) expected.put("net", Math.subtractExact(p.gross(), p.fee()));
                result.check(
                        "AMOUNT_MISMATCH",
                        "payment",
                        p.id(),
                        amountDiff,
                        expected,
                        matched.stream()
                                .map(l -> Map.of(
                                        "statement_line_id",
                                        l.id(),
                                        "batch_id",
                                        l.batch(),
                                        "gross",
                                        l.gross(),
                                        "net",
                                        l.net()))
                                .toList(),
                        refs,
                        null,
                        "Statement gross and net match local payment");
                result.check(
                        "FEE_MISMATCH",
                        "payment",
                        p.id(),
                        matched.stream().anyMatch(l -> l.fee() != p.expectedFee()),
                        Map.of("payment_id", p.id(), "fee", p.expectedFee()),
                        Map.of(
                                "payment_id",
                                p.id(),
                                "fees",
                                matched.stream()
                                        .map(l -> Map.of("statement_line_id", l.id(), "fee", l.fee()))
                                        .toList()),
                        refs,
                        null,
                        "Statement fees match contractual expected_fee");
            }
            if (p.batch() != null && p.status().equals("SETTLED") && coveredBatches.containsKey(p.batch())) {
                refs.add(Map.of("file_id", coveredBatches.get(p.batch()), "batch_id", p.batch()));
                boolean absent = matched.stream().noneMatch(l -> l.batch().equals(p.batch()));
                result.check(
                        "MISSING_IN_STATEMENT",
                        "payment",
                        p.id(),
                        absent,
                        Map.of("payment_id", p.id(), "batch_id", p.batch(), "present", true),
                        Map.of("present", !absent),
                        refs,
                        null,
                        "Payment appears in snapshot for its local batch " + p.batch());
            }
            Instant due = calendar.dueAt(p.occurred(), 2);
            // Expired missing obligations are owned by T11; do not silently explain them here.
            if (!matched.isEmpty() || (p.status().equals("CAPTURED") && due.isAfter(cutoff))) {
                result.check(
                        "TIMING_DIFFERENCE",
                        "payment",
                        p.id(),
                        matched.isEmpty() && due.isAfter(cutoff),
                        Map.of("payment_id", p.id(), "present", true),
                        Map.of("present", !matched.isEmpty()),
                        refs,
                        matched.isEmpty() && due.isAfter(cutoff) ? due : null,
                        "Payment explained by processor snapshot "
                                + matched.stream().map(Line::batch).distinct().toList());
            }
        }
        for (Batch b : batches) {
            if (!coveredBatches.containsKey(b.id())) continue;
            List<Line> batchLines =
                    unique.stream().filter(l -> l.batch().equals(b.id())).toList();
            // A mixed refund batch cannot be compared correctly before P3 refund support.
            if (batchLines.stream().anyMatch(l -> l.type().equals("REFUND"))) continue;
            long total = 0;
            for (Line l : batchLines) total = Math.addExact(total, l.net());
            if (total <= -9007199254740992L || total >= 9007199254740992L)
                throw new ArithmeticException("Statement total out of range");
            var refs = references(null, batchLines, entries);
            refs.add(Map.of(
                    "batch_id",
                    b.id(),
                    "file_id",
                    coveredBatches.get(b.id()),
                    "entry_ids",
                    entries.getOrDefault(b.id(), List.of())));
            result.check(
                    "BATCH_TOTAL_MISMATCH",
                    "batch",
                    b.id(),
                    total != b.net(),
                    Map.of("batch_id", b.id(), "net", b.net()),
                    Map.of("batch_id", b.id(), "net", total, "difference", Math.subtractExact(b.net(), total)),
                    refs,
                    null,
                    "Deduplicated statement total matches local batch");
        }
        return result;
    }

    private List<Line> lines(Context context, OffsetDateTime asOf) {
        if (context.inputHashes().isEmpty()) return List.of();
        return jdbc.sql("""
                WITH ranked AS (
                    SELECT l.*, dense_rank() OVER (PARTITION BY l.statement_line_id
                        ORDER BY s.fetched_at DESC, s.recorded_at DESC, s.file_id DESC) AS rank
                    FROM recon.statement_line l JOIN recon.v_processor_snapshot s ON s.file_id=l.file_id
                    WHERE s.file_id IN (:files) AND s.fetched_at < :asOf AND s.recorded_at < :asOf AND l.recorded_at < :asOf)
                SELECT * FROM ranked WHERE rank=1 ORDER BY statement_line_id, line_seq
                """)
                .param("files", context.inputHashes().keySet())
                .param("asOf", asOf)
                .query((rs, n) -> new Line(
                        rs.getString("file_id"),
                        rs.getInt("line_seq"),
                        rs.getString("statement_line_id"),
                        rs.getString("line_type"),
                        rs.getString("processor_ref"),
                        rs.getString("batch_id"),
                        rs.getLong("gross"),
                        rs.getLong("fee"),
                        rs.getLong("net"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private Map<String, String> coveredBatches(Context context, OffsetDateTime asOf) {
        Map<String, String> result = new LinkedHashMap<>();
        if (context.inputHashes().isEmpty()) return result;
        jdbc.sql("""
                SELECT file_id, payouts FROM recon.v_processor_snapshot
                WHERE file_id IN (:files) AND fetched_at < :asOf AND recorded_at < :asOf
                ORDER BY fetched_at, recorded_at, file_id
                """)
                .param("files", context.inputHashes().keySet())
                .param("asOf", asOf)
                .query((rs, n) -> {
                    for (var payout : mapper.readTree(rs.getString(2)))
                        result.put(payout.path("id").asText(), rs.getString(1));
                    return 0;
                })
                .list();
        return result;
    }

    private List<Map<String, Object>> references(
            @Nullable Payment payment, List<Line> lines, Map<String, List<String>> entries) {
        List<Map<String, Object>> refs = new ArrayList<>();
        if (payment != null)
            refs.add(Map.of(
                    "payment_id",
                    payment.id(),
                    "processor_ref",
                    payment.ref(),
                    "entry_ids",
                    entries.getOrDefault(payment.id(), List.of())));
        for (Line l : lines)
            refs.add(Map.of(
                    "file_id",
                    l.file(),
                    "line_seq",
                    l.seq(),
                    "statement_line_id",
                    l.id(),
                    "batch_id",
                    l.batch(),
                    "processor_ref",
                    l.ref(),
                    "entry_ids",
                    entries.getOrDefault(l.batch(), List.of())));
        if (payment != null && payment.batch() != null)
            refs.add(Map.of(
                    "batch_id",
                    Objects.requireNonNull(payment.batch()),
                    "entry_ids",
                    entries.getOrDefault(payment.batch(), List.of())));
        return refs;
    }
}
