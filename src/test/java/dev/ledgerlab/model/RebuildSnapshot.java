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
        Set<Issue> issues) {
    record Payment(String status, long amount) {}

    record Outcome(String status, String result) {}

    record Posting(int account, String side, long amount) {}

    record Entry(String type, Map<Posting, Long> postings) {}

    record Issue(String category, String subjectType, String subjectId) {}

    static RebuildSnapshot read(ModelApplication app) {
        JdbcClient jdbc = app.jdbc;
        Map<String, Payment> payments = new TreeMap<>();
        jdbc.sql("SELECT processor_payment_ref, status, amount FROM core.payment")
                .query((rs, n) -> Map.entry(rs.getString(1), new Payment(rs.getString(2), rs.getLong(3))))
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
        Set<Issue> issues = new HashSet<>(jdbc.sql("SELECT category, subject_type, subject_id FROM recon.issue")
                .query((rs, n) -> new Issue(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list());
        return new RebuildSnapshot(app.balances(), payments, inbox, entries, issues);
    }

    void assertMatches(RebuildSnapshot replay) {
        List<String> differences = new ArrayList<>();
        compare("balances", balances, replay.balances, differences);
        compare("payments", payments, replay.payments, differences);
        compare("inbox", inbox, replay.inbox, differences);
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
