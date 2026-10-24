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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
    private final BankStatements bankStatements;

    public BatchSettlement(
            JdbcClient jdbc,
            Ledger ledger,
            PaymentSettlement payments,
            IngestionIssues issues,
            Clock clock,
            ObjectMapper mapper,
            BankStatements bankStatements) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.payments = payments;
        this.issues = issues;
        this.clock = clock;
        this.mapper = mapper;
        this.bankStatements = bankStatements;
    }

    /** Optimization only: process rechecks under the funds and batch locks. */
    public boolean needsLines(PayoutEvidence evidence) {
        return status(evidence.batchId(), false)
                .map(state -> !state.equals("HELD") && !evidence.eventType().equals("payout.created"))
                .orElse(true);
    }

    /** Called after capture, under the same funds lock and transaction. No external reads. */
    public List<HeldBatch> unknownPaymentBatches() {
        ledger.lockFunds();
        return jdbc.sql("""
                SELECT batch_id, payout_amount, currency, automatic, method, arrival_date,
                    occurred_at, lines_fetch_id, source_event_id FROM core.settlement_batch
                WHERE status = 'HELD' AND hold_reason = 'UNKNOWN_PAYMENT'
                ORDER BY batch_id FOR UPDATE
                """)
                .query((rs, row) -> new HeldBatch(
                        rs.getString("batch_id"),
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
                        rs.getString("source_event_id")))
                .list();
    }

    public Result recheckByPayment(String paymentRef, PayoutEvidence evidence) {
        ledger.lockFunds();
        boolean eligible = jdbc.sql("""
                SELECT count(*) FROM core.settlement_batch WHERE batch_id = :batch
                  AND status = 'HELD' AND hold_reason = 'UNKNOWN_PAYMENT' AND lines_fetch_id = :fetch
                """)
                        .param("batch", evidence.batchId())
                        .param("fetch", Objects.requireNonNull(evidence.lines()).fetchId())
                        .query(Long.class)
                        .single()
                == 1;
        if (!eligible
                || evidence.lines().items().stream()
                        .noneMatch(line -> line.type().equals("charge") && paymentRef.equals(line.source())))
            return Result.DUPLICATE_FACT;
        return process(evidence, true);
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
        return process(evidence, false);
    }

    private Result process(PayoutEvidence evidence, boolean recheck) {
        ledger.lockFunds();
        Instant now = clock.instant();
        Optional<String> existing = status(evidence.batchId(), true);
        if (evidence.eventType().equals("payout.paid")) {
            if (existing.isEmpty()) return Result.PARKED;
            history(evidence, existing.get(), existing.get(), now);
            return Result.APPLIED;
        }
        if (evidence.eventType().equals("payout.failed") || evidence.eventType().equals("payout.canceled")) {
            return failPayout(evidence, existing, now);
        }
        if (existing.filter(state ->
                                List.of("CONFIRMED", "BANKED", "PAYOUT_FAILED").contains(state))
                        .isPresent()
                && evidence.lines() != null) {
            return compareVersion(evidence, existing.orElseThrow(), now);
        }
        if (existing.isPresent()
                && !recheck
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
        jdbc.sql("UPDATE core.settlement_batch SET lines_fetch_id = :fetch WHERE batch_id = :batch")
                .param("fetch", fetched.fetchId())
                .param("batch", evidence.batchId())
                .update();
        String previous = recheck ? "HELD" : "ANNOUNCED";
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
            if (!supportedLineType(line)
                    || line.source() == null
                    || !line.currency().equalsIgnoreCase("cad")
                    || line.gross() < 1
                    || line.gross() > 99999999
                    || line.fee() < 0
                    || !identities.add(line.id())
                    || !subjects.add(line.type() + ":" + line.source())) {
                hold(
                        evidence,
                        previous,
                        "UNSUPPORTED_LINE",
                        now,
                        Map.of("line_types", List.of("charge", "payout_failure")),
                        line);
                return Result.APPLIED;
            }
            if (line.fee() > line.gross()) {
                hold(
                        evidence,
                        previous,
                        "FEE_EXCEEDS_GROSS",
                        now,
                        Map.of("max_fee", line.gross(), "processor_ref", Objects.requireNonNull(line.source())),
                        line);
                return Result.APPLIED;
            }
            if (line.net() != Math.subtractExact(line.gross(), line.fee())) {
                hold(
                        evidence,
                        previous,
                        "TOTAL_MISMATCH",
                        now,
                        Map.of("net", Math.subtractExact(line.gross(), line.fee())),
                        line);
                return Result.APPLIED;
            }
            net = Math.addExact(net, line.net());
            if (line.type().equals("charge")) {
                gross = Math.addExact(gross, line.gross());
                fees = Math.addExact(fees, line.fee());
            } else {
                if (line.fee() != 0) {
                    hold(evidence, previous, "UNSUPPORTED_LINE", now, Map.of("fee", 0), line);
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
            hold(
                    evidence,
                    previous,
                    "TOTAL_MISMATCH",
                    now,
                    Map.of("payout_amount", announcedAmount),
                    Map.of("payout_amount", evidence.amount(), "line_net", net));
            return Result.APPLIED;
        }
        transition(evidence, previous, "RECEIVED", now);
        Map<String, Payment> matched = new HashMap<>();
        for (Line line : lines) {
            String source = Objects.requireNonNull(line.source());
            if (jdbc.sql("SELECT count(*) FROM core.settlement_batch_line WHERE balance_txn_id = :id")
                            .param("id", line.id())
                            .query(Long.class)
                            .single()
                    != 0) {
                hold(
                        evidence,
                        "RECEIVED",
                        "LINE_IN_OTHER_BATCH",
                        now,
                        Map.of(
                                "batch_id",
                                jdbc.sql("SELECT batch_id FROM core.settlement_batch_line WHERE balance_txn_id = :id")
                                        .param("id", line.id())
                                        .query(String.class)
                                        .single()),
                        line);
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
                    hold(
                            evidence,
                            "RECEIVED",
                            mismatch,
                            now,
                            Map.of(
                                    "gross",
                                    fact.amount(),
                                    "processor_ref",
                                    source,
                                    "status",
                                    fact.status(),
                                    "batch_id",
                                    Objects.toString(fact.batchId(), "")),
                            line);
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
                    hold(
                            evidence,
                            "RECEIVED",
                            used ? "LINE_IN_OTHER_BATCH" : "UNSUPPORTED_LINE",
                            now,
                            Map.of("status", "PAYOUT_FAILED", "unused", true, "net", line.gross()),
                            line);
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
        bankStatements.wakeWaiting(evidence.batchId());
        return Result.APPLIED;
    }

    private Optional<String> status(String batch, boolean lock) {
        return jdbc.sql("SELECT status FROM core.settlement_batch WHERE batch_id = :batch"
                        + (lock ? " FOR UPDATE" : ""))
                .param("batch", batch)
                .query(String.class)
                .optional();
    }

    private static boolean supportedLineType(Line line) {
        return line.type().equals("charge") || line.type().equals("payout_failure");
    }

    private void hold(PayoutEvidence evidence, String previous, String reason, Instant now) {
        hold(
                evidence,
                previous,
                reason,
                now,
                Map.of("automatic", true, "method", "standard", "currency", "cad", "positive_amount", true),
                Map.of(
                        "amount",
                        evidence.amount(),
                        "automatic",
                        evidence.automatic(),
                        "method",
                        evidence.method(),
                        "currency",
                        evidence.currency(),
                        "reconciliation_status",
                        evidence.reconciliationStatus()));
    }

    private void hold(
            PayoutEvidence evidence, String previous, String reason, Instant now, Object expected, Object actual) {
        jdbc.sql(
                        "UPDATE core.settlement_batch SET status = 'HELD', hold_reason = :reason, updated_at = :now WHERE batch_id = :batch")
                .param("reason", reason)
                .param("now", utc(now))
                .param("batch", evidence.batchId())
                .update();
        history(evidence, previous, "HELD", now);
        if (reason.equals("UNKNOWN_PAYMENT")) return;
        String category = switch (reason) {
            case "FEE_EXCEEDS_GROSS", "AMOUNT_MISMATCH" -> "AMOUNT_MISMATCH";
            case "UNSUPPORTED_LINE" -> "UNSUPPORTED_BATCH_LINE";
            case "LINE_IN_OTHER_BATCH" -> "DUPLICATE_STATEMENT_LINE";
            case "TOTAL_MISMATCH" -> "BATCH_TOTAL_MISMATCH";
            default -> reason;
        };
        Map<String, Object> details = new HashMap<>();
        details.put("reason", reason);
        details.put("line", actual);
        if (reason.equals("UNSUPPORTED_LINE")) {
            details.put(
                    "lines",
                    Objects.requireNonNull(evidence.lines()).items().stream()
                            .filter(line -> line.equals(actual)
                                    || (!supportedLineType(line) && !line.type().equals("payout")))
                            .toList());
        }
        openIssue(evidence, category, expected, details);
    }

    private void openIssue(PayoutEvidence evidence, String category, Object expected, Object actual) {
        var refs = new ArrayList<Map<String, Object>>();
        refs.add(Map.of(
                "table",
                evidence.evidenceType().equals("processor_fetch") ? "io.processor_fetch" : "io.inbox_event",
                "id",
                evidence.evidenceId()));
        if (evidence.lines() != null)
            refs.add(
                    Map.of("table", "io.processor_fetch", "id", evidence.lines().fetchId()));
        issues.open(
                category,
                "batch",
                evidence.batchId(),
                mapper.writeValueAsString(expected),
                mapper.writeValueAsString(actual),
                mapper.writeValueAsString(refs));
    }

    private Result failPayout(PayoutEvidence evidence, Optional<String> existing, Instant now) {
        if (existing.isEmpty()) return Result.PARKED;
        String state = existing.orElseThrow();
        if (state.equals("CONFIRMED")) {
            long net = jdbc.sql("SELECT net FROM core.settlement_batch WHERE batch_id = :batch")
                    .param("batch", evidence.batchId())
                    .query(Long.class)
                    .single();
            transition(evidence, state, "PAYOUT_FAILED", now);
            ledger.post(new EntryRequest(
                    EntryType.BATCH_PAYOUT_FAILED,
                    "batch:" + evidence.batchId() + ":payout_failed",
                    evidence.created(),
                    SubjectType.BATCH,
                    evidence.batchId(),
                    List.of(
                            PostingLine.debit(Account.PROCESSOR_RECEIVABLE, net),
                            PostingLine.credit(Account.SETTLEMENT_IN_TRANSIT, net))));
            return Result.APPLIED;
        }
        history(evidence, state, state, now);
        if (state.equals("BANKED")) {
            openIssue(
                    evidence,
                    "BATCH_STATE_CONFLICT",
                    Map.of("status", state),
                    Map.of("event_type", evidence.eventType(), "amount", evidence.amount()));
            return Result.BUSINESS_CONFLICT;
        }
        return state.equals("PAYOUT_FAILED") ? Result.DUPLICATE_FACT : Result.APPLIED;
    }

    private Result compareVersion(PayoutEvidence evidence, String state, Instant now) {
        List<Line> original = jdbc.sql("""
                SELECT l.*, b.currency FROM core.settlement_batch_line l
                JOIN core.settlement_batch b USING (batch_id) WHERE batch_id = :batch
                """)
                .param("batch", evidence.batchId())
                .query((rs, row) -> new Line(
                        rs.getString("balance_txn_id"),
                        rs.getString("line_type").equals("PAYMENT") ? "charge" : "payout_failure",
                        rs.getString("processor_ref"),
                        rs.getLong("gross"),
                        rs.getLong("fee"),
                        rs.getLong("net"),
                        rs.getString("currency"),
                        rs.getTimestamp("occurred_at").toInstant()))
                .list();
        String expectedHash = contentHash(original);
        String actualHash = contentHash(Objects.requireNonNull(evidence.lines()).items());
        var batch = jdbc.sql("SELECT payout_amount, lines_fetch_id FROM core.settlement_batch WHERE batch_id = :batch")
                .param("batch", evidence.batchId())
                .query()
                .singleRow();
        history(evidence, state, state, now);
        if (expectedHash.equals(actualHash) && Objects.equals(batch.get("payout_amount"), evidence.amount()))
            return Result.DUPLICATE_FACT;
        openIssue(
                evidence,
                "BATCH_VERSION_CONFLICT",
                Map.of(
                        "hash",
                        expectedHash,
                        "fetch_id",
                        Objects.requireNonNull(batch.get("lines_fetch_id")),
                        "amount",
                        Objects.requireNonNull(batch.get("payout_amount"))),
                Map.of("hash", actualHash, "fetch_id", evidence.lines().fetchId(), "amount", evidence.amount()));
        return Result.BUSINESS_CONFLICT;
    }

    private String contentHash(List<Line> lines) {
        var canonical = lines.stream()
                .filter(line -> !line.type().equals("payout"))
                .sorted(Comparator.comparing(Line::id))
                .map(line -> Arrays.asList(
                        line.id(),
                        line.type(),
                        line.source(),
                        line.gross(),
                        line.fee(),
                        line.net(),
                        line.currency().toLowerCase(Locale.ROOT),
                        line.occurredAt().getEpochSecond()))
                .toList();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void transition(PayoutEvidence evidence, String previous, String next, Instant now) {
        jdbc.sql(
                        "UPDATE core.settlement_batch SET status = :status, hold_reason = NULL, updated_at = :now WHERE batch_id = :batch")
                .param("status", next)
                .param("now", utc(now))
                .param("batch", evidence.batchId())
                .update();
        history(evidence, previous, next, now);
    }

    private void history(PayoutEvidence evidence, @Nullable String previous, String next, Instant now) {
        jdbc.sql("""
                INSERT INTO core.batch_transition
                    (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at,
                     hold_reason, lines_fetch_id)
                SELECT :batch, :previous, :next, :type, :id, :occurred, :now, hold_reason, lines_fetch_id
                FROM core.settlement_batch WHERE batch_id = :batch
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
        BUSINESS_CONFLICT,
        PARKED
    }

    public record AnnouncedBatch(String batchId, String sourceEventId) {}

    public record HeldBatch(
            String batchId,
            long amount,
            String currency,
            boolean automatic,
            String method,
            Instant arrivalDate,
            Instant created,
            long fetchId,
            String sourceEventId) {}
}
