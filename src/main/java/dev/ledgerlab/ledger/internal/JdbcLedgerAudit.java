package dev.ledgerlab.ledger.internal;

import dev.ledgerlab.ledger.LedgerAudit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The checks are independent SQL recomputations; none of them trusts the stored balance or header. */
@Component
public class JdbcLedgerAudit implements LedgerAudit {

    private final JdbcClient jdbc;
    private final @Nullable TransactionTemplate snapshot;
    private final boolean readonlyViews;

    @Autowired
    public JdbcLedgerAudit(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this(dataSource, transactionManager, false);
    }

    private JdbcLedgerAudit(
            DataSource dataSource, PlatformTransactionManager transactionManager, boolean readonlyViews) {
        this.readonlyViews = readonlyViews;
        this.jdbc = JdbcClient.create(dataSource);
        this.snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static LedgerAudit readonly(DataSource dataSource) {
        return new JdbcLedgerAudit(dataSource, new JdbcTransactionManager(dataSource), true);
    }

    /** Runs the same checks through the readonly views inside the caller's transaction (no own snapshot). */
    static List<InvariantViolation> checkInCallerSnapshot(JdbcClient jdbc) {
        return new JdbcLedgerAudit(jdbc).checkAll();
    }

    private JdbcLedgerAudit(JdbcClient jdbc) {
        this.readonlyViews = true;
        this.jdbc = jdbc;
        this.snapshot = null;
    }

    private JdbcClient.StatementSpec sql(String statement) {
        // Only these fixed, internal SQL statements reach this method. No caller supplied SQL.
        return jdbc.sql(readonlyViews ? statement.replace("ledger.", "ledger.v_") : statement);
    }

    @Override
    public List<InvariantViolation> check() {
        List<InvariantViolation> violations = Objects.requireNonNull(snapshot).execute(status -> checkAll());
        return violations == null ? List.of() : violations;
    }

    private List<InvariantViolation> checkAll() {
        List<InvariantViolation> found = new ArrayList<>();
        found.addAll(entriesMatchHeaders());
        found.addAll(balancesMatchPostings());
        found.addAll(assetsEqualLiabilities());
        found.addAll(nonNegativeAccounts());
        found.addAll(uniqueSourceKeys());
        return found;
    }

    /** INV-1 and INV-4: at least two positive postings, debits = credits = header total, count = header count. */
    private List<InvariantViolation> entriesMatchHeaders() {
        return sql("""
                        SELECT e.id, e.posting_count, e.total_amount, count(p.id) AS postings,
                               coalesce(sum(p.amount) FILTER (WHERE p.side = 'D'), 0) AS debits,
                               coalesce(sum(p.amount) FILTER (WHERE p.side = 'C'), 0) AS credits,
                               coalesce(min(p.amount), 0) AS smallest
                        FROM ledger.journal_entry e
                        LEFT JOIN ledger.posting p ON p.entry_id = e.id
                        GROUP BY e.id, e.posting_count, e.total_amount
                        HAVING count(p.id) < 2
                            OR count(p.id) <> e.posting_count
                            OR coalesce(sum(p.amount) FILTER (WHERE p.side = 'D'), 0) <> e.total_amount
                            OR coalesce(sum(p.amount) FILTER (WHERE p.side = 'C'), 0) <> e.total_amount
                            OR coalesce(min(p.amount), 0) <= 0
                        ORDER BY e.id
                        """)
                .query((rs, row) -> new InvariantViolation(
                        "INV-1/INV-4",
                        "entry %s: %d postings (header %d), debits %d, credits %d, header total %d, smallest %d"
                                .formatted(
                                        rs.getString("id"),
                                        rs.getLong("postings"),
                                        rs.getLong("posting_count"),
                                        rs.getLong("debits"),
                                        rs.getLong("credits"),
                                        rs.getLong("total_amount"),
                                        rs.getLong("smallest"))))
                .list();
    }

    /** INV-2: the stored balance and version equal what the postings say. */
    private List<InvariantViolation> balancesMatchPostings() {
        return sql("""
                        SELECT a.code, a.balance, a.version,
                               coalesce(sum(CASE WHEN p.side = a.normal_side THEN p.amount ELSE -p.amount END), 0)
                                   AS recomputed,
                               count(p.id) AS postings
                        FROM ledger.account a
                        LEFT JOIN ledger.posting p ON p.account_code = a.code
                        GROUP BY a.code, a.balance, a.version
                        HAVING a.balance <> coalesce(sum(CASE WHEN p.side = a.normal_side THEN p.amount
                                                               ELSE -p.amount END), 0)
                            OR a.version <> count(p.id)
                        ORDER BY a.code
                        """)
                .query((rs, row) -> new InvariantViolation(
                        "INV-2",
                        "account %d: stored balance %d version %d, postings give %d over %d postings"
                                .formatted(
                                        rs.getInt("code"),
                                        rs.getLong("balance"),
                                        rs.getLong("version"),
                                        rs.getLong("recomputed"),
                                        rs.getLong("postings"))))
                .list();
    }

    /** INV-3: total assets equal total liabilities, from the stored balances. */
    private List<InvariantViolation> assetsEqualLiabilities() {
        return sql("""
                        SELECT coalesce(sum(balance) FILTER (WHERE kind = 'ASSET'), 0) AS assets,
                               coalesce(sum(balance) FILTER (WHERE kind = 'LIABILITY'), 0) AS liabilities
                        FROM ledger.account
                        """)
                .query((rs, row) -> new long[] {rs.getLong("assets"), rs.getLong("liabilities")})
                .optional()
                .filter(totals -> totals[0] != totals[1])
                .map(totals ->
                        new InvariantViolation("INV-3", "assets %d, liabilities %d".formatted(totals[0], totals[1])))
                .stream()
                .toList();
    }

    /** INV-5: accounts that may not go negative are not negative. */
    private List<InvariantViolation> nonNegativeAccounts() {
        return sql("SELECT code, balance FROM ledger.account WHERE NOT allow_negative AND balance < 0 ORDER BY code")
                .query((rs, row) -> new InvariantViolation(
                        "INV-5", "account %d balance %d".formatted(rs.getInt("code"), rs.getLong("balance"))))
                .list();
    }

    /** INV-8: one entry per source key (the UNIQUE constraint holds it; this proves it). */
    private List<InvariantViolation> uniqueSourceKeys() {
        return sql("""
                        SELECT source_key, count(*) AS entries FROM ledger.journal_entry
                        GROUP BY source_key HAVING count(*) > 1 ORDER BY source_key
                        """)
                .query((rs, row) -> new InvariantViolation(
                        "INV-8",
                        "source key %s has %d entries".formatted(rs.getString("source_key"), rs.getLong("entries"))))
                .list();
    }
}
