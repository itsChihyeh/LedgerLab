package dev.ledgerlab.payouts;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.shared.BusinessCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Narrow correction-only transition; the caller owns the funds, payout and bank row locks. */
@Service
public final class PayoutCorrections {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public PayoutCorrections(JdbcClient jdbc, Ledger ledger, BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.calendar = calendar;
        this.clock = clock;
    }

    public void confirmFailedPaid(String payoutId, String bankTxnId, LocalDate bookingDate, String correctionId) {
        ledger.assertFundsLocked();
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        var paidAt = calendar.startOfDay(bookingDate).atOffset(ZoneOffset.UTC);
        int changed = jdbc.sql("""
                UPDATE core.payout SET status='PAID',bank_txn_id=?,paid_at=?,updated_at=?
                WHERE id=? AND status='FAILED' AND EXISTS (
                    SELECT 1 FROM ledger.correction WHERE id=? AND subject_type='payout'
                        AND subject_id=? AND correction_type='CONFIRM_FAILED_PAYOUT_PAID')
                """)
                .params(bankTxnId, paidAt, now, payoutId, correctionId, payoutId)
                .update();
        if (changed != 1) throw new IllegalStateException("Payout is not eligible for this correction");
        jdbc.sql("""
                INSERT INTO core.payout_status_history(payout_id,from_status,to_status,evidence_type,
                    evidence_id,occurred_at,recorded_at)
                VALUES (?,'FAILED','PAID','correction',?,?,?)
                """).params(payoutId, correctionId, now, now).update();
    }
}
