package dev.ledgerlab.sim.relay;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class RelayRecording {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final boolean bodies;
    private final JsonMapper json = new JsonMapper();

    public RelayRecording(JdbcClient jdbc, Clock clock, @Value("${sim.relay.record-bodies:false}") boolean bodies) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.bodies = bodies;
    }

    public void inbound(@Nullable String id, @Nullable String type, byte[] body) {
        if (!bodies) return;
        var now = Timestamp.from(clock.instant());
        jdbc.sql("""
                insert into sim_chaos.inbound_recording (event_id,event_type,received_at,recorded_at,sha256,body)
                values (?,?,?,?,?,?)
                """)
                .param(id)
                .param(type)
                .param(now)
                .param(now)
                .param(hash(body))
                .param(body)
                .update();
    }

    void outbound(
            Instant received,
            String method,
            String path,
            @Nullable String query,
            @Nullable Integer status,
            RelayPlan.OutboundAction action,
            byte[] request,
            byte @Nullable [] response) {
        var names = new ArrayList<String>();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&", -1)) {
                String name = pair.split("=", 2)[0];
                try {
                    names.add(URLDecoder.decode(name, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    names.add(name);
                }
            }
        }
        Instant now = clock.instant();
        jdbc.sql("""
                insert into sim_chaos.api_exchange
                (received_at,recorded_at,method,path,query_parameter_names,upstream_status,action,duration_ms,
                 request_sha256,response_sha256,request_body,response_body)
                values (?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
                """)
                .param(Timestamp.from(received))
                .param(Timestamp.from(now))
                .param(method)
                .param(path)
                .param(json.writeValueAsString(names))
                .param(status)
                .param(action.name())
                .param(Math.max(0, Duration.between(received, now).toMillis()))
                .param(hash(request))
                .param(response == null ? null : hash(response))
                .param(11, bodies ? request : null, java.sql.Types.BINARY)
                .param(12, bodies ? response : null, java.sql.Types.BINARY)
                .update();
    }

    public Map<String, Map<String, Long>> stats(Instant since) {
        Map<String, Long> inbound = new LinkedHashMap<>();
        for (var action : RelayPlan.Action.values()) inbound.put(action.name(), 0L);
        inbound.put("HOLD_TIMEOUT_RELEASE", 0L);
        Map<String, Long> outbound = new LinkedHashMap<>();
        for (var action : RelayPlan.OutboundAction.values()) outbound.put(action.name(), 0L);
        jdbc.sql("""
                select action,count(*) as n from sim_chaos.relay_log where recorded_at >= ?
                and (attempt_no=0 or action='HOLD_TIMEOUT_RELEASE') group by action
                """)
                .param(Timestamp.from(since))
                .query((rs, row) -> {
                    inbound.put(rs.getString("action"), rs.getLong("n"));
                    return 0;
                })
                .list();
        jdbc.sql("select action,count(*) as n from sim_chaos.api_exchange where received_at >= ? group by action")
                .param(Timestamp.from(since))
                .query((rs, row) -> {
                    outbound.put(rs.getString("action"), rs.getLong("n"));
                    return 0;
                })
                .list();
        return Map.of("inbound", inbound, "outbound", outbound);
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
