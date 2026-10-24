package dev.ledgerlab.refunds;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A state observation, timed by the evidence that brought that state, not Refund.created. */
public record ProcessorRefundFact(
        @Nullable String refundId,
        @Nullable String processorRefundRef,
        Status status,
        @Nullable String failureReason,
        Source source,
        String evidenceId,
        Instant occurredAt,
        @Nullable Long amount,
        @Nullable String chargeRef) {
    /** Existing command/query callers have already validated their response identity. */
    public ProcessorRefundFact(
            @Nullable String refundId,
            @Nullable String processorRefundRef,
            Status status,
            @Nullable String failureReason,
            Source source,
            String evidenceId,
            Instant occurredAt) {
        this(refundId, processorRefundRef, status, failureReason, source, evidenceId, occurredAt, null, null);
    }

    public ProcessorRefundFact {
        if (refundId == null && processorRefundRef == null)
            throw new IllegalArgumentException("Refund identity required");
        if (refundId != null && !refundId.startsWith("rfd_")) throw new IllegalArgumentException("Invalid refund id");
        if (processorRefundRef != null && !processorRefundRef.startsWith("re_"))
            throw new IllegalArgumentException("Invalid processor refund id");
        if (evidenceId.isBlank()) throw new IllegalArgumentException("Evidence id required");
        Objects.requireNonNull(status);
        Objects.requireNonNull(source);
        Objects.requireNonNull(occurredAt);
    }

    public enum Status {
        pending,
        succeeded,
        failed,
        canceled
    }

    public enum Source {
        COMMAND_RESPONSE,
        COMMAND_QUERY,
        WEBHOOK,
        BATCH_LINE
    }
}
