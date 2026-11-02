package dev.ledgerlab.payouts;

import dev.ledgerlab.outbound.CommandExecution.Response;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Bank request ids are permanent idempotency keys. API receipts never prove booked cash movement. */
public interface BankPayoutGateway {
    Response<Outcome> submit(String requestId, long amount);

    Response<Outcome> query(String requestId);

    sealed interface Outcome {}

    enum Status {
        ACCEPTED,
        EXECUTED,
        REJECTED,
        RETURNED
    }

    record Found(
            String requestId,
            String paymentId,
            Status status,
            LocalDate executionDate,
            @Nullable String reason,
            Instant receivedAt)
            implements Outcome {}

    record NotFound() implements Outcome {}

    record Conflict(Instant receivedAt) implements Outcome {}

    record Unknown(String code) implements Outcome {}
}
