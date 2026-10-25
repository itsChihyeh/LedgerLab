package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.outbound.CommandExecution.Response;
import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import java.util.ArrayDeque;
import java.util.Deque;
import tools.jackson.databind.ObjectMapper;

/** Exact scripted processor receipts; no database or local business-state lookup. */
final class RefundReplay implements ProcessorRefundGateway {
    record Reply(String operation, Integer status, String body, String error, ScenarioLog.Webhook early) {
        Reply(String operation, Integer status, String body, String error) {
            this(operation, status, body, error, null);
        }
    }

    final Deque<Reply> replies = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper();
    java.util.function.Consumer<ScenarioLog.Webhook> earlyDelivery = event -> {
        throw new AssertionError("Early webhook handler is not installed");
    };

    @Override
    public Response<Outcome> create(String requestId, String chargeRef, long amount, String refundId) {
        assertThat(requestId).isEqualTo(refundId);
        return next("CREATE", refundId);
    }

    @Override
    public Response<Outcome> retrieve(String ref) {
        return next("RETRIEVE", null);
    }

    @Override
    public Response<Outcome> findByRefundId(String chargeRef, String refundId) {
        return next("LIST_ALL", refundId);
    }

    private Response<Outcome> next(String operation, String refundId) {
        assertThat(replies).as("scripted receipt for %s", operation).isNotEmpty();
        Reply reply = replies.removeFirst();
        assertThat(reply.operation()).isEqualTo(operation);
        if (reply.early() != null) earlyDelivery.accept(reply.early());
        Outcome outcome;
        if (reply.error() != null) outcome = new Incomplete(reply.error());
        else if (reply.status() >= 400) outcome = new Rejected("declined");
        else {
            var body = mapper.readTree(reply.body());
            if (operation.equals("LIST_ALL")) {
                assertThat(body.isArray())
                        .as("complete query retains every raw page")
                        .isTrue();
                var found = mapper.createObjectNode();
                for (int i = 0; i < body.size(); i++) {
                    var page = body.get(i);
                    assertThat(page.path("has_more").asBoolean()).isEqualTo(i < body.size() - 1);
                    for (var object : page.path("data"))
                        if (object.path("metadata")
                                .path("ledgerlab_refund_id")
                                .asString()
                                .equals(refundId)) found = (tools.jackson.databind.node.ObjectNode) object;
                }
                body = found;
            }
            outcome = body.has("id")
                    ? new Found(
                            body.path("id").asString(),
                            ProcessorRefundFact.Status.valueOf(
                                    body.path("status").asString()),
                            null)
                    : new NotFound();
        }
        return new Response<>(outcome, reply.status(), reply.body(), reply.error());
    }
}
