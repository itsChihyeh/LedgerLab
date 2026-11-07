package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.shared.InvariantCheck;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** INV-6, INV-9 (2104 part) and INV-10 (payout part), recomputed from payouts and ledger entries. */
@Component
public final class PayoutInvariantCheck implements InvariantCheck {

    @Override
    public List<String> ids() {
        return List.of("INV-6", "INV-9", "INV-10");
    }

    @Override
    public Map<String, List<String>> check(JdbcClient snapshot) {
        return Map.of(
                "INV-6",
                java.util.stream.Stream.of(negativeAvailable(snapshot), reservedWithinFreeCash(snapshot))
                        .flatMap(List::stream)
                        .toList(),
                "INV-9",
                payoutTransit(snapshot),
                "INV-10",
                effects(snapshot));
    }

    /**
     * INV-6, first part. 2102 only crosses below zero through a refund acceptance or a correction (a correction's
     * entries carry its id), and a payout reservation never leaves it negative.
     */
    private List<String> negativeAvailable(JdbcClient snapshot) {
        return snapshot.sql("""
                        SELECT e.id, e.entry_type, p.balance_after
                        FROM ledger.v_posting p JOIN ledger.v_journal_entry e ON e.id = p.entry_id
                        WHERE p.account_code = 2102 AND p.balance_after < 0
                          AND ((e.entry_type = 'PAYOUT_RESERVED')
                            OR (e.entry_type <> 'REFUND_ACCEPTED' AND e.correction_id IS NULL
                                AND coalesce((SELECT q.balance_after FROM ledger.v_posting q
                                              WHERE q.account_code = 2102
                                                AND q.account_version = p.account_version - 1), 0) >= 0))
                        ORDER BY e.seq
                        """)
                .query((rs, row) -> "entry %s (%s) left 2102 at %d"
                        .formatted(rs.getString("id"), rs.getString("entry_type"), rs.getLong("balance_after")))
                .list();
    }

    /**
     * INV-6, second part. The free cash snapshot is not stored, so it is rebuilt from the running balances right
     * after each reservation: 1103 - 2104 - 2105 - max(0, -1101) must still be at least zero, which is the same as
     * "amount at most the free cash that was available". Entries are ordered by sequence; money moves under the
     * global funds lock, so sequence order is the order the balances changed.
     */
    private List<String> reservedWithinFreeCash(JdbcClient snapshot) {
        return snapshot.sql("""
                        WITH delta AS (
                            SELECT e.seq, e.id, e.entry_type,
                                   coalesce(sum(CASE WHEN p.side = 'D' THEN p.amount ELSE -p.amount END)
                                       FILTER (WHERE p.account_code = 1101), 0) AS d1101,
                                   coalesce(sum(CASE WHEN p.side = 'D' THEN p.amount ELSE -p.amount END)
                                       FILTER (WHERE p.account_code = 1103), 0) AS d1103,
                                   coalesce(sum(CASE WHEN p.side = 'C' THEN p.amount ELSE -p.amount END)
                                       FILTER (WHERE p.account_code = 2104), 0) AS d2104,
                                   coalesce(sum(CASE WHEN p.side = 'C' THEN p.amount ELSE -p.amount END)
                                       FILTER (WHERE p.account_code = 2105), 0) AS d2105
                            FROM ledger.v_journal_entry e JOIN ledger.v_posting p ON p.entry_id = e.id
                            WHERE p.account_code IN (1101, 1103, 2104, 2105)
                            GROUP BY e.seq, e.id, e.entry_type
                        ), running AS (
                            SELECT id, entry_type,
                                   sum(d1101) OVER w AS b1101, sum(d1103) OVER w AS b1103,
                                   sum(d2104) OVER w AS b2104, sum(d2105) OVER w AS b2105
                            FROM delta WINDOW w AS (ORDER BY seq)
                        )
                        SELECT id, b1103 - b2104 - b2105 - greatest(0, -b1101) AS free_after
                        FROM running
                        WHERE entry_type = 'PAYOUT_RESERVED' AND b1103 - b2104 - b2105 - greatest(0, -b1101) < 0
                        ORDER BY id
                        """)
                .query((rs, row) -> "reservation %s exceeded free cash by %d"
                        .formatted(rs.getString("id"), -rs.getLong("free_after")))
                .list();
    }

    /** INV-9: 2104 equals the payouts that still hold funds in transit. */
    private List<String> payoutTransit(JdbcClient snapshot) {
        return snapshot
                .sql("""
                        SELECT a.balance, coalesce((SELECT sum(o.amount) FROM core.v_payout o
                            WHERE o.status IN ('RESERVED', 'SUBMITTED', 'UNKNOWN', 'ACCEPTED')), 0) AS expected
                        FROM ledger.v_account a WHERE a.code = 2104
                        """)
                .query((rs, row) -> new long[] {rs.getLong("balance"), rs.getLong("expected")})
                .list()
                .stream()
                .filter(pair -> pair[0] != pair[1])
                .map(pair -> "2104 balance %d, in-transit payouts total %d".formatted(pair[0], pair[1]))
                .toList();
    }

    /**
     * INV-10: one reservation per payout, paid and failed are alternatives (at most one of them), a return happens
     * at most once, and a payout whose result is still open (including UNKNOWN) has settled nothing.
     */
    private List<String> effects(JdbcClient snapshot) {
        return snapshot.sql("""
                        SELECT o.id, o.status,
                               count(*) FILTER (WHERE e.entry_type = 'PAYOUT_RESERVED') AS reserved,
                               count(*) FILTER (WHERE e.entry_type IN ('PAYOUT_PAID', 'PAYOUT_FAILED')) AS settled,
                               count(*) FILTER (WHERE e.entry_type = 'PAYOUT_RETURNED') AS returned
                        FROM core.v_payout o
                        LEFT JOIN ledger.v_journal_entry e ON e.subject_type = 'payout' AND e.subject_id = o.id
                        GROUP BY o.id, o.status
                        HAVING count(*) FILTER (WHERE e.entry_type = 'PAYOUT_RESERVED') > 1
                            OR count(*) FILTER (WHERE e.entry_type IN ('PAYOUT_PAID', 'PAYOUT_FAILED')) > 1
                            OR count(*) FILTER (WHERE e.entry_type = 'PAYOUT_RETURNED') > 1
                            OR (o.status IN ('RESERVED', 'SUBMITTED', 'UNKNOWN', 'ACCEPTED')
                                AND count(*) FILTER (WHERE e.entry_type IN
                                    ('PAYOUT_PAID', 'PAYOUT_FAILED', 'PAYOUT_RETURNED')) > 0)
                        ORDER BY o.id
                        """)
                .query((rs, row) -> "payout %s (%s): %d reservations, %d paid-or-failed, %d returns"
                        .formatted(
                                rs.getString("id"),
                                rs.getString("status"),
                                rs.getLong("reserved"),
                                rs.getLong("settled"),
                                rs.getLong("returned")))
                .list();
    }
}
