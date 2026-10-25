package dev.ledgerlab.settlement;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.reconciliation.IssueDisposition;
import dev.ledgerlab.refunds.RefundSettlement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local archive replay only. All entry points join the caller's transaction when present. */
@Service
public final class BatchRechecks {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final BatchSettlement batches;
    private final RefundSettlement refunds;
    private final IssueDisposition issues;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public BatchRechecks(
            JdbcClient jdbc,
            Ledger ledger,
            BatchSettlement batches,
            RefundSettlement refunds,
            IssueDisposition issues,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.batches = batches;
        this.refunds = refunds;
        this.issues = issues;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    public void recheckUnknownPayment(String paymentRef, String captureEventId) {
        transactions.executeWithoutResult(
                tx -> recheckPayment(paymentRef, "UNKNOWN_PAYMENT", "inbox_event", captureEventId));
    }

    /** Returns only batches that actually advanced, for correction issue disposition. */
    public List<String> recheckAmount(String paymentRef, String correctionId) {
        return Objects.requireNonNull(
                transactions.execute(tx -> recheckPayment(paymentRef, "AMOUNT_MISMATCH", "correction", correctionId)));
    }

    private List<String> recheckPayment(String paymentRef, String reason, String evidenceType, String evidenceId) {
        ledger.lockFunds();
        var advanced = new ArrayList<String>();
        var candidates = jdbc.sql("""
                SELECT batch_id FROM core.settlement_batch
                WHERE status='HELD' AND hold_reason=? ORDER BY batch_id FOR UPDATE
                """).param(reason).query(String.class).list();
        for (String batchId : candidates) {
            var evidence = archived(Objects.requireNonNull(batchId), evidenceType, evidenceId);
            if (Objects.requireNonNull(evidence.lines()).items().stream()
                    .noneMatch(line -> line.type().equals("charge") && paymentRef.equals(line.source()))) continue;
            batches.recheckHeld(evidence);
            if (advanced(batchId)) advanced.add(batchId);
        }
        return List.copyOf(advanced);
    }

    public RecheckResult recheck(String batchId) {
        return Objects.requireNonNull(transactions.execute(tx -> {
            ledger.lockFunds();
            var evidence = archived(batchId, "processor_fetch", null);
            batches.recheckHeld(evidence);
            if (advanced(batchId)) {
                // Lock issues only after all business and account writes, including bank wake-up.
                for (String id :
                        jdbc.sql("""
                        SELECT id FROM recon.issue WHERE subject_type='batch' AND subject_id=?
                            AND status='OPEN' ORDER BY id FOR UPDATE
                        """).param(batchId).query(String.class).list()) {
                    issues.resolve(Objects.requireNonNull(id), "运营重核通过");
                }
            }
            return jdbc.sql("SELECT status, hold_reason FROM core.settlement_batch WHERE batch_id=?")
                    .param(batchId)
                    .query((rs, n) -> new RecheckResult(batchId, rs.getString(1), rs.getString(2)))
                    .single();
        }));
    }

    private boolean advanced(String batchId) {
        return jdbc.sql("SELECT status IN ('CONFIRMED','BANKED') FROM core.settlement_batch WHERE batch_id=?")
                .param(batchId)
                .query(Boolean.class)
                .single();
    }

    private PayoutEvidence archived(String batchId, String evidenceType, @Nullable String evidenceId) {
        var row = jdbc.sql("SELECT * FROM core.settlement_batch WHERE batch_id=? FOR UPDATE")
                .param(batchId)
                .query((rs, n) -> {
                    if (!rs.getString("status").equals("HELD")) throw new BatchRecheckException(409, "BATCH_NOT_HELD");
                    if (rs.getString("hold_reason").equals("UNSUPPORTED_PAYOUT"))
                        throw new BatchRecheckException(409, "BATCH_NOT_RECHECKABLE");
                    return new BatchSettlement.HeldBatch(
                            batchId,
                            rs.getLong("payout_amount"),
                            rs.getString("currency"),
                            rs.getBoolean("automatic"),
                            rs.getString("method"),
                            rs.getDate("arrival_date")
                                    .toLocalDate()
                                    .atStartOfDay(ZoneOffset.UTC)
                                    .toInstant(),
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getLong("lines_fetch_id"),
                            rs.getString("source_event_id"));
                })
                .optional()
                .orElseThrow(() -> new BatchRecheckException(404, "BATCH_NOT_FOUND"));
        String raw =
                jdbc.sql("""
                SELECT response_body FROM io.processor_fetch WHERE id=? AND complete AND purpose='BATCH_LINES'
                    AND request->'query'->>'payout'=?
                """).params(row.fetchId(), batchId).query(String.class).single();
        var lines = new ArrayList<PayoutEvidence.Line>();
        for (var page : mapper.readTree(raw)) {
            for (var node : mapper.readTree(page.asString()).path("data")) {
                lines.add(new PayoutEvidence.Line(
                        text(node, "id"),
                        text(node, "type"),
                        node.path("source").isNull() ? null : text(node, "source"),
                        integer(node, "amount"),
                        integer(node, "fee"),
                        integer(node, "net"),
                        text(node, "currency"),
                        Instant.ofEpochSecond(integer(node, "created"))));
            }
        }
        var identities = new HashMap<String, PayoutEvidence.RefundIdentity>();
        for (var line : lines) {
            String ref = line.source();
            if (!line.type().equals("refund")
                    || ref == null
                    || !ref.matches("re_[A-Za-z0-9_]+")
                    || line.gross() >= 0
                    || identities.containsKey(ref)
                    || refunds.find(ref, null).isPresent()) continue;
            var body = jdbc.sql("""
                    SELECT response_body FROM io.processor_fetch
                    WHERE purpose='REFUND_QUERY' AND complete AND request->>'path'=:path
                        AND fetched_at <= (SELECT min(recorded_at) FROM core.batch_transition
                            WHERE subject_id=:batch AND to_status='HELD'
                                AND ((:capture AND hold_reason='UNKNOWN_PAYMENT')
                                    OR (NOT :capture AND lines_fetch_id=:fetch)))
                    ORDER BY id DESC LIMIT 1
                    """)
                    .param("path", "/v1/refunds/" + ref)
                    .param("batch", batchId)
                    .param("capture", evidenceType.equals("inbox_event"))
                    .param("fetch", row.fetchId())
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
                                    integer(node, "amount"),
                                    text(node, "charge")));
            }
        }
        return new PayoutEvidence(
                row.sourceEventId(),
                evidenceType,
                evidenceId == null ? Long.toString(row.fetchId()) : evidenceId,
                "payout.reconciliation_completed",
                batchId,
                row.amount(),
                row.currency(),
                row.automatic(),
                row.method(),
                "completed",
                row.arrivalDate(),
                row.created(),
                new PayoutEvidence.Lines(row.fetchId(), lines, identities));
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isString() || node.path(field).asString().isBlank())
            throw new IllegalArgumentException("INVALID_RESPONSE");
        return node.path(field).asString();
    }

    private static long integer(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToLong())
            throw new IllegalArgumentException("INVALID_RESPONSE");
        return node.path(field).longValue();
    }

    public record RecheckResult(
            String batchId, String status, @Nullable String holdReason) {}
}
