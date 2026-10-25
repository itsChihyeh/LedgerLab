package dev.ledgerlab.refunds;

import dev.ledgerlab.outbound.CommandExecution.Response;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** NotFound certifies a complete query; a failed page must return Incomplete. */
public interface ProcessorRefundGateway {
    Response<Outcome> create(String requestId, String chargeRef, long amount, String refundId);

    Response<Outcome> retrieve(String processorRefundRef);

    Response<Outcome> findByRefundId(String chargeRef, String refundId);

    sealed interface Outcome {}

    record Found(
            String processorRefundRef,
            ProcessorRefundFact.Status status,
            @Nullable String failureReason,
            @Nullable Details details)
            implements Outcome {
        public Found(String processorRefundRef, ProcessorRefundFact.Status status, @Nullable String failureReason) {
            this(processorRefundRef, status, failureReason, null);
        }
    }

    /** Parsed HTTP identity and the instant the response body was received, before archiving. */
    record Details(long amount, String chargeRef, @Nullable String refundId, Instant receivedAt) {}

    record NotFound() implements Outcome {}

    record Incomplete(String reason) implements Outcome {}
    /** Only an explicit refusal proving that no refund was created; never a timeout or 5xx. */
    record Rejected(String errorCode, @Nullable Instant receivedAt) implements Outcome {
        public Rejected(String errorCode) {
            this(errorCode, null);
        }
    }
}
