package dev.ledgerlab.investigation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class QueryModels {
    private QueryModels() {}

    record Batch(
            String batchId,
            long payoutAmount,
            String currency,
            boolean automatic,
            String method,
            @Nullable LocalDate settlementDate,
            @Nullable LocalDate arrivalDate,
            @Nullable Long gross,
            @Nullable Long fees,
            @Nullable Long refunds,
            @Nullable Long payoutFailuresReturned,
            @Nullable Long net,
            String status,
            @Nullable String holdReason,
            String sourceEventId,
            @Nullable Long linesFetchId,
            Instant occurredAt,
            Instant recordedAt,
            Instant updatedAt) {}

    static RowMapper<Batch> batch(ObjectMapper json) {
        return (rs, row) -> new Batch(
                rs.getString("batch_id"),
                rs.getLong("payout_amount"),
                rs.getString("currency"),
                rs.getBoolean("automatic"),
                rs.getString("method"),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getObject("arrival_date", LocalDate.class),
                rs.getObject("gross", Long.class),
                rs.getObject("fees", Long.class),
                rs.getObject("refunds", Long.class),
                rs.getObject("payout_failures_returned", Long.class),
                rs.getObject("net", Long.class),
                rs.getString("status"),
                rs.getString("hold_reason"),
                rs.getString("source_event_id"),
                rs.getObject("lines_fetch_id", Long.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    record BatchLine(
            String batchId,
            int lineNo,
            String lineType,
            String processorRef,
            String balanceTxnId,
            long gross,
            long fee,
            long net,
            Instant occurredAt,
            Instant recordedAt) {}

    static RowMapper<BatchLine> batchLine(ObjectMapper json) {
        return (rs, row) -> new BatchLine(
                rs.getString("batch_id"),
                rs.getInt("line_no"),
                rs.getString("line_type"),
                rs.getString("processor_ref"),
                rs.getString("balance_txn_id"),
                rs.getLong("gross"),
                rs.getLong("fee"),
                rs.getLong("net"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant());
    }

    record BatchTransition(
            long id,
            String subjectId,
            @Nullable String fromStatus,
            String toStatus,
            String evidenceType,
            String evidenceId,
            Instant occurredAt,
            Instant recordedAt,
            @Nullable String holdReason,
            @Nullable Long linesFetchId) {}

    static RowMapper<BatchTransition> batchTransition(ObjectMapper json) {
        return (rs, row) -> new BatchTransition(
                rs.getLong("id"),
                rs.getString("subject_id"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("evidence_type"),
                rs.getString("evidence_id"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("hold_reason"),
                rs.getObject("lines_fetch_id", Long.class));
    }

    record Entry(
            String id,
            long seq,
            String entryType,
            String sourceKey,
            int postingCount,
            long totalAmount,
            Instant occurredAt,
            Instant recordedAt,
            String subjectType,
            String subjectId,
            @Nullable String reversesEntryId,
            @Nullable String correctionId) {}

    static RowMapper<Entry> entry(ObjectMapper json) {
        return (rs, row) -> new Entry(
                rs.getString("id"),
                rs.getLong("seq"),
                rs.getString("entry_type"),
                rs.getString("source_key"),
                rs.getInt("posting_count"),
                rs.getLong("total_amount"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getString("reverses_entry_id"),
                rs.getString("correction_id"));
    }

    record BankLine(
            long importOrder,
            String bankTxnId,
            int version,
            String fileId,
            String direction,
            String currency,
            String status,
            long amount,
            String reference,
            @Nullable String returnOf,
            @Nullable String reasonCode,
            LocalDate bookingDate,
            LocalDate valueDate,
            String memo,
            String matchStatus,
            @Nullable String matchedBatchId,
            @Nullable Instant waitUntil,
            Instant recordedAt) {}

    static RowMapper<BankLine> bankLine(ObjectMapper json) {
        return (rs, row) -> new BankLine(
                rs.getLong("import_order"),
                rs.getString("bank_txn_id"),
                rs.getInt("version"),
                rs.getString("file_id"),
                rs.getString("direction"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getLong("amount"),
                rs.getString("reference"),
                rs.getString("return_of"),
                rs.getString("reason_code"),
                rs.getObject("booking_date", LocalDate.class),
                rs.getObject("value_date", LocalDate.class),
                rs.getString("memo"),
                rs.getString("match_status"),
                rs.getString("matched_batch_id"),
                instant(rs, "wait_until"),
                rs.getTimestamp("recorded_at").toInstant());
    }

    record StatementLine(
            String fileId,
            int lineSeq,
            String statementLineId,
            String lineType,
            String processorRef,
            String batchId,
            long gross,
            long fee,
            long net,
            Instant occurredAt,
            LocalDate settlementDate,
            String memo,
            Instant recordedAt) {}

    static RowMapper<StatementLine> statementLine(ObjectMapper json) {
        return (rs, row) -> new StatementLine(
                rs.getString("file_id"),
                rs.getInt("line_seq"),
                rs.getString("statement_line_id"),
                rs.getString("line_type"),
                rs.getString("processor_ref"),
                rs.getString("batch_id"),
                rs.getLong("gross"),
                rs.getLong("fee"),
                rs.getLong("net"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("settlement_date", LocalDate.class),
                rs.getString("memo"),
                rs.getTimestamp("recorded_at").toInstant());
    }

    record Issue(
            String id,
            String category,
            String subjectType,
            String subjectId,
            String detectedBy,
            @Nullable String firstRunId,
            @Nullable String lastRunId,
            String status,
            JsonNode expected,
            JsonNode actual,
            JsonNode evidenceRefs,
            @Nullable String explanation,
            @Nullable String resolutionCorrectionId,
            Instant recordedAt,
            Instant updatedAt,
            @Nullable String fingerprint,
            int reopenCount) {}

    static RowMapper<Issue> issue(ObjectMapper json) {
        return (rs, row) -> new Issue(
                rs.getString("id"),
                rs.getString("category"),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                rs.getString("detected_by"),
                rs.getString("first_run_id"),
                rs.getString("last_run_id"),
                rs.getString("status"),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("expected"), "null")),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("actual"), "null")),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("evidence_refs"), "null")),
                rs.getString("explanation"),
                rs.getString("resolution_correction_id"),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("fingerprint"),
                rs.getInt("reopen_count"));
    }

    record Finding(
            String id,
            String runId,
            String category,
            String subjectType,
            String subjectId,
            JsonNode expected,
            JsonNode actual,
            JsonNode evidenceRefs,
            String fingerprint,
            @Nullable Instant dueAt,
            String issueId,
            Instant recordedAt) {}

    static RowMapper<Finding> finding(ObjectMapper json) {
        return (rs, row) -> new Finding(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("category"),
                rs.getString("subject_type"),
                rs.getString("subject_id"),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("expected"), "null")),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("actual"), "null")),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("evidence_refs"), "null")),
                rs.getString("fingerprint"),
                instant(rs, "due_at"),
                rs.getString("issue_id"),
                rs.getTimestamp("recorded_at").toInstant());
    }

    record IssueTransition(
            String id,
            String issueId,
            @Nullable String fromStatus,
            String toStatus,
            @Nullable String runId,
            String reason,
            String explanation,
            JsonNode evidenceRefs,
            Instant recordedAt) {}

    static RowMapper<IssueTransition> issueTransition(ObjectMapper json) {
        return (rs, row) -> new IssueTransition(
                rs.getString("id"),
                rs.getString("issue_id"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getString("run_id"),
                rs.getString("reason"),
                rs.getString("explanation"),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("evidence_refs"), "null")),
                rs.getTimestamp("recorded_at").toInstant());
    }

    record Run(
            String id,
            String kind,
            String status,
            JsonNode inputFileIds,
            JsonNode inputHashes,
            String ruleVersion,
            Instant cutoffAt,
            Instant evidenceAsOf,
            Instant startedAt,
            @Nullable Instant finishedAt,
            JsonNode summary,
            Instant recordedAt) {}

    static RowMapper<Run> run(ObjectMapper json) {
        return (rs, row) -> new Run(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("status"),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("input_file_ids"), "null")),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("input_hashes"), "null")),
                rs.getString("rule_version"),
                rs.getTimestamp("cutoff_at").toInstant(),
                rs.getTimestamp("evidence_as_of").toInstant(),
                rs.getTimestamp("started_at").toInstant(),
                instant(rs, "finished_at"),
                json.readTree(java.util.Objects.requireNonNullElse(rs.getString("summary"), "null")),
                rs.getTimestamp("recorded_at").toInstant());
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
