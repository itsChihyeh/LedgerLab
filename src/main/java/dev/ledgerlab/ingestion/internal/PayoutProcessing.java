package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ingestion.StripeReader.FetchPurpose;
import dev.ledgerlab.ingestion.StripeReader.Payout;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import dev.ledgerlab.refunds.RefundSettlement;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.settlement.PayoutEvidence.Lines;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** HTTP and processor mapping stay on the ingestion side of the settlement boundary. */
@Component
final class PayoutProcessing {
    private final StripeReader reader;
    private final BatchSettlement settlement;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProcessorRefundGateway refundGateway;
    private final RefundSettlement refunds;

    PayoutProcessing(
            StripeReader reader,
            BatchSettlement settlement,
            JdbcClient jdbc,
            ObjectMapper mapper,
            Clock clock,
            ProcessorRefundGateway refundGateway,
            RefundSettlement refunds) {
        this.reader = reader;
        this.settlement = settlement;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.refundGateway = refundGateway;
        this.refunds = refunds;
    }

    @Nullable
    PayoutEvidence prepare(String eventId, String eventType, boolean live, byte[] payload) {
        if (live
                || !(eventType.equals("payout.created")
                        || eventType.equals("payout.reconciliation_completed")
                        || eventType.equals("payout.paid")
                        || eventType.equals("payout.failed")
                        || eventType.equals("payout.canceled"))) return null;
        Payout payout =
                StripeReadParser.payout(mapper.readTree(payload).path("data").path("object"));
        return prepare(eventId, eventType, "inbox_event", eventId, payout);
    }

    PayoutEvidence prepare(
            String sourceEvent, String eventType, String evidenceType, String evidenceId, Payout payout) {
        PayoutEvidence evidence = evidence(sourceEvent, eventType, evidenceType, evidenceId, payout, null);
        if ((eventType.equals("payout.created") || eventType.equals("payout.reconciliation_completed"))
                && evidence.supported()
                && evidence.ready()
                && settlement.needsLines(evidence)) {
            var result = reader.listBalanceTransactionsForPayout(payout.id(), FetchPurpose.BATCH_LINES, sourceEvent);
            if (!result.complete())
                throw new IllegalStateException("Incomplete payout lines; fetch=" + result.fetchId());
            var lines = new PayoutEvidence.Lines(
                    result.fetchId(),
                    result.items().stream()
                            .map(line -> new PayoutEvidence.Line(
                                    line.id(),
                                    line.type(),
                                    line.source(),
                                    line.amount(),
                                    line.fee(),
                                    line.net(),
                                    line.currency(),
                                    line.created()))
                            .toList());
            evidence = evidence(
                    sourceEvent,
                    eventType,
                    evidenceType,
                    evidenceId,
                    payout,
                    new Lines(lines.fetchId(), lines.items(), refundIdentities(lines.items(), null)));
        }
        return evidence;
    }

    void recheckByPayment(String paymentRef, String captureEventId) {
        for (var batch : settlement.unknownPaymentBatches()) {
            String raw = jdbc.sql("""
                    SELECT response_body FROM io.processor_fetch
                    WHERE id = :id AND complete AND purpose = 'BATCH_LINES'
                      AND request->'query'->>'payout' = :batch
                    """)
                    .param("id", batch.fetchId())
                    .param("batch", batch.batchId())
                    .query(String.class)
                    .single();
            var items = new ArrayList<PayoutEvidence.Line>();
            for (var page : mapper.readTree(raw)) {
                for (var node : mapper.readTree(page.asString()).path("data")) {
                    var line = StripeReadParser.balanceTransaction(node);
                    items.add(new PayoutEvidence.Line(
                            line.id(),
                            line.type(),
                            line.source(),
                            line.amount(),
                            line.fee(),
                            line.net(),
                            line.currency(),
                            line.created()));
                }
            }
            settlement.recheckByPayment(
                    paymentRef,
                    new PayoutEvidence(
                            batch.sourceEventId(),
                            "inbox_event",
                            captureEventId,
                            "payout.reconciliation_completed",
                            batch.batchId(),
                            batch.amount(),
                            batch.currency(),
                            batch.automatic(),
                            batch.method(),
                            "completed",
                            batch.arrivalDate(),
                            batch.created(),
                            new Lines(batch.fetchId(), items, refundIdentities(items, batch.batchId()))));
        }
    }

    private Map<String, PayoutEvidence.RefundIdentity> refundIdentities(
            List<PayoutEvidence.Line> lines, @Nullable String archivedBatch) {
        var identities = new HashMap<String, PayoutEvidence.RefundIdentity>();
        for (var line : lines) {
            String ref = line.source();
            if (!line.type().equals("refund")
                    || ref == null
                    || !ref.matches("re_[A-Za-z0-9_]+")
                    || line.gross() >= 0
                    || identities.containsKey(ref)
                    || refunds.find(ref, null).isPresent()) continue;
            if (archivedBatch != null) {
                // Capture rechecks run inside the funds transaction: replay the original archived identity only.
                var body = jdbc.sql("""
                        SELECT response_body FROM io.processor_fetch
                        WHERE purpose='REFUND_QUERY' AND complete AND request->>'path'=:path
                            AND fetched_at <= (SELECT min(recorded_at) FROM core.batch_transition
                                WHERE subject_id=:batch AND to_status='HELD' AND hold_reason='UNKNOWN_PAYMENT')
                        ORDER BY id DESC LIMIT 1
                        """)
                        .param("path", "/v1/refunds/" + ref)
                        .param("batch", archivedBatch)
                        .query(String.class)
                        .optional();
                if (body.isPresent()) {
                    var node = mapper.readTree(body.orElseThrow());
                    if (ref.equals(node.path("id").asString()))
                        identities.put(
                                ref,
                                new PayoutEvidence.RefundIdentity(
                                        node.path("metadata")
                                                .path("ledgerlab_refund_id")
                                                .asString(null),
                                        StripeReadParser.integer(node, "amount"),
                                        StripeReadParser.text(node, "charge")));
                }
                continue;
            }
            var response = refundGateway.retrieve(ref);
            if (response.value() instanceof ProcessorRefundGateway.Incomplete incomplete)
                throw new IllegalStateException("Incomplete refund identity fetch: " + incomplete.reason());
            if (response.value() instanceof ProcessorRefundGateway.Found found && found.details() != null) {
                var details = found.details();
                identities.put(
                        ref,
                        new PayoutEvidence.RefundIdentity(details.refundId(), details.amount(), details.chargeRef()));
            }
        }
        return identities;
    }

    BatchSettlement.Result apply(PayoutEvidence evidence) {
        BatchSettlement.Result result = settlement.process(evidence);
        if (result != BatchSettlement.Result.PARKED) {
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            var awakened = jdbc.sql("""
                    UPDATE io.inbox_event SET status = 'RECEIVED', parked_on = NULL,
                        next_attempt_at = :now, lease_owner = NULL, lease_until = NULL
                    WHERE status = 'PARKED' AND parked_on = :dependency
                    RETURNING event_id
                    """)
                    .param("now", now)
                    .param("dependency", "batch:" + evidence.batchId())
                    .query(String.class)
                    .list();
            for (String eventId : awakened)
                InboxStatusHistory.append(jdbc, java.util.Objects.requireNonNull(eventId), "PARKED", now);
        }
        return result;
    }

    private static PayoutEvidence evidence(
            String sourceEvent,
            String eventType,
            String evidenceType,
            String evidenceId,
            Payout payout,
            @Nullable Lines lines) {
        return new PayoutEvidence(
                sourceEvent,
                evidenceType,
                evidenceId,
                eventType,
                payout.id(),
                payout.amount(),
                payout.currency(),
                payout.automatic(),
                payout.method(),
                payout.reconciliationStatus(),
                payout.arrivalDate(),
                payout.created(),
                lines);
    }
}
