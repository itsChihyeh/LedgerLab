package dev.ledgerlab.sim.sandbox;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Keeps credentials and raw upstream bodies out of storage, exceptions and HTTP responses. */
@Component
@Profile("sandbox")
public final class SandboxStripe implements AutoCloseable {
    private final String key;
    private final String base;
    private final String runId;
    private final Instant windowStart;
    private final HttpClient client;
    private final JsonMapper json = new JsonMapper();

    public SandboxStripe(
            @Value("${sim.sandbox.stripe-api-key:}") String key,
            @Value("${sim.sandbox.stripe-base-url:http://localhost:8081/relay/stripe-api}") String base,
            @Value("${sim.sandbox.run-id:}") String runId,
            @Value("${sim.sandbox.window-start:}") String windowStart) {
        if (!key.matches("(?:rk|sk)_test_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Sandbox requires a test-mode API key");
        if (!runId.matches("sbx_[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("Sandbox requires an sbx_ run-id");
        try {
            this.windowStart = Instant.parse(windowStart);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Sandbox requires an ISO-8601 window-start");
        }
        try {
            URI uri = URI.create(base);
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid sandbox Stripe base URL");
        }
        this.key = key;
        this.base = base.replaceAll("/+$", "");
        this.runId = runId;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String runId() {
        return runId;
    }

    public Instant windowStart() {
        return windowStart;
    }

    public Reply get(String path) {
        return exchange("GET", path, "", "");
    }

    public Reply pay(String card, long seed, int ordinal, long amount) {
        var fields = Map.of(
                "amount",
                Long.toString(amount),
                "currency",
                "cad",
                "payment_method",
                card,
                "automatic_payment_methods[enabled]",
                "true",
                "automatic_payment_methods[allow_redirects]",
                "never",
                "confirm",
                "true",
                "capture_method",
                "automatic",
                "metadata[ledgerlab_run_id]",
                runId,
                "expand[]",
                "latest_charge");
        String form = fields.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return exchange("POST", "/v1/payment_intents", form, runId + ":pay:" + card + ":" + seed + ":" + ordinal);
    }

    private Reply exchange(String method, String path, String body, String idempotency) {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + key)
                .header("Stripe-Version", "2026-09-30.endive");
        if (!idempotency.isEmpty()) builder.header("Idempotency-Key", idempotency);
        if (method.equals("POST")) builder.header("Content-Type", "application/x-www-form-urlencoded");
        var future = client.sendAsync(
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            var response = future.get(60, TimeUnit.SECONDS);
            JsonNode parsed;
            try {
                parsed = json.readTree(response.body());
            } catch (RuntimeException e) {
                return new Reply(response.statusCode(), json.createObjectNode(), "INVALID_RESPONSE");
            }
            if (parsed == null || !parsed.isObject())
                return new Reply(response.statusCode(), json.createObjectNode(), "INVALID_RESPONSE");
            String error = "";
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                error = safeCode(parsed.path("error").path("code").asText(""));
                if (error.isEmpty()) error = "HTTP_" + response.statusCode();
            }
            return new Reply(response.statusCode(), parsed, error);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(0, json.createObjectNode(), "INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Reply(0, json.createObjectNode(), "TRANSPORT_ERROR");
        } finally {
            future.cancel(true);
        }
    }

    static String safeCode(String value) {
        return value.matches("[A-Za-z][A-Za-z0-9_]{0,79}") && !value.matches(".*(?:sk_|rk_|whsec_|acct_|_secret_).*")
                ? value
                : "";
    }

    static String objectId(JsonNode node, String prefix) {
        String value = node.asText("");
        return value.matches(prefix + "[A-Za-z0-9]{1,100}") && !value.contains("_secret_") ? value : "";
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Reply(int status, JsonNode body, String error) {}

    @Override
    public void close() {
        client.close();
    }
}
