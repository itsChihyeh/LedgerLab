package dev.ledgerlab.sim.relay;

import dev.ledgerlab.sim.processor.SimulationClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class Relay {
    private final JdbcClient jdbc;
    private final HttpClient client;
    private final SimulationClock clock;
    private final URI target;
    private final JsonMapper json = JsonMapper.builder().build();
    private RelayPlan plan = new RelayPlan(List.of());
    private final List<Held> held = new ArrayList<>();
    private long sequence;

    public Relay(
            JdbcClient jdbc,
            HttpClient client,
            SimulationClock clock,
            @Value("${sim.relay.target-url}") String target) {
        this.jdbc = jdbc;
        this.client = client;
        this.clock = clock;
        this.target = URI.create(target);
        if (!("http".equals(this.target.getScheme()) || "https".equals(this.target.getScheme()))
                || this.target.getHost() == null) throw new IllegalArgumentException("Invalid relay target URL");
    }

    public synchronized void replace(RelayPlan replacement) {
        plan = replacement;
    }

    public synchronized RelayPlan plan() {
        return plan;
    }

    // Serialize receipt and completion order; no relay lock is held by the clock while calling us.
    public synchronized void receive(byte[] bytes, @Nullable String signature) {
        JsonNode metadata;
        try {
            metadata = json.readTree(bytes);
        } catch (JacksonException e) {
            metadata = json.createObjectNode();
        }
        String id = metadata.path("id").isString() ? metadata.path("id").asText() : null;
        String type = metadata.path("type").isString() ? metadata.path("type").asText() : null;
        RelayPlan.Rule rule = plan.match(id, type);
        Packet packet = new Packet(++sequence, UUID.randomUUID(), id, bytes.clone(), signature, hash(bytes));
        log(packet, rule.action().name(), 0, null, null);
        switch (rule.action()) {
            case PASS -> forward(packet, "PASS", 1);
            case DUPLICATE -> forward(packet, "DUPLICATE", rule.count());
            case DROP -> {}
            case DELAY -> clock.schedule(Duration.ofSeconds(rule.count()), () -> forward(packet, "DELAY", 1));
            case HOLD_AND_REORDER -> {
                Held pending = new Held(packet, rule.count());
                held.add(pending);
                clock.schedule(Duration.ofMinutes(4), () -> timeout(pending));
            }
        }
    }

    private synchronized void timeout(Held pending) {
        if (held.remove(pending)) forward(pending.packet, "HOLD_TIMEOUT_RELEASE", 1);
    }

    private synchronized void forward(Packet packet, String action, int copies) {
        for (int copy = 0; copy < copies; copy++) {
            var builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            if (packet.signature != null) builder.header("Stripe-Signature", packet.signature);
            var request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(packet.body))
                    .build();
            Integer status = null;
            String error = null;
            try {
                status = client.send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode();
            } catch (IOException e) {
                error = e.getClass().getSimpleName();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "InterruptedException";
            }
            log(packet, action, copy + 1, status, error);
        }
        // One received event counts once, including failed downstream attempts, never once per copy.
        List<Held> ready = new ArrayList<>();
        for (Held pending : held) {
            if (pending.packet.sequence < packet.sequence && --pending.remaining == 0) ready.add(pending);
        }
        held.removeAll(ready);
        for (Held pending : ready) forward(pending.packet, "HOLD_AND_REORDER", 1);
    }

    private void log(Packet packet, String action, int attempt, @Nullable Integer status, @Nullable String error) {
        jdbc.sql("""
                insert into sim_chaos.relay_log
                (receipt_id,event_id,action,attempt_no,http_status,error,recorded_at,payload_sha256)
                values (?,?,?,?,?,?,?,?)
                """)
                .param(packet.receipt)
                .param(packet.eventId)
                .param(action)
                .param(attempt)
                .param(status)
                .param(error)
                .param(Timestamp.from(clock.instant()))
                .param(packet.hash)
                .update();
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Held {
        private final Packet packet;
        private int remaining;

        private Held(Packet packet, int remaining) {
            this.packet = packet;
            this.remaining = remaining;
        }
    }

    @SuppressWarnings("ArrayRecordComponent")
    private record Packet(
            long sequence,
            UUID receipt,
            @Nullable String eventId,
            byte[] body,
            @Nullable String signature,
            String hash) {}
}
