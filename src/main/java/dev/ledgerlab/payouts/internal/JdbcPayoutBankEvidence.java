package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payouts.PayoutBankEvidence;
import dev.ledgerlab.refunds.RefundQueue;
import dev.ledgerlab.shared.BusinessCalendar;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class JdbcPayoutBankEvidence implements PayoutBankEvidence {
    private static final Pattern RETURN_REASON = Pattern.compile("(?:^|\\s)RETURN\\s+(\\S+)");
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final RefundQueue queue;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public JdbcPayoutBankEvidence(
            JdbcClient jdbc, Ledger ledger, RefundQueue queue, BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.queue = queue;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Override
    public Result applyDebit(Line line) {
        var found = lock(line);
        if (found.isEmpty()) return new Result("UNMATCHED_NO_POSTING", null, "UNMATCHED_BANK_LINE");
        var payout = found.get();
        var candidate = payout.candidate();
        if (candidate.status().equals("FAILED"))
            return new Result("UNMATCHED_NO_POSTING", candidate, "PAYOUT_STATE_CONFLICT");
        if (!List.of("RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED").contains(candidate.status())
                || candidate.amount() != line.amount()
                || payout.bankTxnId() != null)
            return new Result("UNMATCHED_NO_POSTING", candidate, "UNMATCHED_BANK_LINE");
        post(line, EntryType.PAYOUT_PAID, Account.PAYOUT_IN_TRANSIT, Account.BANK_CASH);
        jdbc.sql("""
                UPDATE core.payout SET status='PAID', bank_txn_id=:txn, paid_at=:occurred, updated_at=:now
                WHERE id=:id
                """)
                .param("id", candidate.id())
                .param("txn", line.bankTxnId())
                .param("occurred", calendar.startOfDay(line.bookingDate()).atOffset(ZoneOffset.UTC))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        history(line, candidate.status(), "PAID");
        return new Result("PAYOUT_PAID", candidate, null);
    }

    @Override
    public Result applyCredit(Line line) {
        var found = lock(line);
        if (found.isEmpty()) return new Result("UNAPPLIED", null, "UNMATCHED_BANK_LINE");
        var payout = found.get();
        var candidate = payout.candidate();
        if (!candidate.status().equals("PAID")
                || candidate.amount() != line.amount()
                || payout.returnBankTxnId() != null)
            return new Result(
                    "UNAPPLIED",
                    candidate,
                    candidate.status().equals("FAILED") ? "PAYOUT_STATE_CONFLICT" : "UNMATCHED_BANK_LINE");
        post(line, EntryType.PAYOUT_RETURNED, Account.BANK_CASH, Account.MERCHANT_AVAILABLE);
        var reason = RETURN_REASON.matcher(line.memo());
        jdbc.sql("""
                UPDATE core.payout SET status='RETURNED', return_bank_txn_id=:txn, returned_at=:occurred,
                    return_reason=:reason, updated_at=:now WHERE id=:id
                """)
                .param("id", candidate.id())
                .param("txn", line.bankTxnId())
                .param("occurred", calendar.startOfDay(line.bookingDate()).atOffset(ZoneOffset.UTC))
                .param("reason", reason.find() ? reason.group(1) : "UNKNOWN")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        history(line, candidate.status(), "RETURNED");
        queue.release();
        return new Result("PAYOUT_RETURNED", candidate, null);
    }

    private Optional<Payout> lock(Line line) {
        ledger.assertFundsLocked();
        return jdbc.sql(
                        "SELECT id, status, amount, bank_txn_id, return_bank_txn_id FROM core.payout WHERE id=:id FOR UPDATE")
                .param("id", line.reference())
                .query((rs, n) -> new Payout(
                        new Candidate(rs.getString(1), rs.getString(2), rs.getLong(3)),
                        rs.getString(4),
                        rs.getString(5)))
                .optional();
    }

    private void post(Line line, EntryType type, Account debit, Account credit) {
        ledger.post(new EntryRequest(
                type,
                "bank_txn:" + line.bankTxnId(),
                calendar.startOfDay(line.bookingDate()),
                SubjectType.PAYOUT,
                line.reference(),
                List.of(PostingLine.debit(debit, line.amount()), PostingLine.credit(credit, line.amount()))));
    }

    private void history(Line line, String from, String to) {
        jdbc.sql("""
                INSERT INTO core.payout_status_history(payout_id, from_status, to_status, evidence_type,
                    evidence_id, occurred_at, recorded_at)
                VALUES (:id,:from,:to,'bank_txn',:evidence,:occurred,:now)
                """)
                .param("id", line.reference())
                .param("from", from)
                .param("to", to)
                .param("evidence", line.bankTxnId())
                .param("occurred", calendar.startOfDay(line.bookingDate()).atOffset(ZoneOffset.UTC))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private record Payout(
            Candidate candidate,
            @Nullable String bankTxnId,
            @Nullable String returnBankTxnId) {}
}
