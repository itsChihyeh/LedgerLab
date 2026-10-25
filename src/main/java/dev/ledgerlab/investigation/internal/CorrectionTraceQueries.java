package dev.ledgerlab.investigation.internal;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Resolves audit navigation exclusively through the investigation role's views. */
@Service
final class CorrectionTraceQueries {
    private final QuerySnapshot snapshot;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final WorkbenchQueries workbench;

    CorrectionTraceQueries(QuerySnapshot snapshot, ObjectMapper json, WorkbenchQueries workbench) {
        this.snapshot = snapshot;
        this.jdbc = snapshot.jdbc;
        this.json = json;
        this.workbench = workbench;
    }

    record Link(String kind, String id, @Nullable Integer lineSeq) {}

    record Evidence(String type, String id, List<Link> links) {}

    record State(String kind, String id, String status) {}

    record Transition(String kind, String id, @Nullable String fromStatus, String toStatus) {}

    record Trace(List<Evidence> evidence, List<State> objects, List<Transition> history, PaymentQueries.AsOf asOf) {}

    Trace trace(String id) {
        return snapshot.read(asOf -> {
            var evidence = jdbc.sql("SELECT evidence_refs FROM ledger.v_correction WHERE id=?")
                    .param(id)
                    .query((rs, n) -> json.readTree(rs.getString(1)))
                    .optional()
                    .orElseThrow(() -> WorkbenchQueries.missing("Correction"));
            List<Evidence> references = new ArrayList<>();
            evidence.forEach(ref -> references.add(
                    resolve(ref.path("type").asString(), ref.path("id").asString())));
            List<State> objects = new ArrayList<>();
            objects.addAll(jdbc.sql("""
                    SELECT p.id, p.status FROM core.v_payment p JOIN ledger.v_correction c
                    ON c.subject_type='payment' AND c.subject_id=p.id WHERE c.id=?
                    """)
                    .param(id)
                    .query((rs, n) -> new State("payments", rs.getString(1), rs.getString(2)))
                    .list());
            objects.addAll(jdbc.sql("""
                    SELECT b.batch_id, b.status FROM core.v_settlement_batch b WHERE EXISTS
                    (SELECT 1 FROM core.v_batch_transition t WHERE t.subject_id=b.batch_id
                    AND t.evidence_type='correction' AND t.evidence_id=?) ORDER BY b.batch_id
                    """)
                    .param(id)
                    .query((rs, n) -> new State("batches", rs.getString(1), rs.getString(2)))
                    .list());
            objects.addAll(jdbc.sql("""
                    SELECT DISTINCT ON (b.bank_txn_id) b.bank_txn_id, b.match_status FROM recon.v_bank_line b
                    JOIN ledger.v_journal_entry e ON e.subject_type='bank_txn' AND e.subject_id=b.bank_txn_id
                    JOIN ledger.v_correction_target t ON t.entry_id=e.id
                    WHERE t.correction_id=? AND t.role='APPLIED' ORDER BY b.bank_txn_id, b.version DESC
                    """)
                    .param(id)
                    .query((rs, n) -> new State("bank-lines", rs.getString(1), rs.getString(2)))
                    .list());
            objects.addAll(jdbc.sql("SELECT id, status FROM recon.v_issue WHERE resolution_correction_id=? ORDER BY id")
                    .param(id)
                    .query((rs, n) -> new State("recon", rs.getString(1), rs.getString(2)))
                    .list());
            List<Transition> history = new ArrayList<>();
            history.addAll(jdbc.sql("""
                    SELECT subject_id, from_status, to_status FROM core.v_payment_transition
                    WHERE evidence_type='correction' AND evidence_id=? ORDER BY id
                    """)
                    .param(id)
                    .query((rs, n) -> new Transition("payments", rs.getString(1), rs.getString(2), rs.getString(3)))
                    .list());
            history.addAll(jdbc.sql("""
                    SELECT subject_id, from_status, to_status FROM core.v_batch_transition
                    WHERE evidence_type='correction' AND evidence_id=? ORDER BY id
                    """)
                    .param(id)
                    .query((rs, n) -> new Transition("batches", rs.getString(1), rs.getString(2), rs.getString(3)))
                    .list());
            return new Trace(references, objects, history, asOf);
        });
    }

    private Evidence resolve(String type, String id) {
        List<Link> links = switch (type) {
            case "issue" -> List.of(new Link("recon", id, null));
            case "bank_txn" -> List.of(new Link("bank-lines", id, null));
            case "inbox_event" -> List.of(new Link("events", id, null));
            case "statement_line" -> {
                List<Link> found = new ArrayList<>(jdbc.sql("""
                        SELECT file_id, line_seq FROM recon.v_statement_line WHERE statement_line_id=? ORDER BY file_id, line_seq
                        """)
                        .param(id)
                        .query((rs, n) -> new Link("statement-lines", rs.getString(1), rs.getInt(2)))
                        .list());
                found.addAll(jdbc.sql("""
                        SELECT batch_id, line_no FROM core.v_settlement_batch_line WHERE balance_txn_id=? ORDER BY batch_id, line_no
                        """)
                        .param(id)
                        .query((rs, n) -> new Link("batch-lines", rs.getString(1), rs.getInt(2)))
                        .list());
                yield found;
            }
            case "processor_fetch" ->
                jdbc.sql("""
                    SELECT DISTINCT subject_id FROM core.v_batch_transition WHERE lines_fetch_id::text=? ORDER BY subject_id
                    """)
                        .param(id)
                        .query((rs, n) -> new Link("batches", rs.getString(1), null))
                        .list();
            default -> List.of();
        };
        return new Evidence(type, id, links);
    }

    record EntryDetail(
            QueryModels.Entry entry,
            @Nullable String reversedBy,
            List<PaymentQueries.Posting> postings,
            PaymentQueries.AsOf asOf) {}

    EntryDetail entry(String id) {
        return snapshot.read(asOf -> new EntryDetail(
                jdbc.sql("SELECT * FROM ledger.v_journal_entry WHERE id=?")
                        .param(id)
                        .query(QueryModels.entry(json))
                        .optional()
                        .orElseThrow(() -> WorkbenchQueries.missing("Entry")),
                jdbc.sql("SELECT correction_id FROM ledger.v_correction_target WHERE entry_id=? AND role='REVERSED'")
                        .param(id)
                        .query(String.class)
                        .optional()
                        .orElse(null),
                workbench.postings(List.of(id)),
                asOf));
    }
}
