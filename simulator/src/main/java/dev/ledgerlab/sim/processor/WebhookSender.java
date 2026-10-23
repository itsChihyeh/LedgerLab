package dev.ledgerlab.sim.processor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class WebhookSender implements EventDelivery {
    private final JdbcClient jdbc;
    private final HttpClient client;
    private final Clock clock;
    private final URI target;
    private final String secret;

    public WebhookSender(
            JdbcClient jdbc,
            HttpClient client,
            Clock clock,
            @Value("${sim.stripe.webhook-url}") String target,
            @Value("${sim.stripe.webhook-secret}") String secret) {
        if (secret.isBlank()) throw new IllegalArgumentException("Webhook secret is required");
        this.jdbc = jdbc;
        this.client = client;
        this.clock = clock;
        this.target = URI.create(target);
        this.secret = secret;
        if (!("http".equals(this.target.getScheme()) || "https".equals(this.target.getScheme()))
                || this.target.getHost() == null) throw new IllegalArgumentException("Invalid webhook URL");
    }
    // Single simulator process: serialize delivery to keep each event's signature timestamp increasing.
    @Override
    public synchronized void deliver(String eventId, String payload) {
        long previous = jdbc.sql(
                        "select coalesce(max(signed_at), 0) from sim_processor.webhook_delivery where event_id = ?")
                .param(eventId)
                .query(Long.class)
                .single();
        long signedAt = Math.max(clock.instant().getEpochSecond(), previous + 1);
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", signature(signedAt, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        Integer status = null;
        String error = null;
        try {
            status =
                    client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            error = e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "InterruptedException";
        }
        record(eventId, signedAt, status, error);
    }

    private void record(String id, long signedAt, @Nullable Integer status, @Nullable String error) {
        jdbc.sql("""
                insert into sim_processor.webhook_delivery
                    (event_id, target_url, signed_at, http_status, error, recorded_at)
                values (?, ?, ?, ?, ?, ?)
                """)
                .param(id)
                .param(target.toString())
                .param(signedAt)
                .param(status)
                .param(error)
                .param(Timestamp.from(clock.instant()))
                .update();
    }

    private String signature(long timestamp, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
