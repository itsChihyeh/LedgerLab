package dev.ledgerlab.refunds;

import dev.ledgerlab.outbound.CommandExecution.Response;
import org.jspecify.annotations.Nullable;

/** T08 supplies HTTP. NotFound certifies a complete query; a failed page must return Incomplete. */
public interface ProcessorRefundGateway {
    Response<Outcome> create(String requestId, String chargeRef, long amount, String refundId);

    Response<Outcome> retrieve(String processorRefundRef);

    Response<Outcome> findByRefundId(String chargeRef, String refundId);

    sealed interface Outcome {}

    record Found(
            String processorRefundRef,
            ProcessorRefundFact.Status status,
            @Nullable String failureReason) implements Outcome {}

    record NotFound() implements Outcome {}

    record Incomplete(String reason) implements Outcome {}
    /** Only an explicit refusal proving that no refund was created; never a timeout or 5xx. */
    record Rejected(String errorCode) implements Outcome {}
}
