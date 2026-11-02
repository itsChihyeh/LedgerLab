package dev.ledgerlab.sim.relay;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A configured upstream only; never an open forward proxy. Headers are never passed to storage. */
@RestController
@ConditionalOnProperty(name = "sim.relay.outbound-enabled", havingValue = "true")
public final class OutboundRelay implements AutoCloseable {
    private static final String PREFIX = "/relay/stripe-api";
    private static final List<String> REQUEST_HEADERS =
            List.of("Authorization", "Stripe-Version", "Idempotency-Key", "Content-Type");
    private final HttpClient client;
    private final String upstream;
    private final Duration timeout;
    private final Relay relay;
    private final RelayRecording recording;
    private final Clock clock;

    public OutboundRelay(
            Relay relay,
            RelayRecording recording,
            Clock clock,
            @Value("${sim.relay.upstream-api-url:https://api.stripe.com}") String upstream,
            @Value("${sim.relay.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${sim.relay.read-timeout:PT60S}") Duration readTimeout) {
        this.upstream = validateUpstream(upstream);
        if (connectTimeout.isNegative() || connectTimeout.isZero() || readTimeout.toMillis() < 1)
            throw new IllegalArgumentException("Relay timeouts must be positive");
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.timeout = readTimeout;
        this.relay = relay;
        this.recording = recording;
        this.clock = clock;
    }

    static String validateUpstream(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid relay upstream URL");
        }
        String host = uri.getHost();
        boolean loopback = host != null && isLoopback(host);
        if (host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !("https".equalsIgnoreCase(uri.getScheme())
                        || ("http".equalsIgnoreCase(uri.getScheme()) && loopback)))
            throw new IllegalArgumentException("Relay upstream requires HTTPS or loopback HTTP");
        return value.replaceAll("/+$", "");
    }

    private static boolean isLoopback(String host) {
        if (host.equalsIgnoreCase("localhost")) return true;
        try {
            // Literal parsing does not perform DNS; handles compressed and expanded IPv6 alike.
            return java.net.InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @RequestMapping(
            value = "/relay/stripe-api/**",
            method = {
                RequestMethod.GET,
                RequestMethod.HEAD,
                RequestMethod.POST,
                RequestMethod.PUT,
                RequestMethod.PATCH,
                RequestMethod.DELETE,
                RequestMethod.OPTIONS,
                RequestMethod.TRACE
            })
    public ResponseEntity<byte[]> proxy(HttpServletRequest incoming) throws IOException {
        for (String auth : Collections.list(incoming.getHeaders("Authorization"))) {
            String credential = auth.strip().replaceFirst("(?i)^Bearer\\s+", "");
            if (credential.startsWith("sk_live_") || credential.startsWith("rk_live_"))
                return error(403, "relay rejects live credentials");
        }
        Instant received = clock.instant();
        String path =
                incoming.getRequestURI().substring(incoming.getContextPath().length() + PREFIX.length());
        if (path.isEmpty()) path = "/";
        String query = incoming.getQueryString();
        String method = incoming.getMethod();
        byte[] body = incoming.getInputStream().readAllBytes();
        var action = relay.outboundAction(method, path);
        if (action == RelayPlan.OutboundAction.FAIL_BEFORE_SEND) {
            recording.outbound(received, method, path, query, null, action, body, null);
            return error(503, "relay failed before send");
        }
        var request = HttpRequest.newBuilder(URI.create(upstream + path + (query == null ? "" : "?" + query)))
                .timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        for (String header : REQUEST_HEADERS) {
            for (String value : Collections.list(incoming.getHeaders(header))) request.header(header, value);
        }
        // The future completes only after the body has been read, so the deadline also covers a stalled body.
        var pending = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            pending.cancel(true);
            recording.outbound(received, method, path, query, null, action, body, null);
            return error(504, "relay upstream unavailable");
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            recording.outbound(received, method, path, query, null, action, body, null);
            return error(504, "relay upstream unavailable");
        }
        recording.outbound(received, method, path, query, response.statusCode(), action, body, response.body());
        if (action == RelayPlan.OutboundAction.DROP_RESPONSE) return error(504, "relay dropped upstream response");
        var result = ResponseEntity.status(response.statusCode());
        for (String header : List.of("Content-Type", "Request-Id")) {
            response.headers().allValues(header).forEach(value -> result.header(header, value));
        }
        return result.body(response.body());
    }

    @GetMapping("/sim/relay/stats")
    public ResponseEntity<?> stats(@RequestParam String since) {
        try {
            return ResponseEntity.ok(recording.stats(Instant.parse(since)));
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid since"));
        }
    }

    private static ResponseEntity<byte[]> error(int status, String message) {
        return ResponseEntity.status(status)
                .header("Content-Type", "application/json")
                .body(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        client.close();
    }
}
