package dev.ledgerlab.demo.internal;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A small JSON-over-HTTP client for the simulator's control interface and the application's own public API. */
final class JsonClient {
    /** A reply of any status; 4xx and 5xx are data here, because refusals are part of the scenarios. */
    record Reply(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final RestClient client;
    private final ObjectMapper mapper;
    private final Supplier<String> base;

    JsonClient(Supplier<String> base, Duration timeout, ObjectMapper mapper) {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.mapper = mapper;
        this.base = base;
    }

    Reply get(String path) {
        return send(HttpMethod.GET, path, null, Map.of());
    }

    Reply post(String path, @Nullable Object body) {
        return send(HttpMethod.POST, path, body, Map.of());
    }

    Reply post(String path, @Nullable Object body, Map<String, String> headers) {
        return send(HttpMethod.POST, path, body, headers);
    }

    Reply put(String path, Object body) {
        return send(HttpMethod.PUT, path, body, Map.of());
    }

    /** A text/csv GET, for the bank's statement files. */
    byte[] getBytes(String path) {
        try {
            return Objects.requireNonNull(client.get().uri(uri(path)).exchange((sent, reply) -> {
                if (!reply.getStatusCode().is2xxSuccessful()) {
                    throw new ActionFailed(
                            "GET " + path + " answered " + reply.getStatusCode().value());
                }
                return reply.getBody().readAllBytes();
            }));
        } catch (RestClientException e) {
            throw new ActionFailed("GET " + path + " could not be completed", e);
        }
    }

    /** Posts a CSV file as the bank import expects it. */
    Reply postCsv(String path, byte[] file) {
        try {
            return Objects.requireNonNull(client.post()
                    .uri(uri(path))
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(file)
                    .exchange((sent, reply) ->
                            reply(reply.getStatusCode().value(), reply.getBody().readAllBytes())));
        } catch (RestClientException e) {
            throw new ActionFailed("POST " + path + " could not be completed", e);
        }
    }

    private Reply send(HttpMethod method, String path, @Nullable Object body, Map<String, String> headers) {
        try {
            var request = client.method(method).uri(uri(path));
            headers.forEach(request::header);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
            }
            return Objects.requireNonNull(request.exchange((sent, reply) ->
                    reply(reply.getStatusCode().value(), reply.getBody().readAllBytes())));
        } catch (RestClientException e) {
            throw new ActionFailed(method + " " + path + " could not be completed", e);
        }
    }

    private Reply reply(int status, byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        if (text.isBlank()) return new Reply(status, mapper.createObjectNode());
        try {
            return new Reply(status, mapper.readTree(text));
        } catch (RuntimeException notJson) {
            return new Reply(status, mapper.createObjectNode());
        }
    }

    private URI uri(String path) {
        return URI.create(base.get() + path);
    }
}
