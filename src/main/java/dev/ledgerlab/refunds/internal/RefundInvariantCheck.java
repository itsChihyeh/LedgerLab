package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.shared.InvariantCheck;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** INV-7, INV-9 (2103 part) and INV-10 (refund part), recomputed from the refund rows and the entries. */
@Component
public final class RefundInvariantCheck implements InvariantCheck {

    @Override
    public List<String> ids() {
        return List.of("INV-7", "INV-9", "INV-10");
    }

    @Override
    public Map<String, List<String>> check(JdbcClient snapshot) {
        return Map.of("INV-7", overRefunded(snapshot), "INV-9", refundTransit(snapshot), "INV-10", effects(snapshot));
    }

    /** INV-7: per payment, the refunds that are neither failed nor cancelled never exceed the captured amount. */
    private List<String> overRefunded(JdbcClient snapshot) {
        return snapshot.sql("""
                        SELECT p.id, p.amount, sum(r.amount) AS refunded
                        FROM core.v_payment p JOIN core.v_refund r ON r.payment_id = p.id
                        WHERE r.status NOT IN ('FAILED', 'CANCELLED')
                        GROUP BY p.id, p.amount
                        HAVING sum(r.amount) > p.amount
                        ORDER BY p.id
                        """)
                .query((rs, row) -> "payment %s: refunds %d exceed captured amount %d"
                        .formatted(rs.getString("id"), rs.getLong("refunded"), rs.getLong("amount")))
                .list();
    }

    /** INV-9: 2103 equals the refunds that still hold funds in transit. */
    private List<String> refundTransit(JdbcClient snapshot) {
        return snapshot
                .sql("""
                        SELECT a.balance, coalesce((SELECT sum(r.amount) FROM core.v_refund r
                            WHERE r.status IN ('AWAITING_FUNDS', 'READY', 'SUBMITTED', 'UNKNOWN')), 0) AS expected
                        FROM ledger.v_account a WHERE a.code = 2103
                        """)
                .query((rs, row) -> new long[] {rs.getLong("balance"), rs.getLong("expected")})
                .list()
                .stream()
                .filter(pair -> pair[0] != pair[1])
                .map(pair -> "2103 balance %d, in-transit refunds total %d".formatted(pair[0], pair[1]))
                .toList();
    }

    /**
     * INV-10: one reservation and at most one release (executed, failed or cancelled) per refund; an unresolved
     * refund (including UNKNOWN) has released nothing; a refund reached a final status at most once.
     */
    private List<String> effects(JdbcClient snapshot) {
        List<String> entries = snapshot.sql("""
                        SELECT r.id, r.status,
                               count(*) FILTER (WHERE e.entry_type = 'REFUND_ACCEPTED') AS accepted,
                               count(*) FILTER (WHERE e.entry_type IN
                                   ('REFUND_EXECUTED', 'REFUND_FAILED', 'REFUND_CANCELLED')) AS released
                        FROM core.v_refund r
                        LEFT JOIN ledger.v_journal_entry e ON e.subject_type = 'refund' AND e.subject_id = r.id
                        GROUP BY r.id, r.status
                        HAVING count(*) FILTER (WHERE e.entry_type = 'REFUND_ACCEPTED') > 1
                            OR count(*) FILTER (WHERE e.entry_type IN
                                   ('REFUND_EXECUTED', 'REFUND_FAILED', 'REFUND_CANCELLED')) > 1
                            OR (r.status IN ('AWAITING_FUNDS', 'READY', 'SUBMITTED', 'UNKNOWN')
                                AND count(*) FILTER (WHERE e.entry_type IN
                                   ('REFUND_EXECUTED', 'REFUND_FAILED', 'REFUND_CANCELLED')) > 0)
                        ORDER BY r.id
                        """)
                .query((rs, row) -> "refund %s (%s): %d reservations, %d releases"
                        .formatted(
                                rs.getString("id"),
                                rs.getString("status"),
                                rs.getLong("accepted"),
                                rs.getLong("released")))
                .list();
        List<String> history = snapshot.sql("""
                        SELECT subject_id, count(*) AS finals FROM core.v_refund_transition
                        WHERE to_status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                        GROUP BY subject_id HAVING count(*) > 1 ORDER BY subject_id
                        """)
                .query((rs, row) -> "refund %s reached a final status %d times"
                        .formatted(rs.getString("subject_id"), rs.getLong("finals")))
                .list();
        return java.util.stream.Stream.concat(entries.stream(), history.stream())
                .toList();
    }
}
