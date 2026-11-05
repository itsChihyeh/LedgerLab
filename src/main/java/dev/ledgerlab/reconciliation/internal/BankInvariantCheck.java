package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.shared.InvariantCheck;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** INV-11 (bank side) and INV-12 (unapplied cash), recomputed from the imported bank lines. */
@Component
public final class BankInvariantCheck implements InvariantCheck {

    @Override
    public List<String> ids() {
        return List.of("INV-11", "INV-12");
    }

    @Override
    public Map<String, List<String>> check(JdbcClient snapshot) {
        return Map.of(
                "INV-11",
                java.util.stream.Stream.of(statementsAddUp(snapshot), cashMatchesBank(snapshot))
                        .flatMap(List::stream)
                        .toList(),
                "INV-12",
                unappliedCash(snapshot));
    }

    /** INV-11, first part: opening + booked credits - booked debits = closing, for every imported statement. */
    private List<String> statementsAddUp(JdbcClient snapshot) {
        return snapshot.sql("""
                        SELECT s.file_id, s.opening_balance, s.closing_balance,
                               coalesce(sum(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                                   FILTER (WHERE l.status = 'BOOKED'), 0) AS booked
                        FROM recon.v_bank_statement s LEFT JOIN recon.v_bank_line l ON l.file_id = s.file_id
                        GROUP BY s.file_id, s.opening_balance, s.closing_balance
                        HAVING s.opening_balance + coalesce(sum(CASE WHEN l.direction = 'CREDIT' THEN l.amount
                                   ELSE -l.amount END) FILTER (WHERE l.status = 'BOOKED'), 0) <> s.closing_balance
                        ORDER BY s.file_id
                        """)
                .query((rs, row) -> "statement %s: opening %d + booked %d <> closing %d"
                        .formatted(
                                rs.getString("file_id"),
                                rs.getLong("opening_balance"),
                                rs.getLong("booked"),
                                rs.getLong("closing_balance")))
                .list();
    }

    /**
     * INV-11, second part. As of the latest statement the bank cash on the books (1103) equals its closing
     * balance, unless the difference is shown: it equals the booked bank lines that were deliberately not posted
     * (credits still WAITING, lines with no posting, credits positive and debits negative), or a reconciliation run
     * has an unresolved balance or timing difference recorded for a bank statement.
     */
    private List<String> cashMatchesBank(JdbcClient snapshot) {
        return snapshot.sql("""
                        WITH latest AS (
                            SELECT file_id, period_end, closing_balance FROM recon.v_bank_statement
                            ORDER BY period_end DESC, version DESC, recorded_at DESC LIMIT 1
                        ), gap AS (
                            SELECT l.file_id, l.period_end, l.closing_balance, a.balance AS cash,
                                   l.closing_balance - a.balance AS difference,
                                   (SELECT coalesce(sum(CASE WHEN w.direction = 'CREDIT' THEN w.amount
                                                             ELSE -w.amount END), 0)
                                      FROM recon.v_bank_line w
                                     WHERE w.status = 'BOOKED'
                                       AND w.match_status IN ('WAITING', 'UNMATCHED_NO_POSTING')
                                       AND w.version = (SELECT max(m.version) FROM recon.v_bank_line m
                                                         WHERE m.bank_txn_id = w.bank_txn_id)) AS unposted,
                                   EXISTS (SELECT 1 FROM recon.v_issue i WHERE i.status <> 'RESOLVED'
                                       AND i.subject_type = 'bank_statement'
                                       AND i.category IN ('BANK_BALANCE_MISMATCH', 'TIMING_DIFFERENCE')) AS recorded
                            FROM latest l CROSS JOIN ledger.v_account a WHERE a.code = 1103
                        )
                        SELECT * FROM gap WHERE difference <> 0 AND difference <> unposted AND NOT recorded
                        """)
                .query((rs, row) -> "statement %s (%s): bank closing %d, books 1103 %d, difference %d has no record"
                        .formatted(
                                rs.getString("file_id"),
                                rs.getString("period_end"),
                                rs.getLong("closing_balance"),
                                rs.getLong("cash"),
                                rs.getLong("difference")))
                .list();
    }

    /** INV-12: 2105 equals the credits whose current line version is still UNAPPLIED. */
    private List<String> unappliedCash(JdbcClient snapshot) {
        return snapshot
                .sql("""
                        SELECT a.balance, coalesce((SELECT sum(l.amount) FROM recon.v_bank_line l
                            WHERE l.match_status = 'UNAPPLIED'
                              AND l.version = (SELECT max(m.version) FROM recon.v_bank_line m
                                                WHERE m.bank_txn_id = l.bank_txn_id)), 0) AS expected
                        FROM ledger.v_account a WHERE a.code = 2105
                        """)
                .query((rs, row) -> new long[] {rs.getLong("balance"), rs.getLong("expected")})
                .list()
                .stream()
                .filter(pair -> pair[0] != pair[1])
                .map(pair -> "2105 balance %d, unapplied bank credits total %d".formatted(pair[0], pair[1]))
                .toList();
    }
}
