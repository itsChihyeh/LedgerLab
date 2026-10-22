package dev.ledgerlab.payments;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Joins the caller's transaction; capture, history, issues and postings commit or roll back together. */
@Service
public final class PaymentCapture {
    private final Ledger ledger;
    private final JdbcClient jdbc;
    private final IngestionIssues issues;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper mapper;

    public PaymentCapture(
            Ledger ledger, JdbcClient jdbc, IngestionIssues issues, IdGenerator ids, Clock clock, ObjectMapper mapper) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.issues = issues;
        this.ids = ids;
        this.clock = clock;
        this.mapper = mapper;
    }

    public CaptureResult capture(ChargeEvidence evidence) {
        ledger.lockFunds();
        CaptureResult admission = admission(evidence);
        if (admission != CaptureResult.APPLIED) {
            openIssue(
                    evidence,
                    admission == CaptureResult.LIVEMODE ? "LIVEMODE_EVENT" : "UNSUPPORTED_EVENT",
                    Map.of("admission", "full CAD test-mode capture"),
                    Map.of("result", admission.name()));
            return admission;
        }

        long amount = Objects.requireNonNull(evidence.amount());
        var existing = jdbc.sql("SELECT amount FROM core.payment WHERE processor_payment_ref = :charge FOR UPDATE")
                .param("charge", evidence.chargeId())
                .query(Long.class)
                .optional();
        if (existing.isPresent()) {
            if (existing.get() == amount) {
                return CaptureResult.DUPLICATE_FACT;
            }
            openIssue(evidence, "EVENT_BUSINESS_CONFLICT", Map.of("amount", existing.get()), Map.of("amount", amount));
            return CaptureResult.BUSINESS_CONFLICT;
        }

        String paymentId = ids.next("pay");
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        var occurredAt = evidence.created().atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO core.payment
                    (id, processor_payment_ref, processor_intent_ref, amount, expected_fee, status,
                     source_event_id, occurred_at, received_at, recorded_at)
                VALUES (:id, :charge, :intent, :amount, :fee, 'CAPTURED', :event, :occurred, :received, :now)
                """)
                .param("id", paymentId)
                .param("charge", evidence.chargeId())
                .param("intent", evidence.paymentIntentId())
                .param("amount", amount)
                .param("fee", 30 + (amount * 29 + 500) / 1000)
                .param("event", evidence.eventId())
                .param("occurred", occurredAt)
                .param("received", evidence.receivedAt().atOffset(ZoneOffset.UTC))
                .param("now", now)
                .update();
        jdbc.sql("""
                INSERT INTO core.payment_transition
                    (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at)
                VALUES (:id, NULL, 'CAPTURED', 'inbox_event', :event, :occurred, :now)
                """)
                .param("id", paymentId)
                .param("event", evidence.eventId())
                .param("occurred", occurredAt)
                .param("now", now)
                .update();
        ledger.post(new EntryRequest(
                EntryType.PAYMENT_CAPTURED,
                "payment:" + evidence.chargeId() + ":captured",
                evidence.created(),
                SubjectType.PAYMENT,
                paymentId,
                List.of(
                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, amount),
                        PostingLine.credit(Account.MERCHANT_PENDING, amount))));
        return CaptureResult.APPLIED;
    }

    private static CaptureResult admission(ChargeEvidence evidence) {
        if (evidence.livemode()) {
            return CaptureResult.LIVEMODE;
        }
        Long amount = evidence.amount();
        if (amount == null || amount < 50 || amount > 99999999) {
            return CaptureResult.INVALID_AMOUNT;
        }
        if (!"cad".equalsIgnoreCase(evidence.currency())) {
            return CaptureResult.UNSUPPORTED_CURRENCY;
        }
        if (!"succeeded".equals(evidence.status())
                || !Boolean.TRUE.equals(evidence.captured())
                || !amount.equals(evidence.amountCaptured())) {
            return CaptureResult.UNSUPPORTED_CAPTURE;
        }
        return CaptureResult.APPLIED;
    }

    private void openIssue(ChargeEvidence evidence, String category, Object expected, Object actual) {
        issues.open(
                category,
                "event",
                evidence.eventId(),
                mapper.writeValueAsString(expected),
                mapper.writeValueAsString(actual),
                mapper.writeValueAsString(List.of(Map.of("table", "io.inbox_event", "id", evidence.eventId()))));
    }
}
