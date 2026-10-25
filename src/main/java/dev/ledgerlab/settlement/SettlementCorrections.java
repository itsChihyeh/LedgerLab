package dev.ledgerlab.settlement;

import dev.ledgerlab.ledger.Ledger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Narrow correction access. The caller owns the funds lock and the enclosing transaction. */
@Service
public final class SettlementCorrections {
    private final JdbcClient jdbc;
    private final Ledger ledger;

    public SettlementCorrections(JdbcClient jdbc, Ledger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public Optional<Batch> lockBatch(String id) {
        ledger.assertFundsLocked();
        return jdbc.sql("""
                SELECT b.batch_id,b.status,b.net,
                    EXISTS(SELECT 1 FROM recon.bank_line WHERE matched_batch_id=b.batch_id) AS deposited,
                    EXISTS(SELECT 1 FROM core.settlement_batch_line
                        WHERE line_type='PAYOUT_FAILURE' AND processor_ref=b.batch_id) AS returned
                FROM core.settlement_batch b WHERE b.batch_id=? FOR UPDATE
                """)
                .param(id)
                .query((rs, n) ->
                        new Batch(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), rs.getBoolean(5)))
                .optional();
    }

    /** Locks current versions in bank transaction ID order, after the batch row. */
    public List<BankLine> lockBankLines(List<String> ids) {
        ledger.assertFundsLocked();
        return ids.stream()
                .sorted()
                .flatMap(id -> jdbc
                        .sql("""
                SELECT bank_txn_id,version,status,direction,currency,match_status,reference,amount
                FROM recon.bank_line WHERE bank_txn_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE
                """)
                        .param(id)
                        .query((rs, n) -> new BankLine(
                                rs.getString(1),
                                rs.getInt(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getLong(8)))
                        .optional()
                        .stream())
                .toList();
    }

    public void markApplied(BankLine line, String batchId) {
        ledger.assertFundsLocked();
        int changed = jdbc.sql("""
                UPDATE recon.bank_line SET match_status='APPLIED',matched_batch_id=?
                WHERE bank_txn_id=? AND version=? AND match_status='UNAPPLIED'
                    AND status='BOOKED' AND direction='CREDIT' AND currency='CAD' AND reference=?
                """)
                .params(batchId, line.id(), line.version(), batchId)
                .update();
        if (changed != 1) throw new IllegalStateException("Bank line is no longer eligible for application");
    }

    public void markBanked(Batch batch, String correctionId, Instant at) {
        ledger.assertFundsLocked();
        if (!List.of("CONFIRMED", "PAYOUT_FAILED").contains(batch.status()))
            throw new IllegalArgumentException("Batch is not eligible for a bank correction");
        var now = at.atOffset(ZoneOffset.UTC);
        int changed = jdbc.sql("""
                UPDATE core.settlement_batch SET status='BANKED',updated_at=?
                WHERE batch_id=? AND status=?
                """).params(now, batch.id(), batch.status()).update();
        if (changed != 1) throw new IllegalStateException("Batch is no longer eligible for a bank correction");
        jdbc.sql("""
                INSERT INTO core.batch_transition
                    (subject_id,from_status,to_status,evidence_type,evidence_id,occurred_at,recorded_at,lines_fetch_id)
                SELECT batch_id,?,'BANKED','correction',?,?,?,lines_fetch_id
                FROM core.settlement_batch WHERE batch_id=?
                """).params(batch.status(), correctionId, now, now, batch.id()).update();
    }

    public record Batch(String id, String status, long net, boolean deposited, boolean returned) {}

    public record BankLine(
            String id,
            int version,
            String status,
            String direction,
            String currency,
            String matchStatus,
            String reference,
            long amount) {
        public boolean unapplied() {
            return status.equals("BOOKED")
                    && direction.equals("CREDIT")
                    && currency.equals("CAD")
                    && matchStatus.equals("UNAPPLIED");
        }
    }
}
