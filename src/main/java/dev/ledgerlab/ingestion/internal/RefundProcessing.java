package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.RefundEvidence;
import dev.ledgerlab.refunds.RefundException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Joins inbox's business transaction, including rejection evidence and history writeback. */
@Component
final class RefundProcessing {
    private final RefundParser parser;
    private final RefundEvidence evidence;
    private final IngestionIssues issues;
    private final ObjectMapper mapper;

    RefundProcessing(RefundParser parser, RefundEvidence evidence, IngestionIssues issues, ObjectMapper mapper) {
        this.parser = parser;
        this.evidence = evidence;
        this.issues = issues;
        this.mapper = mapper;
    }

    String apply(String eventId, byte[] payload) {
        RefundParser.Parsed refund;
        try {
            refund = parser.parse(payload);
        } catch (RefundParser.Rejected invalid) {
            String result = Objects.requireNonNull(invalid.getMessage());
            issues.open(
                    result.equals("LIVEMODE") ? "LIVEMODE_EVENT" : "UNSUPPORTED_EVENT",
                    "event",
                    eventId,
                    "{}",
                    mapper.writeValueAsString(Map.of("result", result)),
                    references(eventId));
            return result;
        }
        // A missing/foreign metadata value must never fall back to the processor refund id.
        if (!refund.refundId().startsWith("rfd_")) return unknown(eventId, refund);
        try {
            return switch (evidence.apply(refund.fact(eventId))) {
                case APPLIED -> "APPLIED";
                case DUPLICATE_FACT -> "DUPLICATE_FACT";
                case CONFLICT -> "BUSINESS_CONFLICT";
            };
        } catch (RefundException missing) {
            if (!missing.code().equals("REFUND_NOT_FOUND")) throw missing;
            return unknown(eventId, refund);
        }
    }

    private String unknown(String eventId, RefundParser.Parsed refund) {
        issues.open(
                "MISSING_IN_LEDGER",
                "refund",
                refund.ref(),
                "{}",
                mapper.writeValueAsString(Map.of(
                        "charge",
                        refund.charge(),
                        "amount",
                        refund.amount(),
                        "ledgerlab_refund_id",
                        refund.refundId())),
                references(eventId));
        return "UNKNOWN_REFUND";
    }

    private String references(String eventId) {
        return mapper.writeValueAsString(List.of(Map.of("table", "io.inbox_event", "id", eventId)));
    }
}
