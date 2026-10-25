package dev.ledgerlab.investigation.internal;

import static dev.ledgerlab.investigation.internal.QueryModels.Finding;
import static dev.ledgerlab.investigation.internal.QueryModels.Issue;
import static dev.ledgerlab.investigation.internal.QueryModels.IssueTransition;
import static dev.ledgerlab.investigation.internal.QueryModels.statementLine;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Navigation includes current related objects and references retained by every historical finding. */
final class IssueReferences {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Set<String> batches = new TreeSet<>();
    private final Set<String> banks = new TreeSet<>();
    private final Set<String> payments = new TreeSet<>();
    private final Set<String> refunds = new TreeSet<>();
    private final Set<String> processorRefs = new TreeSet<>();
    private final Set<String> statements = new TreeSet<>();
    private final Set<String> events = new TreeSet<>();
    private final Set<String> entries = new TreeSet<>();

    IssueReferences(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record StatementReference(String fileId, int lineSeq, String statementLineId) {}

    record References(
            List<String> batchIds,
            List<StatementReference> statementLines,
            List<String> eventIds,
            List<String> entryIds,
            List<String> bankTxnIds,
            List<String> paymentIds,
            List<String> refundIds) {}

    References resolve(Issue issue, List<Finding> findings, List<IssueTransition> history) {
        switch (issue.subjectType()) {
            case "batch" -> batches.add(issue.subjectId());
            case "payment" -> {
                payments.add(issue.subjectId());
                processorRefs.add(issue.subjectId());
            }
            case "bank_txn" -> banks.add(issue.subjectId());
            case "statement_line" -> statements.add(issue.subjectId());
            case "processor_payment" -> processorRefs.add(issue.subjectId());
            case "event" -> events.add(issue.subjectId());
            case "refund" -> refunds.add(issue.subjectId());
            case "processor_refund" -> processorRefs.add(issue.subjectId());
            default -> {
                /* Other subjects can still have explicit evidence references. */
            }
        }
        collect(issue.expected());
        collect(issue.actual());
        collect(issue.evidenceRefs());
        findings.forEach(f -> {
            collect(f.expected());
            collect(f.actual());
            collect(f.evidenceRefs());
        });
        history.forEach(t -> collect(t.evidenceRefs()));
        refundLinks();
        paymentLinks();
        var lines = jdbc.sql("""
                SELECT * FROM recon.v_statement_line
                WHERE batch_id IN (:batches) OR statement_line_id IN (:statements) OR processor_ref IN (:refs)
                ORDER BY file_id, line_seq
                """)
                .param("batches", bound(batches))
                .param("statements", bound(statements))
                .param("refs", bound(processorRefs))
                .query(statementLine(json))
                .list();
        lines.forEach(l -> {
            batches.add(l.batchId());
            processorRefs.add(l.processorRef());
        });
        refundLinks();
        paymentLinks();
        jdbc.sql("""
                SELECT batch_id, source_event_id FROM core.v_settlement_batch WHERE batch_id IN (:ids)
                """)
                .param("ids", bound(batches))
                .query((rs, row) -> {
                    events.add(rs.getString("source_event_id"));
                    return rs.getString("batch_id");
                })
                .list();
        jdbc.sql("""
                SELECT evidence_id FROM core.v_batch_transition
                WHERE subject_id IN (:ids) AND evidence_type='inbox_event'
                """).param("ids", bound(batches)).query(String.class).list().forEach(events::add);
        jdbc.sql("""
                SELECT bank_txn_id FROM recon.v_bank_line
                WHERE matched_batch_id IN (:ids) OR reference IN (:ids)
                """).param("ids", bound(batches)).query(String.class).list().forEach(banks::add);
        jdbc.sql("""
                SELECT id FROM ledger.v_journal_entry WHERE
                  (subject_type='payment' AND subject_id IN (:payments)) OR
                  (subject_type='refund' AND subject_id IN (:refunds)) OR
                  (subject_type='batch' AND subject_id IN (:batches)) OR
                  (subject_type='bank_txn' AND subject_id IN (:banks))
                """)
                .param("payments", bound(payments))
                .param("refunds", bound(refunds))
                .param("batches", bound(batches))
                .param("banks", bound(banks))
                .query(String.class)
                .list()
                .forEach(entries::add);
        return new References(
                List.copyOf(batches),
                lines.stream()
                        .map(l -> new StatementReference(l.fileId(), l.lineSeq(), l.statementLineId()))
                        .toList(),
                List.copyOf(events),
                List.copyOf(entries),
                List.copyOf(banks),
                List.copyOf(payments),
                List.copyOf(refunds));
    }

    private void refundLinks() {
        jdbc.sql("""
                SELECT id, payment_id, processor_refund_ref FROM core.v_refund
                WHERE id IN (:ids) OR processor_refund_ref IN (:refs)
                """)
                .param("ids", bound(refunds))
                .param("refs", bound(processorRefs))
                .query((rs, row) -> {
                    refunds.add(rs.getString("id"));
                    payments.add(rs.getString("payment_id"));
                    String ref = rs.getString("processor_refund_ref");
                    if (ref != null) processorRefs.add(ref);
                    return rs.getString("id");
                })
                .list();
        jdbc.sql("""
                SELECT evidence_ref FROM core.v_refund_transition
                WHERE subject_id IN (:ids) AND evidence_source='WEBHOOK'
                """).param("ids", bound(refunds)).query(String.class).list().forEach(events::add);
    }

    private void paymentLinks() {
        jdbc.sql("""
                SELECT id, processor_payment_ref, batch_id, source_event_id FROM core.v_payment
                WHERE id IN (:ids) OR processor_payment_ref IN (:refs) OR batch_id IN (:batches)
                """)
                .param("ids", bound(payments))
                .param("refs", bound(processorRefs))
                .param("batches", bound(batches))
                .query((rs, row) -> {
                    payments.add(rs.getString("id"));
                    processorRefs.add(rs.getString("processor_payment_ref"));
                    events.add(rs.getString("source_event_id"));
                    String batch = rs.getString("batch_id");
                    if (batch != null) batches.add(batch);
                    return rs.getString("id");
                })
                .list();
    }

    private void collect(JsonNode node) {
        if (node.isArray()) {
            node.forEach(this::collect);
            return;
        }
        if (!node.isObject()) return;
        if (node.path("id").isTextual() && !node.path("id").asString().isBlank()) {
            String id = node.path("id").asString();
            switch (node.path("table").asString("")) {
                case "core.settlement_batch" -> batches.add(id);
                case "core.payment" -> payments.add(id);
                case "core.refund" -> refunds.add(id);
                case "io.inbox_event" -> events.add(id);
                case "ledger.journal_entry" -> entries.add(id);
                default -> {}
            }
        }
        node.properties().forEach(e -> {
            Set<String> target = switch (e.getKey()) {
                case "batch_id", "candidate_batch_id" -> batches;
                case "bank_txn_id" -> banks;
                case "payment_id" -> payments;
                case "refund_id" -> refunds;
                case "processor_ref", "processor_payment_ref", "processor_refund_ref" -> processorRefs;
                case "statement_line_id" -> statements;
                case "event_id", "source_event_id" -> events;
                case "entry_id", "entry_ids" -> entries;
                default -> null;
            };
            if (target != null) {
                if (e.getValue().isTextual() && !e.getValue().asString().isBlank())
                    target.add(e.getValue().asString());
                else if (e.getValue().isArray())
                    e.getValue().forEach(v -> {
                        if (v.isTextual() && !v.asString().isBlank()) target.add(v.asString());
                    });
            }
            collect(e.getValue());
        });
    }

    private static Set<String> bound(Set<String> ids) {
        // All stored object identifiers are nonempty; a sentinel keeps IN bindings valid for empty sets.
        return ids.isEmpty() ? Set.of("") : Set.copyOf(ids);
    }
}
