package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.outbound.CommandExecution.Response;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundFact.Status;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class StripeRefundAdapter implements ProcessorRefundGateway {
    private final StripeHttpTransport transport;
    private final StripeFetchArchive archive;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final IngestionIssues issues;
    private final TransactionTemplate outside;
    private final TransactionTemplate issueTransaction;

    StripeRefundAdapter(
            RestClient client,
            StripeProperties credentials,
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock,
            IngestionIssues issues) {
        transport = new StripeHttpTransport(client, credentials, clock);
        archive = new StripeFetchArchive(jdbc, manager, clock);
        this.mapper = mapper;
        this.clock = clock;
        this.issues = issues;
        outside = new TransactionTemplate(manager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        issueTransaction = new TransactionTemplate(manager);
        issueTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Response<Outcome> create(String requestId, String chargeRef, long amount, String refundId) {
        return fetch(
                "POST",
                "/v1/refunds",
                Map.of(),
                Map.of("charge", chargeRef, "amount", Long.toString(amount), "metadata[ledgerlab_refund_id]", refundId),
                requestId,
                null,
                validId(chargeRef, "ch_") && validId(requestId, "rfd_") && validId(refundId, "rfd_") && amount > 0);
    }

    @Override
    public Response<Outcome> retrieve(String processorRefundRef) {
        return fetch(
                "GET",
                "/v1/refunds/" + processorRefundRef,
                Map.of(),
                Map.of(),
                null,
                null,
                validId(processorRefundRef, "re_"));
    }

    @Override
    public Response<Outcome> findByRefundId(String chargeRef, String refundId) {
        return fetch(
                "GET",
                "/v1/refunds",
                Map.of("charge", chargeRef),
                Map.of(),
                null,
                refundId,
                validId(chargeRef, "ch_") && validId(refundId, "rfd_"));
    }

    private Response<Outcome> fetch(
            String method,
            String path,
            Map<String, String> query,
            Map<String, String> form,
            @Nullable String requestId,
            @Nullable String refundId,
            boolean valid) {
        return Objects.requireNonNull(outside.execute(ignored -> {
            var call = new Call();
            Outcome outcome = valid
                    ? read(method, path, query, form, requestId, refundId, call)
                    : new Incomplete("INVALID_REQUEST");
            boolean paginated = refundId != null;
            String body = paginated
                    ? mapper.writeValueAsString(call.bodies)
                    : call.bodies.isEmpty() ? "" : call.bodies.getFirst();
            String purpose = method.equals("POST") ? "REFUND_COMMAND" : "REFUND_QUERY";
            String error = outcome instanceof Incomplete incomplete
                    ? incomplete.reason()
                    : outcome instanceof Rejected rejected ? rejected.errorCode() : null;
            var request = new LinkedHashMap<String, Object>();
            request.put("method", method);
            request.put("path", path);
            request.put("query", query);
            request.put("form", form);
            if (requestId != null) request.put("Idempotency-Key", requestId);
            request.put("purpose", purpose);
            request.put("stripe_version", StripeHttpTransport.VERSION);
            request.put("attempts", call.attempts);
            request.put("outcome", error == null ? "COMPLETE" : error);
            long id = archive.archive(
                    purpose,
                    null,
                    mapper.writeValueAsString(request),
                    body,
                    StripeFetchArchive.sha256(body),
                    !(outcome instanceof Incomplete),
                    clock.instant());
            if (call.matches.size() > 1) {
                issueTransaction.executeWithoutResult(tx -> issues.open(
                        "REFUND_STATE_CONFLICT",
                        "refund",
                        Objects.requireNonNull(refundId),
                        "{\"matching_refunds\":1}",
                        mapper.writeValueAsString(Map.of(
                                "reason",
                                "DUPLICATE_PROCESSOR_REFUNDS",
                                "processor_refund_refs",
                                call.matches.stream()
                                        .map(Found::processorRefundRef)
                                        .toList())),
                        mapper.writeValueAsString(List.of(Map.of("source", "processor_fetch", "id", id)))));
            }
            return new Response<>(outcome, call.status, body, error);
        }));
    }

    private Outcome read(
            String method,
            String path,
            Map<String, String> originalQuery,
            Map<String, String> form,
            @Nullable String requestId,
            @Nullable String refundId,
            Call call) {
        var query = new LinkedHashMap<>(originalQuery);
        boolean paginated = refundId != null;
        if (paginated) query.put("limit", "100");
        var cursors = new HashSet<String>();
        var objects = new HashSet<String>();
        try {
            while (true) {
                var attempt = new LinkedHashMap<String, Object>();
                attempt.put("query", Map.copyOf(query));
                call.attempts.add(attempt);
                var response = transport.send(method, path, query, form, requestId);
                call.status = response.status();
                if (response.failure() != null) {
                    attempt.put("error", response.failure());
                    return new Incomplete(response.failure());
                }
                call.bodies.add(response.body());
                int status = Objects.requireNonNull(response.status());
                attempt.put("status", status);
                if (status < 200 || status >= 300) {
                    if (status >= 400 && status < 500) {
                        JsonNode error = parse(response.body()).path("error");
                        StripeReadParser.text(error, "type");
                        String code = StripeReadParser.text(error, "code");
                        // A query error, including 404, cannot authorize another charge refund.
                        if (method.equals("POST")) return new Rejected(transport.safeCode(code), response.receivedAt());
                    }
                    return new Incomplete("HTTP_STATUS_" + status);
                }
                JsonNode root = parse(response.body());
                if (!paginated) {
                    Found found = refund(root, response.receivedAt());
                    if (method.equals("GET") && !path.endsWith("/" + found.processorRefundRef()))
                        return new Incomplete("REFUND_RESPONSE_MISMATCH");
                    return found;
                }
                JsonNode data = root.path("data");
                if (!data.isArray()) return new Incomplete("INVALID_RESPONSE");
                boolean more = StripeReadParser.bool(root, "has_more");
                for (JsonNode node : data) {
                    Found found = refund(node, response.receivedAt());
                    if (!objects.add(found.processorRefundRef())) return new Incomplete("INVALID_PAGINATION");
                    if (!Objects.requireNonNull(found.details()).chargeRef().equals(query.get("charge")))
                        return new Incomplete("REFUND_RESPONSE_MISMATCH");
                    if (Objects.requireNonNull(refundId).equals(found.details().refundId())) call.matches.add(found);
                }
                if (!more) {
                    if (call.matches.size() > 1) return new Incomplete("DUPLICATE_PROCESSOR_REFUNDS");
                    return call.matches.isEmpty() ? new NotFound() : call.matches.getFirst();
                }
                if (data.isEmpty()) return new Incomplete("INVALID_PAGINATION");
                String cursor = StripeReadParser.text(data.path(data.size() - 1), "id");
                if (!cursors.add(cursor)) return new Incomplete("INVALID_PAGINATION");
                query.put("starting_after", cursor);
            }
        } catch (RuntimeException invalid) {
            // Malformed JSON and unsupported object shapes are uncertain, never definitive refusals.
            return new Incomplete("INVALID_RESPONSE");
        }
    }

    private JsonNode parse(String body) {
        return mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(body);
    }

    private Found refund(JsonNode node, java.time.Instant receivedAt) {
        String id = StripeReadParser.text(node, "id");
        String charge = StripeReadParser.text(node, "charge");
        long amount = StripeReadParser.integer(node, "amount");
        JsonNode metadata = node.path("metadata");
        if (!validId(id, "re_")
                || !validId(charge, "ch_")
                || amount <= 0
                || !metadata.isObject()
                || (node.has("livemode") && StripeReadParser.bool(node, "livemode")))
            throw new IllegalArgumentException("INVALID_RESPONSE");
        String refundId =
                metadata.has("ledgerlab_refund_id") ? StripeReadParser.text(metadata, "ledgerlab_refund_id") : null;
        String failure = node.hasNonNull("failure_reason")
                ? transport.safeCode(StripeReadParser.text(node, "failure_reason"))
                : null;
        return new Found(
                id,
                Status.valueOf(StripeReadParser.text(node, "status")),
                failure,
                new Details(amount, charge, refundId, receivedAt));
    }

    private static boolean validId(String id, String prefix) {
        return id.matches(prefix + "[A-Za-z0-9_]+");
    }

    private static final class Call {
        private final List<String> bodies = new ArrayList<>();
        private final List<Map<String, Object>> attempts = new ArrayList<>();
        private final List<Found> matches = new ArrayList<>();
        private @Nullable Integer status;
    }
}
