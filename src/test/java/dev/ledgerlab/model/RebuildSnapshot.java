package dev.ledgerlab.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Whole-database comparison: no local IDs, row ordering or reference-model expectations. */
record RebuildSnapshot(
        Map<Integer, Long> balances,
        Map<String, Payment> payments,
        Map<String, Outcome> inbox,
        Map<String, List<Entry>> entries,
        Set<Issue> issues,
        Map<String, Map<String, Object>> batches,
        Map<String, Map<String, Object>> batchLines,
        Map<String, Map<String, Object>> bankLines,
        Map<String, Map<String, Object>> refunds,
        Map<String, Map<String, Object>> refundHistory,
        Map<String, Map<String, Object>> commands,
        Map<String, Map<String, Object>> idempotency) {
    RebuildSnapshot(
            Map<Integer, Long> balances,
            Map<String, Payment> payments,
            Map<String, Outcome> inbox,
            Map<String, List<Entry>> entries,
            Set<Issue> issues,
            Map<String, Map<String, Object>> batches,
            Map<String, Map<String, Object>> batchLines,
            Map<String, Map<String, Object>> bankLines) {
        this(
                balances,
                payments,
                inbox,
                entries,
                issues,
                batches,
                batchLines,
                bankLines,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    RebuildSnapshot(
            Map<Integer, Long> balances,
            Map<String, Payment> payments,
            Map<String, Outcome> inbox,
            Map<String, List<Entry>> entries,
            Set<Issue> issues) {
        this(balances, payments, inbox, entries, issues, Map.of(), Map.of(), Map.of());
    }

    record Payment(String status, long amount, String batch, Long actualFee) {
        Payment(String status, long amount) {
            this(status, amount, null, null);
        }
    }

    record Outcome(String status, String result) {}

    record Posting(int account, String side, long amount) {}

    record Entry(String type, Map<Posting, Long> postings) {}

    record Issue(String category, String subjectType, String subjectId, String status) {
        Issue(String category, String subjectType, String subjectId) {
            this(category, subjectType, subjectId, "OPEN");
        }
    }

    static RebuildSnapshot read(ModelApplication app) {
        JdbcClient jdbc = app.jdbc;
        Map<String, Payment> payments = new TreeMap<>();
        jdbc.sql("SELECT processor_payment_ref, status, amount, batch_id, actual_fee FROM core.payment")
                .query((rs, n) ->
                        Map.entry(rs.getString(1), new Payment(rs.getString(2), rs.getLong(3), rs.getString(4), (Long)
                                rs.getObject(5))))
                .list()
                .forEach(e -> payments.put(e.getKey(), e.getValue()));
        Map<String, Outcome> inbox = new TreeMap<>();
        jdbc.sql("SELECT event_id, status, result FROM io.inbox_event")
                .query((rs, n) -> Map.entry(rs.getString(1), new Outcome(rs.getString(2), rs.getString(3))))
                .list()
                .forEach(e -> inbox.put(e.getKey(), e.getValue()));
        Map<String, List<Entry>> entries = new TreeMap<>();
        for (var row : jdbc.sql("SELECT id, source_key, entry_type FROM ledger.journal_entry")
                .query()
                .listOfRows()) {
            Map<Posting, Long> postings = new HashMap<>();
            jdbc.sql("SELECT account_code, side, amount FROM ledger.posting WHERE entry_id=:id")
                    .param("id", row.get("id"))
                    .query((rs, n) -> new Posting(rs.getInt(1), rs.getString(2), rs.getLong(3)))
                    .list()
                    .forEach(p -> postings.merge(p, 1L, Long::sum));
            entries.computeIfAbsent((String) row.get("source_key"), ignored -> new ArrayList<>())
                    .add(new Entry((String) row.get("entry_type"), Map.copyOf(postings)));
        }
        Set<Issue> issues = new HashSet<>(jdbc.sql("SELECT category, subject_type, subject_id, status FROM recon.issue")
                .query((rs, n) -> new Issue(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list());
        return new RebuildSnapshot(
                app.balances(),
                payments,
                inbox,
                entries,
                issues,
                rows(jdbc, """
                        SELECT batch_id AS external_key, status, hold_reason, payout_amount, gross, fees, refunds,
                        payout_failures_returned, net, currency, automatic, method, settlement_date, arrival_date
                        FROM core.settlement_batch
                        """),
                rows(jdbc, """
                        SELECT balance_txn_id AS external_key, batch_id, line_type, processor_ref, gross, fee, net
                        FROM core.settlement_batch_line
                        """),
                rows(jdbc, """
                        SELECT bank_txn_id || ':' || version AS external_key, direction, currency, status, amount,
                        reference, booking_date, value_date, memo, match_status, matched_batch_id, wait_until
                        FROM recon.bank_line
                        """),
                rows(jdbc, """
                        SELECT p.processor_payment_ref || ':' || r.merchant_refund_ref AS external_key,
                            r.amount, r.status, r.processor_refund_ref, r.batch_id, r.failure_reason,
                            r.cancel_reason, r.initial_result::text, r.accepted_at, r.submitted_at, r.resolved_at,
                            p.refunded_amount
                        FROM core.refund r JOIN core.payment p ON p.id=r.payment_id
                        """),
                rows(jdbc, """
                        SELECT p.processor_payment_ref || ':' || r.merchant_refund_ref || ':' ||
                            row_number() OVER (PARTITION BY r.id ORDER BY t.id) AS external_key,
                            t.from_status,t.to_status,t.evidence_source,t.evidence_ref,t.occurred_at
                        FROM core.refund_transition t JOIN core.refund r ON r.id=t.subject_id
                            JOIN core.payment p ON p.id=r.payment_id
                        """),
                rows(jdbc, """
                        SELECT p.processor_payment_ref || ':' || r.merchant_refund_ref AS external_key,
                            c.status,c.kind,c.external_ref,c.first_sent_at,c.last_outcome
                        FROM io.outbound_command c JOIN core.refund r ON r.id=c.subject_id
                            JOIN core.payment p ON p.id=r.payment_id
                        """),
                rows(jdbc, """
                        SELECT client_id || ':' || idem_key AS external_key, request_fingerprint,state,
                            response_status,response_body::text,created_at,expires_at
                        FROM io.idempotency_record
                        """));
    }

    private static Map<String, Map<String, Object>> rows(JdbcClient jdbc, String sql) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (var row : jdbc.sql(sql).query().listOfRows()) {
            Map<String, Object> value = new HashMap<>(row);
            String key = (String) value.remove("external_key");
            if (result.put(key, value) != null) throw new AssertionError("Repeated external identity " + key);
        }
        return result;
    }

    void assertMatches(RebuildSnapshot replay) {
        List<String> differences = new ArrayList<>();
        compare("balances", balances, replay.balances, differences);
        compare("payments", payments, replay.payments, differences);
        compare("inbox", inbox, replay.inbox, differences);
        compare("batches", batches, replay.batches, differences);
        compare("batchLines", batchLines, replay.batchLines, differences);
        compare("bankLines", bankLines, replay.bankLines, differences);
        compare("refunds", refunds, replay.refunds, differences);
        compare("refundHistory", refundHistory, replay.refundHistory, differences);
        compare("commands", commands, replay.commands, differences);
        compare("idempotency", idempotency, replay.idempotency, differences);
        // Entry order and posting order are irrelevant; multiplicity is retained at both levels.
        compare("entries", entryBags(entries), entryBags(replay.entries), differences);
        Set<Issue> missing = new HashSet<>(issues);
        missing.removeAll(replay.issues);
        Set<Issue> extra = new HashSet<>(replay.issues);
        extra.removeAll(issues);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            differences.add("issues: original-only=" + missing + ", replay-only=" + extra);
        }
        if (!differences.isEmpty())
            throw new AssertionError("AC-4.13 rebuild differences:\n" + String.join("\n", differences));
    }

    private static Map<String, Map<Entry, Long>> entryBags(Map<String, List<Entry>> entries) {
        Map<String, Map<Entry, Long>> result = new TreeMap<>();
        entries.forEach((key, values) -> {
            Map<Entry, Long> bag = new HashMap<>();
            values.forEach(value -> bag.merge(value, 1L, Long::sum));
            result.put(key, bag);
        });
        return result;
    }

    private static <K, V> void compare(String label, Map<K, V> original, Map<K, V> replay, List<String> differences) {
        Set<K> keys = new HashSet<>(original.keySet());
        keys.addAll(replay.keySet());
        keys.stream().sorted(java.util.Comparator.comparing(Object::toString)).forEach(key -> {
            if (!Objects.equals(original.get(key), replay.get(key))) {
                differences.add(label + "[" + key + "]: original=" + original.get(key) + ", replay=" + replay.get(key));
            }
        });
    }
}
