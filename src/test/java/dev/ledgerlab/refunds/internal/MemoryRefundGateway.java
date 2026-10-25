package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.outbound.CommandExecution.Response;
import dev.ledgerlab.refunds.ProcessorRefundFact.Status;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Independent processor state; clearing idempotency does not clear refunds or metadata search. */
final class MemoryRefundGateway implements ProcessorRefundGateway {
    final Map<String, Found> refunds = new HashMap<>();
    final Map<String, Found> keys = new HashMap<>();
    final List<String> calls = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    Status status = Status.succeeded;
    boolean timeoutBefore;
    boolean timeoutAfter;
    boolean incomplete;
    boolean rejected;
    boolean throwTransport;
    int created;
    Runnable afterCreate = () -> {};

    @Override
    public Response<Outcome> create(String requestId, String chargeRef, long amount, String refundId) {
        outside();
        calls.add("CREATE");
        requests.add(requestId);
        if (throwTransport) throw new IllegalStateException("simulated disconnect");
        if (timeoutBefore) return new Response<>(new Incomplete("TIMEOUT"), null, null, "TIMEOUT");
        if (rejected) return new Response<>(new Rejected("charge_already_refunded"), 400, "explicit refusal", null);
        Found found = keys.computeIfAbsent(requestId, ignored -> {
            var result = new Found("re_memory_" + ++created, status, status == Status.failed ? "declined" : null);
            refunds.put(refundId, result);
            return result;
        });
        afterCreate.run();
        return timeoutAfter
                ? new Response<>(new Incomplete("TIMEOUT"), null, null, "TIMEOUT")
                : new Response<>(found, 200, "processor response", null);
    }

    @Override
    public Response<Outcome> retrieve(String processorRefundRef) {
        outside();
        calls.add("RETRIEVE");
        if (incomplete) return partial();
        return new Response<>(
                refunds.values().stream()
                        .filter(f -> f.processorRefundRef().equals(processorRefundRef))
                        .<Outcome>map(f -> f)
                        .findFirst()
                        .orElseGet(NotFound::new),
                200,
                "query response",
                null);
    }

    @Override
    public Response<Outcome> findByRefundId(String chargeRef, String refundId) {
        outside();
        calls.add("LIST_ALL");
        if (incomplete) return partial();
        Outcome result = refunds.get(refundId);
        return new Response<>(result == null ? new NotFound() : result, 200, "all pages", null);
    }

    private Response<Outcome> partial() {
        return new Response<>(new Incomplete("PAGE_INTERRUPTED"), 503, "partial page", "PAGE_INTERRUPTED");
    }

    private void outside() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();
    }
}
