package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.outbound.CommandExecution.Response;
import dev.ledgerlab.payouts.BankPayoutGateway;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class HttpBankPayoutGateway implements BankPayoutGateway {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;

    HttpBankPayoutGateway(RestClient client, ObjectMapper mapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Response<Outcome> submit(String requestId, long amount) {
        return call(
                HttpMethod.POST,
                requestId,
                mapper.writeValueAsString(Map.of(
                        "requestId", requestId, "amount", amount, "currency", "CAD", "destination", "MERCHANT-001")));
    }

    @Override
    public Response<Outcome> query(String requestId) {
        return call(HttpMethod.GET, requestId, null);
    }

    private Response<Outcome> call(HttpMethod method, String requestId, @Nullable String body) {
        var request = client.method(method)
                .uri(
                        method.equals(HttpMethod.POST) ? "/sim/bank/payments" : "/sim/bank/payments/{requestId}",
                        requestId)
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
        // Transport failures propagate to CommandExecution, which archives a safe TRANSPORT_ERROR.
        return Objects.requireNonNull(request.exchange((req, response) -> {
            int status = response.getStatusCode().value();
            String raw = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            var received = clock.instant();
            Outcome outcome;
            try {
                JsonNode node = mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(raw);
                if (status == 404 && method.equals(HttpMethod.GET) && "NOT_FOUND".equals(text(node, "code"))) {
                    outcome = new NotFound();
                } else if (status == 409 && "REQUEST_CONFLICT".equals(text(node, "code"))) {
                    outcome = new Conflict(received);
                } else if (status == 200 || status == 201) {
                    String id = text(node, "requestId");
                    String payment = text(node, "paymentId");
                    Status bankStatus = Status.valueOf(text(node, "status"));
                    String reason = node.hasNonNull("reason") ? text(node, "reason") : null;
                    if (!requestId.equals(id)
                            || !payment.matches("bpm_[A-Za-z0-9_]+")
                            || (reason != null && !reason.matches("[A-Z][A-Z0-9_]{0,63}"))
                            || (bankStatus == Status.REJECTED && reason == null)) {
                        outcome = new Unknown("INVALID_RESPONSE");
                    } else {
                        outcome = new Found(
                                id,
                                payment,
                                bankStatus,
                                LocalDate.parse(text(node, "executionDate")),
                                reason,
                                received);
                    }
                } else {
                    outcome = new Unknown("HTTP_" + status);
                }
            } catch (RuntimeException invalid) {
                outcome = new Unknown("INVALID_RESPONSE");
            }
            return new Response<>(outcome, status, raw, outcome instanceof Unknown unknown ? unknown.code() : null);
        }));
    }

    private static String text(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isString() || value.asString().isBlank()) throw new IllegalArgumentException("INVALID_RESPONSE");
        return value.asString();
    }
}
