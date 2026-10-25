package dev.ledgerlab.corrections.internal;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.CorrectionException;
import dev.ledgerlab.corrections.CorrectionResult;
import dev.ledgerlab.corrections.Corrections;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionRequest;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.reconciliation.IssueDisposition;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this(jdbc, ledger, payments, issues, clock, mapper, manager, () -> {});
    }

    /** Test seam after ledger writes, before business state changes; no production failure setting. */
    JdbcCorrections(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentCorrections payments,
            IssueDisposition issues,
            Clock clock,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            Runnable afterReversal) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.issues = issues;
        this.clock = clock;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.afterReversal = afterReversal;
    }

    @Override
    public CorrectionResult submit(CorrectionCommand command) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            ledger.lockFunds();
            return switch (command.type()) {
                case VOID_CAPTURE -> voidCapture(command);
                default -> throw new CorrectionException(409, "CORRECTION_TYPE_NOT_AVAILABLE");
            };
        }));
    }

    private record Entry(
            String type,
            String subjectType,
            String subjectId,
            @Nullable String correctionId) {}

    private CorrectionResult voidCapture(CorrectionCommand command) {
        if (!CorrectionCommand.text(command.originalEntryId())
                || (command.reasonCode() != CorrectionReason.CAPTURE_RECORDED_IN_ERROR
                        && command.reasonCode() != CorrectionReason.OTHER))
            throw new CorrectionException(400, "INVALID_REQUEST");
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
        if (payment.refundedAmount() > 0) throw new CorrectionException(409, "PAYMENT_HAS_REFUNDS");
        if (!payment.status().equals("CAPTURED")) throw new CorrectionException(409, "ENTRY_NOT_CORRECTABLE");
        var now = clock.instant();
        var recorded = ledger.recordCorrection(new CorrectionRequest(
                command.type(),
                command.reasonCode(),
                command.reasonNote(),
                mapper.writeValueAsString(command.evidence()),
                mapper.writeValueAsString(Map.of(
                        "originalEntryId",
                        entryId,
                        "oldAmount",
                        payment.amount(),
                        "expectedFee",
                        payment.expectedFee())),
                SubjectType.PAYMENT,
                payment.id(),
                command.requestedBy(),
                List.of(entryId),
                List.of(),
                List.of(),
                now));
        afterReversal.run();
        payments.voidCapture(payment.id(), recorded.correctionId(), now);
        command.evidence().stream()
                .filter(e -> e.type().equals("issue"))
                .map(CorrectionCommand.Evidence::id)
                .distinct()
                .sorted()
                .forEach(id -> issues.resolveByCorrection(id, recorded.correctionId(), command.reasonNote()));
        return new CorrectionReader(jdbc, mapper, false).detail(recorded.correctionId());
    }

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
