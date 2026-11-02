package dev.ledgerlab.corrections.internal;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.CorrectionException;
import dev.ledgerlab.corrections.CorrectionResult;
import dev.ledgerlab.corrections.Corrections;
import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionRequest;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.NegativeBalanceException;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.payouts.PayoutCorrections;
import dev.ledgerlab.reconciliation.IssueDisposition;
import dev.ledgerlab.settlement.BatchRechecks;
import dev.ledgerlab.settlement.SettlementCorrections;
import dev.ledgerlab.shared.Fees;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public final class JdbcCorrections implements Corrections {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PaymentCorrections payments;
    private final IssueDisposition issues;
    private final BatchRechecks rechecks;
    private final SettlementCorrections settlement;
    private final PayoutCorrections payouts;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Runnable afterReversal;

    @Autowired
    public JdbcCorrections(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentCorrections payments,
            IssueDisposition issues,
            BatchRechecks rechecks,
            SettlementCorrections settlement,
            PayoutCorrections payouts,
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this(jdbc, ledger, payments, issues, rechecks, settlement, payouts, clock, mapper, manager, () -> {});
    }

    /** Test seam after ledger writes, before business state changes; no production failure setting. */
    JdbcCorrections(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentCorrections payments,
            IssueDisposition issues,
            BatchRechecks rechecks,
            SettlementCorrections settlement,
            PayoutCorrections payouts,
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            Runnable afterReversal) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.issues = issues;
        this.rechecks = rechecks;
        this.settlement = settlement;
        this.payouts = payouts;
        this.clock = clock;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.afterReversal = afterReversal;
    }

    @Override
    public CorrectionResult submit(CorrectionCommand command) {
        try {
            return Objects.requireNonNull(transactions.execute(tx -> {
                ledger.lockFunds();
                return switch (command.type()) {
                    case VOID_CAPTURE, RESTATE_CAPTURE_AMOUNT -> correctCapture(command);
                    case APPLY_UNAPPLIED_CASH, RESTORE_FAILED_PAYOUT -> correctBank(command);
                    case CONFIRM_FAILED_PAYOUT_PAID -> correctPayout(command);
                };
            }));
        } catch (NegativeBalanceException error) {
            if (command.type() == CorrectionType.CONFIRM_FAILED_PAYOUT_PAID && error.account() == Account.BANK_CASH)
                throw new CorrectionException(409, "CASH_WOULD_GO_NEGATIVE");
            throw error;
        } catch (org.springframework.dao.DataIntegrityViolationException error) {
            if (command.type() == CorrectionType.CONFIRM_FAILED_PAYOUT_PAID) {
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.sql.SQLException sql
                            && "23514".equals(sql.getSQLState())
                            && String.valueOf(sql.getMessage()).contains("account_non_negative"))
                        throw new CorrectionException(409, "CASH_WOULD_GO_NEGATIVE");
                }
            }
            throw error;
        }
    }

    private record Entry(
            String type,
            String subjectType,
            String subjectId,
            @Nullable String correctionId) {}

    private CorrectionResult correctCapture(CorrectionCommand command) {
        boolean restate = command.type() == CorrectionType.RESTATE_CAPTURE_AMOUNT;
        if (!CorrectionCommand.text(command.originalEntryId())
                || (command.reasonCode()
                                != (restate
                                        ? CorrectionReason.CAPTURE_AMOUNT_ERROR
                                        : CorrectionReason.CAPTURE_RECORDED_IN_ERROR)
                        && command.reasonCode() != CorrectionReason.OTHER))
            throw new CorrectionException(400, "INVALID_REQUEST");
        if (restate
                && (command.newAmount() == null
                        || command.newAmount() < 50
                        || command.newAmount() > 99999999
                        || command.evidence().stream()
                                .noneMatch(e -> List.of("issue", "statement_line", "processor_fetch", "inbox_event")
                                        .contains(e.type())))) throw new CorrectionException(400, "INVALID_REQUEST");
        for (var evidence : command.evidence()) checkEvidence(evidence);
        String entryId = Objects.requireNonNull(command.originalEntryId());
        Entry entry = jdbc.sql(
                        "SELECT entry_type, subject_type, subject_id, correction_id FROM ledger.journal_entry WHERE id=?")
                .param(entryId)
                .query((rs, n) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional()
                .orElseThrow(() -> new CorrectionException(404, "ENTRY_NOT_FOUND"));
        if (entry.type().equals("REVERSAL")) throw new CorrectionException(409, "CANNOT_REVERSE_REVERSAL");
        ledger.findCorrectionForEntry(entryId).ifPresent(target -> {
            throw new CorrectionException(409, "ALREADY_REVERSED", target.correctionId());
        });
        boolean amountEntry = entry.type().equals("PAYMENT_CAPTURED")
                || (entry.type().equals("CORRECTION")
                        && entry.correctionId() != null
                        && ledger.findCorrection(entry.correctionId())
                                .filter(c -> c.type() == CorrectionType.RESTATE_CAPTURE_AMOUNT
                                        && c.subjectType() == SubjectType.PAYMENT
                                        && c.subjectId().equals(entry.subjectId()))
                                .isPresent());
        if (!entry.subjectType().equals("payment") || !amountEntry)
            throw new CorrectionException(409, "ENTRY_NOT_CORRECTABLE");
        var payment = payments.lock(entry.subjectId())
                .orElseThrow(() -> new CorrectionException(409, "ENTRY_NOT_CORRECTABLE"));
        if (payment.status().equals("SETTLED") || payment.batchId() != null)
            throw new CorrectionException(409, "USE_REFUND_FOR_SETTLED_PAYMENT");
        long newAmount = restate ? Objects.requireNonNull(command.newAmount()) : payment.amount();
        if (restate) {
            if (newAmount == payment.amount()) throw new CorrectionException(409, "AMOUNT_UNCHANGED");
            if (newAmount < payment.refundedAmount()) throw new CorrectionException(409, "AMOUNT_BELOW_REFUNDED");
        } else if (payment.refundedAmount() > 0) throw new CorrectionException(409, "PAYMENT_HAS_REFUNDS");
        if (!payment.status().equals("CAPTURED")) throw new CorrectionException(409, "ENTRY_NOT_CORRECTABLE");
        var now = clock.instant();
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("originalEntryId", entryId);
        params.put("oldAmount", payment.amount());
        params.put("expectedFee", payment.expectedFee());
        if (restate) {
            params.put("newAmount", newAmount);
            params.put("newExpectedFee", Fees.contractFee(newAmount));
        }
        var recorded = ledger.recordCorrection(new CorrectionRequest(
                command.type(),
                command.reasonCode(),
                command.reasonNote(),
                mapper.writeValueAsString(command.evidence()),
                mapper.writeValueAsString(params),
                SubjectType.PAYMENT,
                payment.id(),
                command.requestedBy(),
                List.of(entryId),
                List.of(),
                restate
                        ? List.of(
                                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, newAmount),
                                PostingLine.credit(Account.MERCHANT_PENDING, newAmount))
                        : List.of(),
                now));
        afterReversal.run();
        List<String> advanced;
        if (restate) {
            payments.restateAmount(payment.id(), newAmount, recorded.correctionId(), now);
            advanced = rechecks.recheckAmount(payments.processorRef(payment.id()), recorded.correctionId());
        } else {
            payments.voidCapture(payment.id(), recorded.correctionId(), now);
            advanced = List.of();
        }
        var issueIds = new java.util.TreeSet<String>();
        for (String batchId : advanced)
            issueIds.addAll(jdbc.sql("""
                SELECT id FROM recon.issue WHERE subject_type='batch' AND subject_id=? AND status='OPEN'
                """).param(batchId).query(String.class).list());
        command.evidence().stream()
                .filter(e -> e.type().equals("issue"))
                .map(CorrectionCommand.Evidence::id)
                .filter(id -> !restate
                        || jdbc.sql(
                                        "SELECT subject_type<>'batch' OR subject_id IN (:batches) FROM recon.issue WHERE id=:id")
                                .param("batches", advanced.isEmpty() ? List.of("") : advanced)
                                .param("id", id)
                                .query(Boolean.class)
                                .single())
                .forEach(issueIds::add);
        issueIds.forEach(id -> issues.resolveByCorrection(id, recorded.correctionId(), command.reasonNote()));
        return new CorrectionReader(jdbc, mapper, false).detail(recorded.correctionId());
    }

    private CorrectionResult correctBank(CorrectionCommand command) {
        boolean restore = command.type() == CorrectionType.RESTORE_FAILED_PAYOUT;
        var ids = command.bankTxnIds();
        if (!CorrectionCommand.text(command.batchId())
                || ids == null
                || ids.isEmpty()
                || ids.size() > (restore ? 1 : 10)
                || ids.stream().anyMatch(id -> !CorrectionCommand.text(id))
                || ids.stream().distinct().count() != ids.size()
                || (command.reasonCode()
                                != (restore
                                        ? CorrectionReason.PAYOUT_FAILURE_DISPROVED
                                        : CorrectionReason.SPLIT_DEPOSIT)
                        && command.reasonCode() != CorrectionReason.OTHER))
            throw new CorrectionException(400, "INVALID_REQUEST");
        for (var evidence : command.evidence()) checkEvidence(evidence);
        String batchId = Objects.requireNonNull(command.batchId());
        var batch = settlement.lockBatch(batchId).orElseThrow(() -> new CorrectionException(404, "BATCH_NOT_FOUND"));
        if (!batch.status().equals(restore ? "PAYOUT_FAILED" : "CONFIRMED") || (!restore && batch.deposited())) {
            var prior =
                    jdbc.sql("""
                    SELECT id FROM ledger.correction WHERE subject_type='batch' AND subject_id=?
                        AND correction_type IN ('APPLY_UNAPPLIED_CASH','RESTORE_FAILED_PAYOUT')
                    ORDER BY recorded_at,id LIMIT 1
                    """).param(batchId).query(String.class).optional().orElse(null);
            throw new CorrectionException(
                    409, restore ? "BATCH_NOT_PAYOUT_FAILED" : "BATCH_NOT_AWAITING_DEPOSIT", prior);
        }
        var lines = settlement.lockBankLines(ids);
        if (lines.size() != ids.size() || lines.stream().anyMatch(line -> !line.unapplied()))
            throw new CorrectionException(409, "BANK_LINE_NOT_UNAPPLIED");
        if (lines.stream().anyMatch(line -> !line.reference().equals(batchId)))
            throw new CorrectionException(409, "BANK_LINE_REFERENCE_MISMATCH");
        long total =
                lines.stream().mapToLong(SettlementCorrections.BankLine::amount).reduce(0, Math::addExact);
        if (total != batch.net()) throw new CorrectionException(409, "APPLICATION_AMOUNT_MISMATCH");
        if (restore && batch.returned()) throw new CorrectionException(409, "FAILED_PAYOUT_ALREADY_RETURNED");
        var applied = lines.stream()
                .map(line -> jdbc.sql("""
                SELECT id FROM ledger.journal_entry WHERE entry_type='UNAPPLIED_CASH_RECEIVED'
                    AND source_key=? AND subject_type='bank_txn' AND subject_id=?
                """)
                        .params("bank_txn:" + line.id(), line.id())
                        .query(String.class)
                        .single())
                .toList();
        var reversed = restore
                ? List.of(jdbc.sql("""
                SELECT id FROM ledger.journal_entry WHERE entry_type='BATCH_PAYOUT_FAILED'
                    AND source_key=? AND subject_type='batch' AND subject_id=?
                """)
                        .params("batch:" + batchId + ":payout_failed", batchId)
                        .query(String.class)
                        .single())
                : List.<String>of();
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("batchId", batchId);
        if (restore) params.put("bankTxnId", ids.getFirst());
        else params.put("bankTxnIds", ids);
        var now = clock.instant();
        var recorded = ledger.recordCorrection(new CorrectionRequest(
                command.type(),
                command.reasonCode(),
                command.reasonNote(),
                mapper.writeValueAsString(command.evidence()),
                mapper.writeValueAsString(params),
                SubjectType.BATCH,
                batchId,
                command.requestedBy(),
                reversed,
                applied,
                List.of(
                        PostingLine.debit(Account.UNAPPLIED_CASH, total),
                        PostingLine.credit(Account.SETTLEMENT_IN_TRANSIT, total)),
                now));
        afterReversal.run();
        for (var line : lines) settlement.markApplied(line, batchId);
        settlement.markBanked(batch, recorded.correctionId(), now);
        var issueIds = new java.util.TreeSet<>(jdbc.sql("""
                SELECT id FROM recon.issue WHERE (subject_type='bank_txn' AND subject_id IN (:banks))
                    OR (subject_type='batch' AND subject_id=:batch AND category IN (:categories))
                """)
                .param("banks", ids)
                .param("batch", batchId)
                .param(
                        "categories",
                        restore
                                ? List.of("BATCH_STATE_CONFLICT")
                                : List.of("DEPOSIT_MISSING", "DEPOSIT_NOT_RECEIVED", "TIMING_DIFFERENCE"))
                .query(String.class)
                .list());
        command.evidence().stream()
                .filter(e -> e.type().equals("issue"))
                .map(CorrectionCommand.Evidence::id)
                .filter(id -> jdbc.sql("SELECT subject_type<>'batch' OR subject_id=? FROM recon.issue WHERE id=?")
                        .params(batchId, id)
                        .query(Boolean.class)
                        .single())
                .forEach(issueIds::add);
        issueIds.forEach(id -> issues.resolveByCorrection(id, recorded.correctionId(), command.reasonNote()));
        return new CorrectionReader(jdbc, mapper, false).detail(recorded.correctionId());
    }

    private CorrectionResult correctPayout(CorrectionCommand command) {
        var ids = command.bankTxnIds();
        if (!CorrectionCommand.text(command.payoutId())
                || ids == null
                || ids.size() != 1
                || (command.reasonCode() != CorrectionReason.PAYOUT_FAILURE_DISPROVED
                        && command.reasonCode() != CorrectionReason.OTHER)
                || command.evidence().stream()
                        .noneMatch(e -> e.type().equals("bank_txn") && e.id().equals(ids.getFirst())))
            throw new CorrectionException(400, "INVALID_REQUEST");
        for (var evidence : command.evidence()) checkEvidence(evidence);
        String payoutId = Objects.requireNonNull(command.payoutId());
        var payout = jdbc.sql("SELECT status,amount FROM core.payout WHERE id=? FOR UPDATE")
                .param(payoutId)
                .query((rs, n) -> new Payout(rs.getString(1), rs.getLong(2)))
                .optional()
                .orElseThrow(() -> new CorrectionException(404, "PAYOUT_NOT_FOUND"));
        if (!payout.status().equals("FAILED")) {
            var prior =
                    jdbc.sql("""
                    SELECT id FROM ledger.correction WHERE subject_type='payout' AND subject_id=?
                        AND correction_type='CONFIRM_FAILED_PAYOUT_PAID' ORDER BY recorded_at,id LIMIT 1
                    """).param(payoutId).query(String.class).optional().orElse(null);
            throw new CorrectionException(409, "PAYOUT_NOT_FAILED", prior);
        }
        String bankId = ids.getFirst();
        var line = jdbc.sql("""
                SELECT version,status,direction,currency,match_status,reference,amount,booking_date
                FROM recon.bank_line WHERE bank_txn_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE
                """)
                .param(bankId)
                .query((rs, n) -> new PayoutDebit(
                        rs.getInt(1),
                        rs.getString(2).equals("BOOKED")
                                && rs.getString(3).equals("DEBIT")
                                && rs.getString(4).equals("CAD")
                                && rs.getString(5).equals("UNMATCHED_NO_POSTING")
                                && rs.getString(6).equals(payoutId),
                        rs.getLong(7),
                        rs.getObject(8, java.time.LocalDate.class)))
                .optional()
                .orElseThrow(() -> new CorrectionException(409, "BANK_LINE_NOT_PAYOUT_DEBIT"));
        if (!line.eligible()) throw new CorrectionException(409, "BANK_LINE_NOT_PAYOUT_DEBIT");
        if (line.amount() != payout.amount()) throw new CorrectionException(409, "APPLICATION_AMOUNT_MISMATCH");
        String entryId = jdbc.sql("""
                SELECT id FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'
                    AND source_key=? AND subject_type='payout' AND subject_id=?
                """)
                .params("payout:" + payoutId + ":failed", payoutId)
                .query(String.class)
                .single();
        ledger.findCorrectionForEntry(entryId).ifPresent(target -> {
            throw new CorrectionException(409, "ALREADY_REVERSED", target.correctionId());
        });
        var now = clock.instant();
        var recorded = ledger.recordCorrection(new CorrectionRequest(
                command.type(),
                command.reasonCode(),
                command.reasonNote(),
                mapper.writeValueAsString(command.evidence()),
                mapper.writeValueAsString(java.util.Map.of("payoutId", payoutId, "bankTxnId", bankId)),
                SubjectType.PAYOUT,
                payoutId,
                command.requestedBy(),
                List.of(entryId),
                List.of(),
                List.of(
                        PostingLine.debit(Account.PAYOUT_IN_TRANSIT, payout.amount()),
                        PostingLine.credit(Account.BANK_CASH, payout.amount())),
                now));
        afterReversal.run();
        payouts.confirmFailedPaid(payoutId, bankId, line.bookingDate(), recorded.correctionId());
        int changed = jdbc.sql("""
                UPDATE recon.bank_line SET match_status='PAYOUT_PAID',matched_payout_id=?
                WHERE bank_txn_id=? AND version=? AND match_status='UNMATCHED_NO_POSTING'
                """).params(payoutId, bankId, line.version()).update();
        if (changed != 1) throw new IllegalStateException("Bank line is no longer eligible for payout correction");
        var issueIds = new java.util.TreeSet<>(
                jdbc.sql("""
                SELECT id FROM recon.issue WHERE category IN ('PAYOUT_STATE_CONFLICT','UNMATCHED_BANK_LINE')
                    AND ((subject_type='payout' AND subject_id=?) OR (subject_type='bank_txn' AND subject_id=?))
                """).params(payoutId, bankId).query(String.class).list());
        command.evidence().stream()
                .filter(e -> e.type().equals("issue"))
                .map(CorrectionCommand.Evidence::id)
                .filter(id -> jdbc.sql("SELECT subject_type<>'batch' FROM recon.issue WHERE id=?")
                        .param(id)
                        .query(Boolean.class)
                        .single())
                .forEach(issueIds::add);
        issueIds.forEach(id -> issues.resolveByCorrection(id, recorded.correctionId(), command.reasonNote()));
        return new CorrectionReader(jdbc, mapper, false).detail(recorded.correctionId());
    }

    private record Payout(String status, long amount) {}

    private record PayoutDebit(int version, boolean eligible, long amount, java.time.LocalDate bookingDate) {}

    private void checkEvidence(CorrectionCommand.Evidence evidence) {
        String sql = switch (evidence.type()) {
            case "issue" -> "SELECT EXISTS (SELECT 1 FROM recon.issue WHERE id=?)";
            case "statement_line" ->
                "SELECT EXISTS (SELECT 1 FROM recon.statement_line WHERE statement_line_id=? UNION ALL SELECT 1 FROM core.settlement_batch_line WHERE balance_txn_id=?)";
            case "bank_txn" -> "SELECT EXISTS (SELECT 1 FROM recon.bank_line WHERE bank_txn_id=?)";
            case "inbox_event" -> "SELECT EXISTS (SELECT 1 FROM io.inbox_event WHERE event_id=?)";
            case "processor_fetch" -> "SELECT EXISTS (SELECT 1 FROM io.processor_fetch WHERE id::text=?)";
            default -> throw new CorrectionException(400, "INVALID_REQUEST");
        };
        var query = jdbc.sql(sql).param(evidence.id());
        if (evidence.type().equals("statement_line")) query = query.param(evidence.id());
        if (!query.query(Boolean.class).single()) throw new CorrectionException(400, "EVIDENCE_NOT_FOUND");
    }
}
