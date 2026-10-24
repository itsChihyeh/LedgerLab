package dev.ledgerlab.reconciliation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class ReconJson {
    private final ObjectMapper mapper;

    public ReconJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public String canonical(String json) {
        return normalize(mapper.readTree(json));
    }

    public String array(String json) {
        if (!mapper.readTree(json).isArray()) throw new IllegalArgumentException("Evidence must be a JSON array");
        return canonical(json);
    }

    private String normalize(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            List<String> entries = new ArrayList<>();
            fields.forEach((key, value) -> entries.add(write(key) + ":" + normalize(value)));
            return "{" + String.join(",", entries) + "}";
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(item -> items.add(normalize(item)));
            return "[" + String.join(",", items) + "]";
        }
        if (node.isNumber() && (!node.isIntegralNumber() || !node.canConvertToLong())) {
            throw new IllegalArgumentException("Evidence numbers must be long integers");
        }
        return write(node);
    }

    public String fingerprint(Finding finding) {
        String content =
                "[" + write(finding.category()) + "," + write(finding.subjectType()) + "," + write(finding.subjectId())
                        + "," + canonical(finding.expected()) + "," + canonical(finding.actual()) + "]";
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
