package dev.ledgerlab.sim.sandbox;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@Profile("sandbox")
public final class RecordingController {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RecordingRedactor redactor = new RecordingRedactor();

    public RecordingController(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/sim/sandbox/recordings")
    public List<Map<String, Object>> recordings(
            @RequestParam String kind,
            @RequestParam(name = "event_type", defaultValue = "") String eventType,
            @RequestParam(name = "path_prefix", defaultValue = "") String pathPrefix,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(name = "after_id", defaultValue = "0") long afterId) {
        if (limit < 1 || limit > 1000 || afterId < 0 || !(kind.equals("webhook") || kind.equals("api"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recording filter");
        }
        try {
            List<Map<String, Object>> result;
            if (kind.equals("webhook")) {
                result = jdbc.sql("""
                        SELECT id, event_type, recorded_at, body FROM sim_chaos.inbound_recording
                        WHERE id > :after AND (:type = '' OR event_type = :type) ORDER BY id LIMIT :limit
                        """)
                        .param("after", afterId)
                        .param("type", eventType)
                        .param("limit", limit)
                        .query((rs, n) -> Map.<String, Object>of(
                                "id",
                                rs.getLong("id"),
                                "kind",
                                kind,
                                "event_type",
                                rs.getString("event_type") == null ? "" : rs.getString("event_type"),
                                "recorded_at",
                                rs.getTimestamp("recorded_at").toInstant().toString(),
                                "body",
                                redactor.parse(rs.getBytes("body"), mapper)))
                        .list();
            } else {
                result = jdbc.sql("""
                        SELECT id, method, path, upstream_status, recorded_at, response_body
                        FROM sim_chaos.api_exchange WHERE id > :after AND starts_with(path, :prefix)
                        AND response_body IS NOT NULL ORDER BY id LIMIT :limit
                        """)
                        .param("after", afterId)
                        .param("prefix", pathPrefix)
                        .param("limit", limit)
                        .query((rs, n) -> Map.<String, Object>of(
                                "id",
                                rs.getLong("id"),
                                "kind",
                                kind,
                                "method",
                                rs.getString("method"),
                                "path",
                                rs.getString("path"),
                                "status",
                                rs.getInt("upstream_status"),
                                "recorded_at",
                                rs.getTimestamp("recorded_at").toInstant().toString(),
                                "body",
                                redactor.parse(rs.getBytes("response_body"), mapper)))
                        .list();
            }
            RecordingRedactor.scan(mapper.writeValueAsString(result));
            return result;
        } catch (IllegalArgumentException unsafe) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Recording cannot be safely exported");
        }
    }
}
