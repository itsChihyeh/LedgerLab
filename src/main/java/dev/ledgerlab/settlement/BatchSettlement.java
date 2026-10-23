package dev.ledgerlab.settlement;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentSettlement;
import dev.ledgerlab.payments.PaymentSettlement.Payment;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.settlement.PayoutEvidence.Line;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** All business writes join the inbox or polling transaction; funds lock serializes new batches too. */
@Service
public final class BatchSettlement {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Toronto");
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PaymentSettlement payments;
    private final IngestionIssues issues;
    private final Clock clock;
    private final ObjectMapper mapper;

    public BatchSettlement(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentSettlement payments,
            IngestionIssues issues,
            Clock clock,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.issues = issues;
        this.clock = clock;
        this.mapper = mapper;
    }

    /** Optimization only: process rechecks under the funds and batch locks. */
    public boolean needsLines(PayoutEvidence evidence) {
        return status(evidence.batchId(), false)
                .map(state -> state.equals("ANNOUNCED") && !evidence.eventType().equals("payout.created"))
                .orElse(true);
    }

    public List<AnnouncedBatch> announcedBefore(Instant cutoff) {
        return jdbc.sql("""
                SELECT batch_id, source_event_id FROM core.settlement_batch
                WHERE status = 'ANNOUNCED' AND recorded_at <= :cutoff ORDER BY recorded_at, batch_id
                """)
                .param("cutoff", utc(cutoff))
                .query((rs, row) -> new AnnouncedBatch(rs.getString(1), rs.getString(2)))
                .list();
    }

    public Result process(PayoutEvidence evidence) {
        ledger.lockFunds();
        Instant now = clock.instant();
        Optional<String> existing = status(evidence.batchId(), true);
        if (evidence.eventType().equals("payout.paid")) {
            if (existing.isEmpty()) return Result.PARKED;
            history(evidence, existing.get(), existing.get(), now);
            return Result.APPLIED;
        }
        if (existing.isPresent()
                && (evidence.eventType().equals("payout.created")
                        || !existing.get().equals("ANNOUNCED"))) {
            history(evidence, existing.get(), existing.get(), now);
            return Result.DUPLICATE_FACT;
        }
        if (existing.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO core.settlement_batch
                        (batch_id, payout_amount, currency, automatic, method, arrival_date, status,
                         source_event_id, occurred_at, recorded_at, updated_at)
                    VALUES (:batch, :amount, :currency, :automatic, :method, :arrival, 'ANNOUNCED',
                            :event, :occurred, :now, :now)
                    """)
                    .param("batch", evidence.batchId())
                    .param("amount", evidence.amount())
                    .param("currency", evidence.currency())
                    .param("automatic", evidence.automatic())
                    .param("method", evidence.method())
                    .param(
                            "arrival",
                            evidence.arrivalDate().atZone(ZoneOffset.UTC).toLocalDate())
                    .param("event", evidence.eventId())
                    .param("occurred", utc(evidence.created()))
                    .param("now", utc(now))
                    .update();
            history(evidence, null, "ANNOUNCED", now);
        }
        if (!evidence.supported()) {
            hold(evidence, "ANNOUNCED", "UNSUPPORTED_PAYOUT", now);
            return Result.APPLIED;
        }
        if (!evidence.ready()) return Result.APPLIED;
        var fetched = Objects.requireNonNull(evidence.lines(), "Ready payout requires complete lines");
        List<Line> lines = fetched.items().stream()
                .filter(line -> !line.type().equals("payout"))
                .toList();
        long gross = 0;
        long fees = 0;
        long returned = 0;
        long net = 0;
        var identities = new HashSet<String>();
        var subjects = new HashSet<String>();
        for (Line line : lines) {
            if ((!line.type().equals("charge") && !line.type().equals("payout_failure"))
                    || line.source() == null
                    || !line.currency().equalsIgnoreCase("cad")
                    || line.gross() < 1
                    || line.gross() > 99999999
                    || line.fee() < 0
                    || !identities.add(line.id())
                    || !subjects.add(line.type() + ":" + line.source())) {
                hold(evidence, "ANNOUNCED", "UNSUPPORTED_LINE", now);
                return Result.APPLIED;
            }
            if (line.fee() > line.gross()) {
                hold(evidence, "ANNOUNCED", "FEE_EXCEEDS_GROSS", now);
                return Result.APPLIED;
            }
            if (line.net() != Math.subtractExact(line.gross(), line.fee())) {
                hold(evidence, "ANNOUNCED", "TOTAL_MISMATCH", now);
                return Result.APPLIED;
            }
            net = Math.addExact(net, line.net());
            if (line.type().equals("charge")) {
                gross = Math.addExact(gross, line.gross());
                fees = Math.addExact(fees, line.fee());
            } else {
                if (line.fee() != 0) {
                    hold(evidence, "ANNOUNCED", "UNSUPPORTED_LINE", now);
                    return Result.APPLIED;
                }
                returned = Math.addExact(returned, line.gross());
            }
        }
        long announcedAmount = jdbc.sql("SELECT payout_amount FROM core.settlement_batch WHERE batch_id = :batch")
                .param("batch", evidence.batchId())
                .query(Long.class)
                .single();
        if (net != evidence.amount() || net != announcedAmount) {
            hold(evidence, "ANNOUNCED", "TOTAL_MISMATCH", now);
            return Result.APPLIED;
        }
        transition(evidence, "ANNOUNCED", "RECEIVED", now);
        Map<String, Payment> matched = new HashMap<>();
        for (Line line : lines) {
            String source = Objects.requireNonNull(line.source());
            if (jdbc.sql("SELECT count(*) FROM core.settlement_batch_line WHERE balance_txn_id = :id")
                            .param("id", line.id())
                            .query(Long.class)
                            .single()
                    != 0) {
                hold(evidence, "RECEIVED", "LINE_IN_OTHER_BATCH", now);
                return Result.APPLIED;
            }
            if (line.type().equals("charge")) {
                Optional<Payment> payment = payments.lock(source);
                if (payment.isEmpty()) {
                    hold(evidence, "RECEIVED", "UNKNOWN_PAYMENT", now);
                    return Result.APPLIED;
                }
                Payment fact = payment.get();
                String mismatch = fact.batchId() != null
                        ? "LINE_IN_OTHER_BATCH"
                        : !fact.status().equals("CAPTURED")
                                ? "UNSUPPORTED_LINE"
                                : fact.amount() != line.gross() ? "AMOUNT_MISMATCH" : null;
                if (mismatch != null) {
                    hold(evidence, "RECEIVED", mismatch, now);
                    return Result.APPLIED;
                }
                matched.put(source, fact);
            } else {
                boolean matches = jdbc.sql("""
                        SELECT count(*) FROM core.settlement_batch
                        WHERE batch_id = :source AND status = 'PAYOUT_FAILED' AND net = :amount
                        """)
                                .param("source", source)
                                .param("amount", line.gross())
                                .query(Long.class)
                                .single()
                        == 1;
                boolean used =
                        jdbc.sql("""
                        SELECT count(*) FROM core.settlement_batch_line
                        WHERE line_type = 'PAYOUT_FAILURE' AND processor_ref = :source
                        """).param("source", source).query(Long.class).single() != 0;
                if (!matches || used) {
                    hold(evidence, "RECEIVED", "UNSUPPORTED_LINE", now);
                    return Result.APPLIED;
                }
            }
        }
        jdbc.sql("""
                UPDATE core.settlement_batch SET gross = :gross, fees = :fees, refunds = 0,
                    payout_failures_returned = :returned, net = :net, lines_fetch_id = :fetch,
                    settlement_date = :date WHERE batch_id = :batch
                """)
                .param("gross", gross)
                .param("fees", fees)
                .param("returned", returned)
                .param("net", net)
                .param("fetch", fetched.fetchId())
                .param("date", evidence.created().atZone(BUSINESS_ZONE).toLocalDate())
                .param("batch", evidence.batchId())
                .update();
        transition(evidence, "RECEIVED", "CONFIRMED", now);
        int lineNumber = 0;
        for (Line line : lines) {
            jdbc.sql("""
                    INSERT INTO core.settlement_batch_line
                        (batch_id, line_no, line_type, processor_ref, balance_txn_id, gross, fee, net, occurred_at, recorded_at)
                    VALUES (:batch, :number, :type, :source, :id, :gross, :fee, :net, :occurred, :now)
                    """)
                    .param("batch", evidence.batchId())
                    .param("number", ++lineNumber)
                    .param("type", line.type().equals("charge") ? "PAYMENT" : "PAYOUT_FAILURE")
                    .param("source", line.source())
                    .param("id", line.id())
                    .param("gross", line.gross())
                    .param("fee", line.fee())
                    .param("net", line.net())
                    .param("occurred", utc(line.occurredAt()))
                    .param("now", utc(now))
                    .update();
            if (line.type().equals("charge")) {
                payments.settle(
                        Objects.requireNonNull(matched.get(line.source())),
                        line.fee(),
                        evidence.batchId(),
                        evidence.evidenceType(),
                        evidence.evidenceId(),
                        evidence.created(),
                        now);
            }
        }
        var postings = new ArrayList<PostingLine>();
        postings.add(PostingLine.debit(Account.SETTLEMENT_IN_TRANSIT, net));
        if (fees > 0) postings.add(PostingLine.debit(Account.MERCHANT_PENDING, fees));
        postings.add(PostingLine.credit(Account.PROCESSOR_RECEIVABLE, Math.addExact(net, fees)));
        long available = Math.subtractExact(gross, fees);
        if (available > 0) {
            postings.add(PostingLine.debit(Account.MERCHANT_PENDING, available));
            postings.add(PostingLine.credit(Account.MERCHANT_AVAILABLE, available));
        }
        ledger.post(new EntryRequest(
                EntryType.BATCH_SETTLED,
                "batch:" + evidence.batchId() + ":settled",
                evidence.created(),
                SubjectType.BATCH,
                evidence.batchId(),
                postings));
        return Result.APPLIED;
    }

    private Optional<String> status(String batch, boolean lock) {
        return jdbc.sql("SELECT status FROM core.settlement_batch WHERE batch_id = :batch"
                        + (lock ? " FOR UPDATE" : ""))
                .param("batch", batch)
                .query(String.class)
                .optional();
    }

    private void hold(PayoutEvidence evidence, String previous, String reason, Instant now) {
        jdbc.sql(
                        "UPDATE core.settlement_batch SET status = 'HELD', hold_reason = :reason, updated_at = :now WHERE batch_id = :batch")
                .param("reason", reason)
                .param("now", utc(now))
                .param("batch", evidence.batchId())
                .update();
        history(evidence, previous, "HELD", now);
        // T05 only opens the explicitly specified payout admission issue. Matching issue
        // classification and recovery belong to T06; UNKNOWN_PAYMENT waits for aging.
        if (!reason.equals("UNSUPPORTED_PAYOUT")) return;
        issues.open(
                reason,
                "batch",
                evidence.batchId(),
                "{}",
                mapper.writeValueAsString(Map.of("reason", reason)),
                mapper.writeValueAsString(List.of(Map.of(
                        "table",
                        evidence.evidenceType().equals("processor_fetch") ? "io.processor_fetch" : "io.inbox_event",
                        "id",
                        evidence.evidenceId()))));
    }

    private void transition(PayoutEvidence evidence, String previous, String next, Instant now) {
        jdbc.sql("UPDATE core.settlement_batch SET status = :status, updated_at = :now WHERE batch_id = :batch")
                .param("status", next)
                .param("now", utc(now))
                .param("batch", evidence.batchId())
                .update();
        history(evidence, previous, next, now);
    }

    private void history(PayoutEvidence evidence, @Nullable String previous, String next, Instant now) {
        jdbc.sql("""
                INSERT INTO core.batch_transition
                    (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at)
                VALUES (:batch, :previous, :next, :type, :id, :occurred, :now)
                """)
                .param("batch", evidence.batchId())
                .param("previous", previous)
                .param("next", next)
                .param("type", evidence.evidenceType())
                .param("id", evidence.evidenceId())
                .param("occurred", utc(evidence.created()))
                .param("now", utc(now))
                .update();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    public enum Result {
        APPLIED,
        DUPLICATE_FACT,
        PARKED
    }

    public record AnnouncedBatch(String batchId, String sourceEventId) {}
}
