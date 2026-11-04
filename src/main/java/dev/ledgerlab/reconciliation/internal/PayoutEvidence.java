package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reconstruct payout obligations and their bank identities from append-only evidence. */
final class PayoutEvidence {
    private PayoutEvidence() {}

    record Payout(
            String id,
            long amount,
            Instant reserved,
            String status,
            long transition,
            Instant start,
            @Nullable String paidTxn,
            @Nullable String returnedTxn,
            @Nullable String correction) {
        boolean outstanding() {
            return List.of("RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED").contains(status);
        }

        boolean matches(List<BankEvidence.Line> lines, @Nullable String txn, String direction) {
            return lines.stream()
                    .anyMatch(l -> l.id().equals(txn)
                            && l.reference().equals(id)
                            && l.amount() == amount
                            && l.currency().equals("CAD")
                            && l.direction().equals(direction)
                            && l.status().equals("BOOKED"));
        }

        boolean hasDebit(List<BankEvidence.Line> lines) {
            return lines.stream()
                    .anyMatch(l -> l.reference().equals(id)
                            && l.amount() == amount
                            && l.currency().equals("CAD")
                            && l.direction().equals("DEBIT")
                            && l.status().equals("BOOKED"));
        }

        List<Map<String, Object>> references(BankEvidence.Snapshot snapshot) {
            var refs = new ArrayList<Map<String, Object>>();
            refs.add(Map.of("payout_id", id, "transition_id", transition));
            if (correction != null) refs.add(Map.of("correction_id", correction));
            snapshot.lines().stream()
                    .filter(l -> l.reference().equals(id) || l.id().equals(paidTxn) || l.id().equals(returnedTxn))
                    .forEach(l -> refs.addAll(snapshot.refs(l)));
            return refs;
        }
    }

    static List<Payout> read(JdbcClient jdbc, Instant evidenceAsOf) {
        return jdbc.sql("""
                SELECT p.id, p.amount, reserved.occurred_at AS reserved_at,
                    h.to_status, h.id AS transition_id,
                    coalesce(start.occurred_at, reserved.occurred_at) AS start_at,
                    CASE WHEN paid.evidence_type='bank_txn' THEN paid.evidence_id
                        ELSE c.params->>'bankTxnId' END AS paid_txn,
                    returned.evidence_id AS returned_txn, c.id AS correction_id
                FROM core.payout p
                JOIN LATERAL (SELECT id,to_status FROM core.payout_status_history
                    WHERE payout_id=p.id AND recorded_at < :asOf
                    ORDER BY recorded_at DESC,id DESC LIMIT 1) h ON true
                JOIN LATERAL (SELECT occurred_at FROM core.payout_status_history
                    WHERE payout_id=p.id AND to_status='RESERVED' AND recorded_at < :asOf
                    ORDER BY recorded_at,id LIMIT 1) reserved ON true
                LEFT JOIN LATERAL (SELECT min(occurred_at) AS occurred_at FROM core.payout_status_history
                    WHERE payout_id=p.id AND to_status IN ('SUBMITTED','UNKNOWN','ACCEPTED')
                    AND recorded_at < :asOf) start ON true
                LEFT JOIN LATERAL (SELECT evidence_type,evidence_id FROM core.payout_status_history
                    WHERE payout_id=p.id AND to_status='PAID' AND recorded_at < :asOf
                    ORDER BY recorded_at,id LIMIT 1) paid ON true
                LEFT JOIN ledger.correction c ON paid.evidence_type='correction' AND c.id=paid.evidence_id
                    AND c.correction_type='CONFIRM_FAILED_PAYOUT_PAID' AND c.subject_type='payout'
                    AND c.subject_id=p.id AND c.recorded_at < :asOf
                LEFT JOIN LATERAL (SELECT evidence_id FROM core.payout_status_history
                    WHERE payout_id=p.id AND to_status='RETURNED' AND evidence_type='bank_txn'
                    AND recorded_at < :asOf ORDER BY recorded_at,id LIMIT 1) returned ON true
                WHERE p.recorded_at < :asOf AND reserved.occurred_at < :asOf ORDER BY p.id
                """)
                .param("asOf", evidenceAsOf.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Payout(
                        rs.getString("id"),
                        rs.getLong("amount"),
                        rs.getObject("reserved_at", OffsetDateTime.class).toInstant(),
                        rs.getString("to_status"),
                        rs.getLong("transition_id"),
                        rs.getObject("start_at", OffsetDateTime.class).toInstant(),
                        rs.getString("paid_txn"),
                        rs.getString("returned_txn"),
                        rs.getString("correction_id")))
                .list();
    }
}
