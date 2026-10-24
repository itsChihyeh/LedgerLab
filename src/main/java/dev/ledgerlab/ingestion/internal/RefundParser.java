package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.refunds.ProcessorRefundFact;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Refund webhook admission; the envelope has already passed StripeEventParser. */
@Component
final class RefundParser {
    private final ObjectMapper mapper;

    RefundParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Parsed parse(byte[] payload) {
        JsonNode event = mapper.readTree(payload);
        JsonNode refund = event.path("data").path("object");
        if (event.path("livemode").asBoolean(false) || refund.path("livemode").asBoolean(false))
            throw new Rejected("LIVEMODE");
        JsonNode amount = refund.path("amount");
        if (!amount.isIntegralNumber()
                || !amount.canConvertToLong()
                || amount.longValue() < 1
                || amount.longValue() > 99999999) throw new Rejected("INVALID_AMOUNT");
        if (!text(refund, "currency").equalsIgnoreCase("cad")) throw new Rejected("UNSUPPORTED_CURRENCY");
        String ref = text(refund, "id");
        String charge = text(refund, "charge");
        if (!ref.matches("re_[A-Za-z0-9_]+") || !charge.matches("ch_[A-Za-z0-9_]+"))
            throw new Rejected("UNSUPPORTED_TYPE");
        ProcessorRefundFact.Status status;
        try {
            status = ProcessorRefundFact.Status.valueOf(text(refund, "status"));
        } catch (IllegalArgumentException invalid) {
            throw new Rejected("UNSUPPORTED_TYPE");
        }
        JsonNode reason = refund.path("failure_reason");
        if (!reason.isMissingNode() && !reason.isNull() && !reason.isString()) throw new Rejected("UNSUPPORTED_TYPE");
        return new Parsed(
                ref,
                text(refund.path("metadata"), "ledgerlab_refund_id"),
                charge,
                amount.longValue(),
                status,
                reason.isString() ? reason.asString() : null,
                Instant.ofEpochSecond(event.path("created").longValue()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : "";
    }

    record Parsed(
            String ref,
            String refundId,
            String charge,
            long amount,
            ProcessorRefundFact.Status status,
            @Nullable String failureReason,
            Instant occurredAt) {
        ProcessorRefundFact fact(String eventId) {
            return new ProcessorRefundFact(
                    refundId,
                    ref,
                    status,
                    failureReason,
                    ProcessorRefundFact.Source.WEBHOOK,
                    eventId,
                    occurredAt,
                    amount,
                    charge);
        }
    }

    static final class Rejected extends RuntimeException {
        Rejected(String result) {
            super(result);
        }
    }
}
