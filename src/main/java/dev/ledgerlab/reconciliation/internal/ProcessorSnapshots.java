package dev.ledgerlab.reconciliation.internal;

import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.reconciliation.ProcessorSnapshotReader;
import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public final class ProcessorSnapshots {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final IdGenerator ids;
    private final BusinessCalendar calendar;
    private final ObjectMapper mapper;
    private final ReconJson json;
    private final IngestionIssues issues;

    public ProcessorSnapshots(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            Clock clock,
            IdGenerator ids,
            BusinessCalendar calendar,
            ObjectMapper mapper,
            ReconJson json,
            IngestionIssues issues) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.ids = ids;
        this.calendar = calendar;
        this.mapper = mapper;
        this.json = json;
        this.issues = issues;
    }

    public record Result(
            String fileId, String status, @Nullable String rejectReason) {}

    record Line(
            String statementLineId,
            String lineType,
            String processorRef,
            String batchId,
            long gross,
            long fee,
            long net,
            String occurredAt,
            String settlementDate,
            String memo) {}

    public Result fetch(LocalDate date, ProcessorSnapshotReader reader) {
        var payouts = reader.listPayoutsCreated(calendar.startOfDay(date), calendar.startOfDay(date.plusDays(1)));
        List<Long> fetchIds = new ArrayList<>();
        fetchIds.add(payouts.fetchId());
        Map<String, String> lines = new TreeMap<>();
        for (String value : payouts.items()) {
            String id = text(mapper.readTree(value), "id");
            var result = reader.listBalanceTransactionsForPayout(id);
            fetchIds.add(result.fetchId());
            lines.put(id, page(result));
        }
        return importSnapshot("processor:" + date, date, page(payouts), lines, clock.instant(), fetchIds);
    }

    private String page(ProcessorSnapshotReader.FetchResult result) {
        return "{\"data\":[" + String.join(",", result.items()) + "],\"has_more\":" + !result.complete() + "}";
    }

    /** Complete Stripe list envelopes (all pages concatenated into data); has_more must be false. */
    public Result importSnapshot(
            String sourceName,
            LocalDate date,
            String payoutsJson,
            Map<String, String> linesJsonByPayout,
            Instant fetchedAt) {
        return importSnapshot(sourceName, date, payoutsJson, linesJsonByPayout, fetchedAt, List.of());
    }

    private Result importSnapshot(
            String sourceName,
            LocalDate date,
            String payoutsJson,
            Map<String, String> linesJsonByPayout,
            Instant fetchedAt,
            List<Long> fetchIds) {
        if (sourceName.isBlank() || fetchedAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Source name and a completed fetch time are required");
        }
        Instant from = calendar.startOfDay(date);
        Instant to = calendar.startOfDay(date.plusDays(1));
        List<Line> rows = new ArrayList<>();
        List<JsonNode> payouts = new ArrayList<>();
        String content;
        boolean complete;
        try {
            var payoutIds = new HashSet<String>();
            for (JsonNode payout : completePage(payoutsJson)) {
                String batch = text(payout, "id");
                Instant created = Instant.ofEpochSecond(integer(payout, "created"));
                if (!batch.startsWith("po_")
                        || !payoutIds.add(batch)
                        || created.isBefore(from)
                        || !created.isBefore(to)) {
                    throw new IllegalArgumentException("Invalid payout identity or interval");
                }
                payouts.add(payout);
                String linePage = Objects.requireNonNull(linesJsonByPayout.get(batch), "Missing payout lines");
                for (JsonNode line : completePage(linePage)) {
                    String type = text(line, "type");
                    if (type.equals("payout")) continue;
                    String kind = switch (type) {
                        case "charge" -> "PAYMENT";
                        case "refund" -> "REFUND";
                        case "payout_failure" -> "PAYOUT_FAILURE";
                        default -> throw new IllegalArgumentException("Unsupported statement line");
                    };
                    long gross = integer(line, "amount");
                    long fee = integer(line, "fee");
                    long net = integer(line, "net");
                    String identity = text(line, "id");
                    if (!identity.startsWith("txn_")
                            || !text(line, "currency").equalsIgnoreCase("cad")
                            || fee < 0
                            || fee > 99999999
                            || net < -99999999
                            || net > 99999999
                            || (kind.equals("REFUND") ? gross < -99999999 || gross >= 0 : gross < 1 || gross > 99999999)
                            || Math.subtractExact(gross, fee) != net) {
                        throw new IllegalArgumentException("Invalid statement amount or currency");
                    }
                    String memo = line.has("memo") ? textAllowEmpty(line, "memo") : "";
                    rows.add(new Line(
                            identity,
                            kind,
                            text(line, "source"),
                            batch,
                            gross,
                            fee,
                            net,
                            Instant.ofEpochSecond(integer(line, "created")).toString(),
                            calendar.businessDateOf(created).toString(),
                            memo));
                }
            }
            if (!payoutIds.equals(linesJsonByPayout.keySet())) throw new IllegalArgumentException("Extra payout lines");
            payouts.sort(Comparator.comparing(p -> text(p, "id")));
            rows.sort(Comparator.comparing(l -> json.canonical(json.write(l))));
            content = json.canonical(
                    json.write(Map.of("businessDate", date.toString(), "payouts", payouts, "lines", rows)));
            complete = true;
        } catch (RuntimeException invalid) {
            // Preserve even malformed JSON verbatim, without admitting any partial rows.
            content = json.canonical(json.write(
                    Map.of("businessDate", date.toString(), "payoutsRaw", payoutsJson, "linesRaw", linesJsonByPayout)));
            complete = false;
            rows.clear();
        }
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        String sha = hash(raw);
        boolean accepted = complete;
        return Objects.requireNonNull(tx.execute(ignored -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(71010)").query(Object.class).single();
            var existing = jdbc.sql("SELECT id, status, reject_reason FROM io.imported_file WHERE sha256=?")
                    .param(sha)
                    .query((rs, n) -> new Result(rs.getString(1), rs.getString(2), rs.getString(3)))
                    .optional();
            if (existing.isPresent()) return existing.get();
            String id = ids.next("file");
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            int version = jdbc.sql(
                            "SELECT COALESCE(MAX(version), 0) + 1 FROM io.imported_file WHERE kind='PROCESSOR_STATEMENT' AND statement_date=?")
                    .param(date)
                    .query(Integer.class)
                    .single();
            jdbc.sql("""
                    INSERT INTO io.imported_file (id, kind, source_name, sha256, statement_date, version,
                        status, reject_reason, raw, received_at, recorded_at, processor_fetch_ids, processor_source)
                    VALUES (:id, 'PROCESSOR_STATEMENT', :source, :sha, :date, :version,
                        :status, :reason, :raw, :received, :now, CAST(:fetches AS jsonb), CAST(:original AS jsonb))
                    """)
                    .param("id", id)
                    .param("source", sourceName)
                    .param("sha", sha)
                    .param("date", date)
                    .param("version", version)
                    .param("status", accepted ? "ACCEPTED" : "REJECTED")
                    .param("reason", accepted ? null : "FILE_INCOMPLETE")
                    .param("raw", raw)
                    .param("received", fetchedAt.atOffset(ZoneOffset.UTC))
                    .param("now", now)
                    .param("fetches", json.write(fetchIds))
                    .param("original", json.write(Map.of("payouts", payoutsJson, "lines", linesJsonByPayout)))
                    .update();
            int seq = 0;
            for (Line row : rows) {
                var previous = jdbc.sql("""
                        SELECT file_id, to_jsonb(l) - 'file_id' - 'line_seq' - 'recorded_at' AS content
                        FROM recon.statement_line l WHERE statement_line_id=? ORDER BY file_id, line_seq
                        """)
                        .param(row.statementLineId())
                        .query((rs, n) -> Map.of("file_id", rs.getString(1), "line", mapper.readTree(rs.getString(2))))
                        .list();
                int sequence = ++seq;
                jdbc.sql("""
                        INSERT INTO recon.statement_line (file_id, line_seq, statement_line_id, line_type,
                            processor_ref, batch_id, gross, fee, net, occurred_at, settlement_date, memo, recorded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                        .params(
                                id,
                                sequence,
                                row.statementLineId(),
                                row.lineType(),
                                row.processorRef(),
                                row.batchId(),
                                row.gross(),
                                row.fee(),
                                row.net(),
                                Instant.parse(row.occurredAt()).atOffset(ZoneOffset.UTC),
                                LocalDate.parse(row.settlementDate()),
                                row.memo(),
                                now)
                        .update();
                String current = jdbc.sql(
                                "SELECT (to_jsonb(l) - 'file_id' - 'line_seq' - 'recorded_at')::text FROM recon.statement_line l WHERE file_id=? AND line_seq=?")
                        .params(id, sequence)
                        .query(String.class)
                        .single();
                var conflicts = previous.stream()
                        .filter(p -> !json.canonical(json.write(Objects.requireNonNull(p.get("line"))))
                                .equals(json.canonical(current)))
                        .toList();
                if (!conflicts.isEmpty()) {
                    issues.open(
                            "STATEMENT_VERSION_CONFLICT",
                            "statement_line",
                            row.statementLineId(),
                            json.write(conflicts),
                            current,
                            json.write(List.of(Map.of(
                                    "file_id",
                                    id,
                                    "statement_line_id",
                                    row.statementLineId(),
                                    "batch_id",
                                    row.batchId(),
                                    "line_seq",
                                    sequence))));
                }
            }
            return new Result(id, accepted ? "ACCEPTED" : "REJECTED", accepted ? null : "FILE_INCOMPLETE");
        }));
    }

    private JsonNode completePage(String value) {
        JsonNode page = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(value);
        if (!page.path("data").isArray()
                || !page.path("has_more").isBoolean()
                || page.path("has_more").booleanValue()) {
            throw new IllegalArgumentException("Incomplete list");
        }
        return page.path("data");
    }

    private static String textAllowEmpty(JsonNode node, String field) {
        if (!node.path(field).isString()) throw new IllegalArgumentException("Invalid text field");
        return node.path(field).asString();
    }

    private static String text(JsonNode node, String field) {
        String value = textAllowEmpty(node, field);
        if (value.isBlank()) throw new IllegalArgumentException("Blank field");
        return value;
    }

    private static long integer(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("Invalid integer");
        return value.longValue();
    }

    private static String hash(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
