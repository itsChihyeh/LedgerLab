package dev.ledgerlab.corrections.internal;

import dev.ledgerlab.corrections.CorrectionException;
import dev.ledgerlab.corrections.CorrectionResult;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Same response shape for the write transaction and readonly snapshot; table names are fixed internally. */
final class CorrectionReader {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final String prefix;

    CorrectionReader(JdbcClient jdbc, ObjectMapper mapper, boolean views) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.prefix = views ? "v_" : "";
    }

    CorrectionResult detail(String id) {
        Map<String, Object> correction = jdbc.sql("SELECT * FROM ledger." + prefix + "correction WHERE id=?")
                .param(id)
                .query((rs, n) -> Map.<String, Object>of(
                        "id",
                        rs.getString("id"),
                        "type",
                        rs.getString("correction_type"),
                        "reasonCode",
                        rs.getString("reason_code"),
                        "reasonNote",
                        rs.getString("reason_note"),
                        "evidence",
                        mapper.readTree(rs.getString("evidence_refs")),
                        "params",
                        mapper.readTree(rs.getString("params")),
                        "subjectType",
                        rs.getString("subject_type"),
                        "subjectId",
                        rs.getString("subject_id"),
                        "requestedBy",
                        rs.getString("requested_by"),
                        "recordedAt",
                        rs.getTimestamp("recorded_at").toInstant()))
                .optional()
                .orElseThrow(() -> new CorrectionException(404, "CORRECTION_NOT_FOUND"));
        var targets = jdbc.sql("SELECT entry_id, role FROM ledger." + prefix
                        + "correction_target WHERE correction_id=? ORDER BY entry_id")
                .param(id)
                .query((rs, n) -> Map.<String, Object>of("entryId", rs.getString(1), "role", rs.getString(2)))
                .list();
        var entries = jdbc.sql("SELECT id, entry_type FROM ledger." + prefix
                        + "journal_entry WHERE correction_id=? ORDER BY seq")
                .param(id)
                .query((rs, n) -> Map.<String, Object>of(
                        "entryId",
                        rs.getString(1),
                        "entryType",
                        rs.getString(2),
                        "postings",
                        jdbc.sql("SELECT line_no, account_code, side, amount FROM ledger." + prefix
                                        + "posting WHERE entry_id=? ORDER BY line_no")
                                .param(rs.getString(1))
                                .query((p, i) -> Map.<String, Object>of(
                                        "lineNo",
                                        p.getInt(1),
                                        "accountCode",
                                        p.getInt(2),
                                        "side",
                                        p.getString(3),
                                        "amount",
                                        p.getLong(4)))
                                .list()))
                .list();
        var issues = jdbc.sql("SELECT id FROM recon." + prefix + "issue WHERE resolution_correction_id=? ORDER BY id")
                .param(id)
                .query((rs, n) -> rs.getString(1))
                .list();
        return new CorrectionResult(
                correction,
                targets,
                entries,
                new CorrectionResult.Affected(
                        List.of((String) correction.get("subjectId")), List.of(), List.of(), issues));
    }
}
