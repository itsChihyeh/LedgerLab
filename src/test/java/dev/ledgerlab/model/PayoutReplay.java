package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.outbound.CommandExecution.Response;
import dev.ledgerlab.payouts.BankPayoutGateway;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;

/** Scripted external evidence only: no database access or business-state calculation. */
final class PayoutReplay implements BankPayoutGateway {
    record Reply(String operation, String requestId, String status) {}

    final Deque<Reply> replies = new ArrayDeque<>();
    BankPayoutGateway delegate;
    private final Clock clock;

    PayoutReplay(Clock clock) {
        this.clock = clock;
    }

    public Response<Outcome> submit(String requestId, long amount) {
        if (delegate != null) return delegate.submit(requestId, amount);
        return next("SUBMIT", requestId);
    }

    public Response<Outcome> query(String requestId) {
        if (delegate != null) return delegate.query(requestId);
        return next("QUERY", requestId);
    }

    private Response<Outcome> next(String operation, String id) {
        assertThat(replies).isNotEmpty();
        Reply reply = replies.removeFirst();
        assertThat(reply.operation()).isEqualTo(operation);
        assertThat(reply.requestId()).isEqualTo(id);
        if (reply.status().equals("TIMEOUT") || reply.status().equals("DROP"))
            return new Response<>(new Unknown(reply.status()), null, null, reply.status());
        if (reply.status().equals("NOT_FOUND"))
            return new Response<>(new NotFound(), 404, "{\"code\":\"NOT_FOUND\"}", null);
        return new Response<>(
                new Found(
                        id,
                        "bpm_" + id,
                        Status.valueOf(reply.status()),
                        LocalDate.ofInstant(clock.instant(), SettlementModel.ZONE)
                                .plusDays(1),
                        reply.status().equals("REJECTED") ? "ACCOUNT_CLOSED" : null,
                        clock.instant()),
                200,
                "{\"requestId\":\"" + id + "\",\"status\":\"" + reply.status() + "\"}",
                null);
    }
}
