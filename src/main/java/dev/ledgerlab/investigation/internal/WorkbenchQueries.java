package dev.ledgerlab.investigation.internal;

import static dev.ledgerlab.investigation.internal.QueryModels.BankLine;
import static dev.ledgerlab.investigation.internal.QueryModels.Batch;
import static dev.ledgerlab.investigation.internal.QueryModels.BatchLine;
import static dev.ledgerlab.investigation.internal.QueryModels.BatchTransition;
import static dev.ledgerlab.investigation.internal.QueryModels.Entry;
import static dev.ledgerlab.investigation.internal.QueryModels.Finding;
import static dev.ledgerlab.investigation.internal.QueryModels.Issue;
import static dev.ledgerlab.investigation.internal.QueryModels.IssueTransition;
import static dev.ledgerlab.investigation.internal.QueryModels.Run;
import static dev.ledgerlab.investigation.internal.QueryModels.StatementLine;
import static dev.ledgerlab.investigation.internal.QueryModels.bankLine;
import static dev.ledgerlab.investigation.internal.QueryModels.batch;
import static dev.ledgerlab.investigation.internal.QueryModels.batchLine;
import static dev.ledgerlab.investigation.internal.QueryModels.batchTransition;
import static dev.ledgerlab.investigation.internal.QueryModels.entry;
import static dev.ledgerlab.investigation.internal.QueryModels.finding;
import static dev.ledgerlab.investigation.internal.QueryModels.issue;
import static dev.ledgerlab.investigation.internal.QueryModels.issueTransition;
import static dev.ledgerlab.investigation.internal.QueryModels.run;
import static dev.ledgerlab.investigation.internal.QueryModels.statementLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
final class WorkbenchQueries {
    private final QuerySnapshot snapshot;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    WorkbenchQueries(QuerySnapshot snapshot, ObjectMapper json) {
        this.snapshot = snapshot;
        this.jdbc = snapshot.jdbc;
        this.json = json;
    }

    record Account(int code, String name, long balance) {}

    record Overview(
            List<Account> accounts,
            long inTransit,
            long bankCash,
            long merchantAvailable,
            long merchantOwes,
            long unappliedCash,
            Map<String, Long> openIssuesByCategory,
            Map<String, Long> batchesByStatus,
            PaymentQueries.AsOf asOf) {}

    Overview overview() {
        return snapshot.read(asOf -> {
            var accounts = jdbc.sql("SELECT code, name, balance FROM ledger.v_account ORDER BY code")
                    .query((rs, row) -> new Account(rs.getInt(1), rs.getString(2), rs.getLong(3)))
                    .list();
            long available = balance(accounts, 2102);
            return new Overview(
                    accounts,
                    balance(accounts, 1102),
                    balance(accounts, 1103),
                    available,
                    available < 0 ? Math.negateExact(available) : 0,
                    balance(accounts, 2105),
                    counts(
                            "SELECT category, count(*) FROM recon.v_issue WHERE status='OPEN' GROUP BY category ORDER BY category"),
                    counts("SELECT status, count(*) FROM core.v_settlement_batch GROUP BY status ORDER BY status"),
                    asOf);
        });
    }

    private static long balance(List<Account> accounts, int code) {
        return accounts.stream()
                .filter(a -> a.code() == code)
                .findFirst()
                .orElseThrow()
                .balance();
    }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql(sql)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    record BatchList(List<Batch> batches, PaymentQueries.AsOf asOf) {}

    record BatchDetail(
            Batch batch,
            List<BatchLine> lines,
            List<BatchTransition> history,
            List<Entry> entries,
            List<PaymentQueries.Posting> postings,
            List<BankLine> bankLines,
            PaymentQueries.AsOf asOf) {}

    BatchList batches(int limit) {
        checkLimit(limit);
        return snapshot.read(asOf ->
                new BatchList(jdbc.sql("""
                SELECT * FROM core.v_settlement_batch ORDER BY recorded_at DESC, batch_id DESC LIMIT ?
                """).param(limit).query(batch(json)).list(), asOf));
    }

    BatchDetail batchDetail(String id) {
        return snapshot.read(asOf -> {
            Batch batch = batchById(id);
            if (batch == null) throw missing("Batch");
            var entries = jdbc.sql("""
                    SELECT * FROM ledger.v_journal_entry e WHERE
                      (subject_type='batch' AND subject_id=:id) OR
                      (subject_type='bank_txn' AND subject_id IN
                        (SELECT bank_txn_id FROM recon.v_bank_line WHERE matched_batch_id=:id))
                    ORDER BY seq
                    """).param("id", id).query(entry(json)).list();
            return new BatchDetail(
                    batch,
                    jdbc.sql("SELECT * FROM core.v_settlement_batch_line WHERE batch_id=? ORDER BY line_no")
                            .param(id)
                            .query(batchLine(json))
                            .list(),
                    jdbc.sql("SELECT * FROM core.v_batch_transition WHERE subject_id=? ORDER BY id")
                            .param(id)
                            .query(batchTransition(json))
                            .list(),
                    entries,
                    postings(entries.stream().map(Entry::id).toList()),
                    matchedBankLines(id),
                    asOf);
        });
    }

    @Nullable
    Batch batchById(String id) {
        return jdbc.sql("SELECT * FROM core.v_settlement_batch WHERE batch_id=?")
                .param(id)
                .query(batch(json))
                .optional()
                .orElse(null);
    }

    List<BankLine> matchedBankLines(String id) {
        return jdbc.sql("SELECT * FROM recon.v_bank_line WHERE matched_batch_id=? ORDER BY import_order")
                .param(id)
                .query(bankLine(json))
                .list();
    }

    List<PaymentQueries.Posting> postings(List<String> entryIds) {
        if (entryIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT e.id AS entry_id, e.entry_type, p.line_no, p.account_code, a.name, p.side, p.amount
                FROM ledger.v_journal_entry e JOIN ledger.v_posting p ON p.entry_id=e.id
                JOIN ledger.v_account a ON a.code=p.account_code
                WHERE e.id IN (:ids) ORDER BY e.seq, p.line_no
                """)
                .param("ids", entryIds)
                .query((rs, row) -> new PaymentQueries.Posting(
                        rs.getString("entry_id"),
                        rs.getString("entry_type"),
                        rs.getInt("line_no"),
                        rs.getInt("account_code"),
                        rs.getString("name"),
                        rs.getString("side"),
                        rs.getLong("amount")))
                .list();
    }

    record BankDetail(
            BankLine bankLine,
            List<BankLine> versions,
            List<Entry> entries,
            List<PaymentQueries.Posting> postings,
            PaymentQueries.AsOf asOf) {}

    BankDetail bankDetail(String id) {
        return snapshot.read(asOf -> {
            var versions = jdbc.sql("SELECT * FROM recon.v_bank_line WHERE bank_txn_id=? ORDER BY version DESC")
                    .param(id)
                    .query(bankLine(json))
                    .list();
            if (versions.isEmpty()) throw missing("Bank line");
            var entries = jdbc.sql(
                            "SELECT * FROM ledger.v_journal_entry WHERE subject_type='bank_txn' AND subject_id=? ORDER BY seq")
                    .param(id)
                    .query(entry(json))
                    .list();
            return new BankDetail(
                    versions.getFirst(),
                    versions,
                    entries,
                    postings(entries.stream().map(Entry::id).toList()),
                    asOf);
        });
    }

    record StatementDetail(StatementLine statementLine, PaymentQueries.AsOf asOf) {}

    StatementDetail statementDetail(String fileId, int seq) {
        if (seq < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seq must be positive");
        return snapshot.read(asOf -> new StatementDetail(
                jdbc.sql("""
                SELECT * FROM recon.v_statement_line WHERE file_id=? AND line_seq=?
                """)
                        .params(fileId, seq)
                        .query(statementLine(json))
                        .optional()
                        .orElseThrow(() -> missing("Statement line")),
                asOf));
    }

    record IssueList(List<Issue> issues, PaymentQueries.AsOf asOf) {}

    record IssueDetail(
            Issue issue,
            List<IssueAmounts.Comparison> amounts,
            List<IssueTransition> history,
            List<Finding> findings,
            IssueReferences.References references,
            PaymentQueries.AsOf asOf) {}

    record RunList(List<Run> runs, PaymentQueries.AsOf asOf) {}

    IssueList issues(@Nullable String status, @Nullable String category, int limit) {
        checkLimit(limit);
        if (status != null
                && !Set.of("OPEN", "EXPLAINED", "RESOLVED", "ACCEPTED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid issue status");
        return snapshot.read(asOf -> new IssueList(
                jdbc.sql("""
                SELECT * FROM recon.v_issue WHERE (:status::text IS NULL OR status=:status)
                  AND (:category::text IS NULL OR category=:category)
                ORDER BY updated_at DESC, id DESC LIMIT :limit
                """)
                        .param("status", status)
                        .param("category", category)
                        .param("limit", limit)
                        .query(issue(json))
                        .list(),
                asOf));
    }

    IssueDetail issueDetail(String id) {
        return snapshot.read(asOf -> {
            Issue issue = jdbc.sql("SELECT * FROM recon.v_issue WHERE id=?")
                    .param(id)
                    .query(issue(json))
                    .optional()
                    .orElseThrow(() -> missing("Issue"));
            var history = jdbc.sql("SELECT * FROM recon.v_issue_transition WHERE issue_id=? ORDER BY recorded_at, id")
                    .param(id)
                    .query(issueTransition(json))
                    .list();
            var findings = jdbc.sql("""
                    SELECT f.* FROM recon.v_finding f JOIN recon.v_run r ON r.id=f.run_id
                    WHERE f.issue_id=? ORDER BY r.started_at, r.id, f.id
                    """).param(id).query(finding(json)).list();
            return new IssueDetail(
                    issue,
                    IssueAmounts.compare(issue.expected(), issue.actual()),
                    history,
                    findings,
                    new IssueReferences(jdbc, json).resolve(issue, findings, history),
                    asOf);
        });
    }

    RunList runs(int limit) {
        checkLimit(limit);
        return snapshot.read(asOf -> new RunList(
                jdbc.sql("SELECT * FROM recon.v_run ORDER BY started_at DESC, id DESC LIMIT ?")
                        .param(limit)
                        .query(run(json))
                        .list(),
                asOf));
    }

    static void checkLimit(int limit) {
        if (limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
    }

    static ResponseStatusException missing(String kind) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, kind + " not found");
    }
}
