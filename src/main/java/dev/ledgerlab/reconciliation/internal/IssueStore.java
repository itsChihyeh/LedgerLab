package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.reconciliation.IssueDisposition;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class IssueStore implements IssueDisposition {
    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public IssueStore(JdbcClient jdbc, IdGenerator ids, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    private record Issue(
            String id,
            String status,
            String detectedBy,
            @Nullable String runKind,
            @Nullable String fingerprint,
            int reopenCount) {}

    private java.util.List<Issue> issues(String predicate, Object... parameters) {
        return jdbc.sql(
                        "SELECT id, status, detected_by, (SELECT kind FROM recon.run WHERE id=first_run_id) AS run_kind, fingerprint, reopen_count FROM recon.issue WHERE "
                                + predicate + " FOR UPDATE")
                .params(parameters)
                .query((rs, row) -> new Issue(
                        rs.getString("id"),
                        rs.getString("status"),
                        rs.getString("detected_by"),
                        rs.getString("run_kind"),
                        rs.getString("fingerprint"),
                        rs.getInt("reopen_count")))
                .list();
    }

    String found(String runId, RunRequest.Kind kind, Finding finding, String fingerprint, OffsetDateTime now) {
        var existing = issues(
                "category=? AND subject_type=? AND subject_id=?",
                finding.category(),
                finding.subjectType(),
                finding.subjectId());
        if (existing.isEmpty()) {
            String id = ids.next("iss");
            jdbc.sql("""
                    INSERT INTO recon.issue (id, category, subject_type, subject_id, detected_by,
                        first_run_id, last_run_id, status, expected, actual, evidence_refs, fingerprint, recorded_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                    """)
                    .params(
                            id,
                            finding.category(),
                            finding.subjectType(),
                            finding.subjectId(),
                            kind == RunRequest.Kind.AGING ? "AGING" : "RUN",
                            runId,
                            runId,
                            finding.expected(),
                            finding.actual(),
                            finding.evidenceRefs(),
                            fingerprint,
                            now,
                            now)
                    .update();
            transition(id, null, "OPEN", runId, "DETECTED", "Detected by run " + runId, finding.evidenceRefs(), now);
            return id;
        }
        Issue issue = existing.getFirst();
        if (issue.detectedBy().equals("INGESTION") || !kind.name().equals(issue.runKind())) return issue.id();
        if ((issue.status().equals("ACCEPTED") || issue.status().equals("RESOLVED"))
                && fingerprint.equals(issue.fingerprint())) {
            jdbc.sql("""
                    UPDATE recon.issue SET last_run_id=?, fingerprint=?,
                        expected=CAST(? AS jsonb), actual=CAST(? AS jsonb), evidence_refs=CAST(? AS jsonb)
                    WHERE id=?
                    """)
                    .params(
                            runId,
                            fingerprint,
                            finding.expected(),
                            finding.actual(),
                            finding.evidenceRefs(),
                            issue.id())
                    .update();
            return issue.id();
        }
        boolean reopen = issue.status().equals("EXPLAINED")
                || (!issue.status().equals("OPEN") && !fingerprint.equals(issue.fingerprint()));
        jdbc.sql("""
                UPDATE recon.issue SET last_run_id=?, fingerprint=?,
                    expected=CAST(? AS jsonb), actual=CAST(? AS jsonb), evidence_refs=CAST(? AS jsonb), updated_at=?,
                    status=?, reopen_count=?, explanation=CASE WHEN ? THEN NULL ELSE explanation END,
                    resolution_correction_id=CASE WHEN ? THEN NULL ELSE resolution_correction_id END
                WHERE id=?
                """)
                .params(
                        runId,
                        fingerprint,
                        finding.expected(),
                        finding.actual(),
                        finding.evidenceRefs(),
                        now,
                        reopen ? "OPEN" : issue.status(),
                        reopen ? Math.incrementExact(issue.reopenCount()) : issue.reopenCount(),
                        reopen,
                        reopen,
                        issue.id())
                .update();
        if (reopen)
            transition(
                    issue.id(),
                    issue.status(),
                    "OPEN",
                    runId,
                    "REOPENED",
                    "Reopened by run " + runId,
                    finding.evidenceRefs(),
                    now);
        return issue.id();
    }

    void absent(
            String runId,
            RunRequest.Kind kind,
            Finding.Key key,
            ReconRule.Explanation explanation,
            OffsetDateTime now) {
        var existing = issues("""
                category=? AND subject_type=? AND subject_id=? AND detected_by <> 'INGESTION' AND status='OPEN'
                AND first_run_id IN (SELECT id FROM recon.run WHERE kind=?)
                """, key.category(), key.subjectType(), key.subjectId(), kind.name());
        if (existing.isEmpty()) return;
        Issue issue = existing.getFirst();
        String note = "Run " + runId + ": " + explanation.note();
        jdbc.sql("UPDATE recon.issue SET status='EXPLAINED', last_run_id=?, explanation=?, updated_at=? WHERE id=?")
                .params(runId, note, now, issue.id())
                .update();
        transition(issue.id(), "OPEN", "EXPLAINED", runId, "EXPLAINED", note, explanation.evidenceRefs(), now);
    }

    void escalated(String runId, Finding.Key key, ReconRule.Explanation explanation, OffsetDateTime now) {
        // AGING owns this explicit cross-kind transition; ordinary absence remains kind-scoped.
        RunRequest.Kind source = key.subjectType().equals("payment") ? RunRequest.Kind.PROCESSOR : RunRequest.Kind.BANK;
        absent(runId, source, key, explanation, now);
        absent(runId, RunRequest.Kind.AGING, key, explanation, now);
    }

    @Override
    public void accept(String issueId, String reason) {
        dispose(issueId, reason, "ACCEPTED");
    }

    @Override
    public void resolve(String issueId, String note) {
        dispose(issueId, note, "RESOLVED");
    }

    private void dispose(String issueId, String note, String target) {
        if (issueId.isBlank() || note.isBlank())
            throw new IllegalArgumentException("Issue and disposition explanation are required");
        transactions.executeWithoutResult(tx -> {
            Issue issue = issues("id=?", issueId).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown issue: " + issueId));
            if (!issue.status().equals("OPEN")) throw new IllegalStateException("Only OPEN issues can be disposed");
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            jdbc.sql("UPDATE recon.issue SET status=?, explanation=?, updated_at=? WHERE id=?")
                    .params(target, note, now, issueId)
                    .update();
            transition(issueId, "OPEN", target, null, "OPERATOR", note, "[]", now);
        });
    }

    private void transition(
            String issueId,
            @Nullable String from,
            String to,
            @Nullable String runId,
            String reason,
            String explanation,
            String evidence,
            OffsetDateTime now) {
        jdbc.sql("""
                INSERT INTO recon.issue_transition (id, issue_id, from_status, to_status, run_id, reason,
                    explanation, evidence_refs, recorded_at)
                VALUES (:id, :issue, :from, :to, :run, :reason, :explanation, CAST(:evidence AS jsonb), :now)
                """)
                .param("id", ids.next("itr"))
                .param("issue", issueId)
                .param("from", from)
                .param("to", to)
                .param("run", runId)
                .param("reason", reason)
                .param("explanation", explanation)
                .param("evidence", evidence)
                .param("now", now)
                .update();
    }
}
