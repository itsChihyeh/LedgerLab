package dev.ledgerlab.ingestion.internal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** One transport policy for reads and commands; never expose upstream diagnostics as errors. */
final class StripeHttpTransport {
    static final String VERSION = "2026-09-30.endive";
    private final RestClient client;
    private final StripeProperties credentials;
    private final Clock clock;

    StripeHttpTransport(RestClient client, StripeProperties credentials, Clock clock) {
        this.client = client;
        this.credentials = credentials;
        this.clock = clock;
    }

    boolean hasKey() {
        return credentials.apiKey() != null && !credentials.apiKey().isEmpty();
    }

    String safeCode(String value) {
        return value.matches("[A-Za-z0-9_]{1,80}")
                        && !value.startsWith("sk_")
                        && !value.startsWith("rk_")
                        && (!hasKey() || !value.contains(credentials.apiKey()))
                ? value
                : "PROCESSOR_ERROR";
    }

    Response send(
            String method,
            String path,
            Map<String, String> query,
            Map<String, String> form,
            @Nullable String requestId) {
        if (!hasKey()) return new Response(null, "", clock.instant(), "MISSING_API_KEY");
        try {
            var request = client.method(HttpMethod.valueOf(method))
                    .uri(builder -> {
                        builder.path(path);
                        query.forEach(builder::queryParam);
                        return builder.build();
                    })
                    .header("Authorization", "Bearer " + credentials.apiKey())
                    .header("Stripe-Version", VERSION);
            if (requestId != null) request.header("Idempotency-Key", requestId);
            if (method.equals("POST")) {
                var values = new LinkedMultiValueMap<String, String>();
                form.forEach(values::add);
                request.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(values);
            }
            return Objects.requireNonNull(request.exchange((sent, reply) -> {
                String body = new String(reply.getBody().readAllBytes(), StandardCharsets.UTF_8);
                return new Response(reply.getStatusCode().value(), body, clock.instant(), null);
            }));
        } catch (RestClientException e) {
            return new Response(null, "", clock.instant(), "TRANSPORT_FAILURE");
        }
    }

    record Response(
            @Nullable Integer status,
            String body,
            Instant receivedAt,
            @Nullable String failure) {}
}
