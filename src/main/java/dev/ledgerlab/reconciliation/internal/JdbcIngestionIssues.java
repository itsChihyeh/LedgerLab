package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class JdbcIngestionIssues implements IngestionIssues {
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    public JdbcIngestionIssues(JdbcClient jdbc, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void open(
            String category,
            String subjectType,
            String subjectId,
            String expectedJson,
            String actualJson,
            String evidenceRefs) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Ingestion issues require the caller's transaction");
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO recon.issue
                    (id, category, subject_type, subject_id, detected_by, status,
                     expected, actual, evidence_refs, recorded_at, updated_at)
                VALUES (:id, :category, :subjectType, :subjectId, 'INGESTION', 'OPEN',
                        CAST(:expected AS jsonb), CAST(:actual AS jsonb), CAST(:evidence AS jsonb), :now, :now)
                ON CONFLICT ON CONSTRAINT issue_identity DO NOTHING
                """)
                .param("id", ids.next("iss"))
                .param("category", category)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("expected", expectedJson)
                .param("actual", actualJson)
                .param("evidence", evidenceRefs)
                .param("now", now)
                .update();
    }

    @Override
    public void resolveBankReturn(String creditId, String debitId, String evidenceRefs) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bank return disposition requires the caller's transaction");
        }
        var open = jdbc.sql("""
                SELECT id FROM recon.issue WHERE subject_type='bank_txn' AND subject_id=:credit
                  AND status='OPEN' ORDER BY id FOR UPDATE
                """).param("credit", creditId).query(String.class).list();
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        String explanation = "Unapplied cash returned by bank transaction " + debitId;
        for (String value : open) {
            String id = java.util.Objects.requireNonNull(value);
            jdbc.sql("UPDATE recon.issue SET status='RESOLVED', explanation=?, updated_at=? WHERE id=?")
                    .params(explanation, now, id)
                    .update();
            jdbc.sql("""
                    INSERT INTO recon.issue_transition
                        (id, issue_id, from_status, to_status, reason, explanation, evidence_refs, recorded_at)
                    VALUES (?, ?, 'OPEN', 'RESOLVED', 'EXPLAINED', ?, CAST(? AS jsonb), ?)
                    """)
                    .params(ids.next("itr"), id, explanation, evidenceRefs, now)
                    .update();
        }
    }
}
