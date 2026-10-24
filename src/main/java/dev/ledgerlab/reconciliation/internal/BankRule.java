package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.shared.BusinessCalendar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class BankRule implements ReconRule {
    private final BankEvidence evidence;
    private final BusinessCalendar calendar;
    private final AgingPolicy policy;
    private final ReconJson json;
    private final JdbcClient jdbc;

    public BankRule(
            BankEvidence evidence, BusinessCalendar calendar, AgingPolicy policy, ReconJson json, JdbcClient jdbc) {
        this.evidence = evidence;
        this.calendar = calendar;
        this.policy = policy;
        this.json = json;
        this.jdbc = jdbc;
    }

    @Override
    public RunRequest.Kind kind() {
        return RunRequest.Kind.BANK;
    }

    @Override
    public String version() {
        return "p3-v2";
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
        var snapshot = evidence.read(context);
        Instant cutoff = context.request().cutoffAt();
        var date = calendar.businessDateOf(cutoff);
        for (var batch : snapshot.batches()) {
            boolean missing = !batch.status().equals("PAYOUT_FAILED") && !snapshot.matched(batch);
            Instant due = calendar.dueAt(batch.occurred(), policy.depositBusinessDays());
            if (!missing || due.isAfter(cutoff))
                result.check(
                        "TIMING_DIFFERENCE",
                        "batch",
                        batch.id(),
                        missing,
                        Map.of("net", batch.net(), "due_at", due.toString()),
                        Map.of("matched", !missing),
                        snapshot.refs(batch),
                        missing ? due : null,
                        "Bank evidence matched or payout failed: " + batch.id());
        }
        for (var line : snapshot.lines()) {
            if (!line.booking().isBefore(date) || !line.status().equals("BOOKED")) continue;
            long open = snapshot.unapplied(line);
            result.check(
                    "UNAPPLIED_CASH_OPEN",
                    "bank_txn",
                    line.id(),
                    open != 0,
                    Map.of("unapplied", 0),
                    Map.of("unapplied", open),
                    snapshot.refs(line),
                    null,
                    "No unapplied cash remains for " + line.id());
            boolean unhandled = open != 0
                    || line.direction().equals("DEBIT")
                    || !line.currency().equals("CAD");
            // The ingestion identity is immutable; dispositions are deliberately not used as evidence.
            var categories = jdbc.sql("""
                    SELECT category FROM recon.issue WHERE detected_by='INGESTION' AND subject_type='bank_txn'
                      AND subject_id=? AND recorded_at < ?
                      AND category IN ('DEPOSIT_AMOUNT_MISMATCH','UNMATCHED_BANK_LINE') ORDER BY category
                    """)
                    .params(line.id(), context.request().evidenceAsOf().atOffset(ZoneOffset.UTC))
                    .query(String.class)
                    .list();
            for (String category : categories)
                result.check(
                        java.util.Objects.requireNonNull(category),
                        "bank_txn",
                        line.id(),
                        unhandled,
                        Map.of("matched", true),
                        Map.of("matched", false, "amount", line.amount(), "reference", line.reference()),
                        snapshot.refs(line),
                        null,
                        "Bank line no longer requires handling");
        }
        for (var statement : snapshot.statements()) {
            if (!statement.end().isBefore(date)) continue;
            long cash = snapshot.cash(statement.end());
            long difference = BankEvidence.bounded(Math.subtractExact(statement.closing(), cash));
            long waiting = 0;
            Instant due = null;
            var refs = new ArrayList<Map<String, Object>>();
            refs.add(Map.of(
                    "file_id", statement.file(), "period_end", statement.end().toString()));
            for (var line : snapshot.lines()) {
                if (line.booking().isBefore(statement.start()) || line.booking().isAfter(statement.end())) continue;
                refs.addAll(snapshot.refs(line));
                if (snapshot.waiting(line)) {
                    waiting = Math.addExact(waiting, line.amount());
                    Instant deadline = java.util.Objects.requireNonNull(line.waitUntil());
                    if (due == null || deadline.isBefore(due)) due = deadline;
                }
            }
            BankEvidence.bounded(waiting);
            boolean timing = difference != 0 && difference == waiting;
            // Identity is account + period, so a newer statement version can explain the old issue.
            String subject = statement.account() + ":" + statement.start() + ":" + statement.end();
            var expected = Map.of("closing_balance", statement.closing());
            var actual = Map.of("cash", cash, "difference", difference, "waiting", waiting);
            result.check(
                    "BANK_BALANCE_MISMATCH",
                    "bank_statement",
                    subject,
                    difference != 0 && !timing,
                    expected,
                    actual,
                    refs,
                    null,
                    "Latest bank statement balance matches cash or WAITING evidence");
            result.check(
                    "TIMING_DIFFERENCE",
                    "bank_statement",
                    subject,
                    timing,
                    expected,
                    actual,
                    refs,
                    timing ? due : null,
                    "Bank balance no longer has a WAITING cash difference");
        }
        return result;
    }
}
