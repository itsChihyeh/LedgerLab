package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.RefundEvidence;
import dev.ledgerlab.refunds.RefundException;
import dev.ledgerlab.refunds.RefundQueue;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class JdbcRefundEvidence implements RefundEvidence {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PaymentRefunds payments;
    private final RefundQueue queue;
    private final RefundHistory history;
    private final IngestionIssues issues;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JdbcRefundEvidence(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentRefunds payments,
            RefundQueue queue,
            RefundHistory history,
            IngestionIssues issues,
            ObjectMapper mapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.queue = queue;
        this.history = history;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Result apply(ProcessorRefundFact fact) {
        ledger.lockFunds();
        String id = fact.refundId();
        if (id == null) {
            id = jdbc.sql("SELECT id FROM core.refund WHERE processor_refund_ref = :ref")
                    .param("ref", fact.processorRefundRef())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new RefundException("REFUND_NOT_FOUND"));
        }
        String paymentId = jdbc.sql("SELECT payment_id FROM core.refund WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new RefundException("REFUND_NOT_FOUND"));
        var payment = payments.lock(paymentId).orElseThrow();
        var refund = lock(id);
        String charge = payments.processorRef(paymentId);
        if ((fact.amount() != null && fact.amount().longValue() != refund.amount())
                || (fact.chargeRef() != null && !fact.chargeRef().equals(charge))) {
            issues.open(
                    "REFUND_STATE_CONFLICT",
                    "refund",
                    refund.id(),
                    mapper.writeValueAsString(Map.of("amount", refund.amount(), "charge", charge)),
                    mapper.writeValueAsString(fact),
                    mapper.writeValueAsString(
                            List.of(Map.of("source", fact.source().name(), "id", fact.evidenceId()))));
            return Result.CONFLICT;
        }
        String ref = fact.processorRefundRef();
        if ((ref != null && refund.ref() != null && !ref.equals(refund.ref()))
                || (ref != null
                        && jdbc.sql("SELECT count(*) FROM core.refund WHERE processor_refund_ref=:ref AND id<>:id")
                                        .param("ref", ref)
                                        .param("id", id)
                                        .query(Long.class)
                                        .single()
                                != 0)
                || refund.status().equals("AWAITING_FUNDS")
                || refund.status().equals("CANCELLED")) {
            return conflict(refund, fact);
        }
        String target = switch (fact.status()) {
            case pending -> "SUBMITTED";
            case succeeded -> "SUCCEEDED";
            case failed, canceled -> "FAILED";
        };
        boolean terminal =
                refund.status().equals("SUCCEEDED") || refund.status().equals("FAILED");
        if (terminal) {
            return target.equals("SUBMITTED") || target.equals(refund.status())
                    ? Result.DUPLICATE_FACT
                    : conflict(refund, fact);
        }
        if (target.equals(refund.status())) {
            if (refund.ref() == null && ref != null) {
                jdbc.sql("UPDATE core.refund SET processor_refund_ref=:ref WHERE id=:id")
                        .param("ref", ref)
                        .param("id", id)
                        .update();
                return Result.APPLIED;
            }
            return Result.DUPLICATE_FACT;
        }
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE core.refund SET status=:status, processor_refund_ref=coalesce(processor_refund_ref,:ref),
                    submitted_at=coalesce(submitted_at,
                        (SELECT first_sent_at FROM io.outbound_command WHERE subject_id=:id AND kind='SUBMIT_REFUND'),
                        :occurred),
                    resolved_at=:resolved, failure_reason=:reason WHERE id=:id
                """)
                .param("id", id)
                .param("status", target)
                .param("ref", ref)
                .param("occurred", fact.occurredAt().atOffset(ZoneOffset.UTC))
                .param("resolved", target.equals("SUBMITTED") ? null : now.atOffset(ZoneOffset.UTC))
                .param("reason", target.equals("FAILED") ? fact.failureReason() : null)
                .update();
        history.append(id, refund.status(), target, fact.source().name(), fact.evidenceId(), fact.occurredAt(), now);
        if (!target.equals("SUBMITTED")) {
            boolean success = target.equals("SUCCEEDED");
            ledger.post(new EntryRequest(
                    success ? EntryType.REFUND_EXECUTED : EntryType.REFUND_FAILED,
                    "refund:" + id + (success ? ":executed" : ":failed"),
                    fact.occurredAt(),
                    SubjectType.REFUND,
                    id,
                    List.of(
                            PostingLine.debit(Account.REFUND_PAYABLE, refund.amount()),
                            PostingLine.credit(
                                    success ? Account.PROCESSOR_RECEIVABLE : Account.MERCHANT_AVAILABLE,
                                    refund.amount()))));
            if (!success) {
                payments.restore(payment, refund.amount());
                queue.release();
            }
        }
        return Result.APPLIED;
    }

    /** An uncertain transport result never overrides a terminal processor fact. */
    void unknown(String id, String evidenceId, Instant occurredAt) {
        ledger.lockFunds();
        var refund = lock(id);
        if (!refund.status().equals("READY") && !refund.status().equals("SUBMITTED")) return;
        jdbc.sql("""
                UPDATE core.refund SET status='UNKNOWN', submitted_at=coalesce(submitted_at,
                    (SELECT first_sent_at FROM io.outbound_command WHERE subject_id=:id AND kind='SUBMIT_REFUND'))
                WHERE id=:id
                """).param("id", id).update();
        history.append(id, refund.status(), "UNKNOWN", "COMMAND_RESPONSE", evidenceId, occurredAt, clock.instant());
    }

    private Refund lock(String id) {
        return jdbc.sql("SELECT id, status, amount, processor_refund_ref FROM core.refund WHERE id=:id FOR UPDATE")
                .param("id", id)
                .query((rs, row) -> new Refund(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4)))
                .single();
    }

    private Result conflict(Refund refund, ProcessorRefundFact fact) {
        issues.open(
                "REFUND_STATE_CONFLICT",
                "refund",
                refund.id(),
                mapper.writeValueAsString(
                        Map.of("status", refund.status(), "processor_refund_ref", Objects.toString(refund.ref(), ""))),
                mapper.writeValueAsString(fact),
                mapper.writeValueAsString(List.of(Map.of("source", fact.source().name(), "id", fact.evidenceId()))));
        return Result.CONFLICT;
    }

    private record Refund(
            String id, String status, long amount, @Nullable String ref) {}
}
