package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.refunds.RefundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parse amounts directly from integer tokens, never via floating point or coercion. */
final class RefundJson {
    private RefundJson() {}

    static JsonNode request(ObjectMapper mapper, @Nullable String raw, boolean cancel) {
        if (raw == null) throw new RefundException("INVALID_REQUEST");
        var values = new TreeMap<String, Object>();
        Set<String> fields = cancel ? Set.of("reason") : Set.of("payment_ref", "amount", "merchant_refund_ref");
        try (var parser = mapper.createParser(raw)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new RefundException("INVALID_REQUEST");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw new RefundException("INVALID_REQUEST");
                String name = parser.currentName();
                if (name == null || !fields.contains(name) || values.containsKey(name))
                    throw new RefundException("INVALID_REQUEST");
                JsonToken token = parser.nextToken();
                if (name.equals("amount")) {
                    if (token != JsonToken.VALUE_NUMBER_INT) throw new RefundException("INVALID_AMOUNT");
                    long amount;
                    try {
                        amount = parser.getLongValue();
                    } catch (JacksonException e) {
                        throw new RefundException("INVALID_AMOUNT", e);
                    }
                    if (amount < 1 || amount > 99999999) throw new RefundException("INVALID_AMOUNT");
                    values.put(name, amount);
                } else {
                    if (token != JsonToken.VALUE_STRING) throw new RefundException("INVALID_REQUEST");
                    String value = parser.getString();
                    if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
                        throw new RefundException("INVALID_REQUEST");
                    values.put(name, value);
                }
            }
            if (parser.nextToken() != null) throw new RefundException("INVALID_REQUEST");
        } catch (JacksonException e) {
            throw new RefundException("INVALID_REQUEST", e);
        }
        if (!cancel && !values.containsKey("amount")) throw new RefundException("INVALID_AMOUNT");
        if (!values.keySet().equals(fields)) throw new RefundException("INVALID_REQUEST");
        JsonNode body = mapper.valueToTree(values);
        if (!cancel) {
            String ref = body.path("merchant_refund_ref").asString();
            if (ref.codePointCount(0, ref.length()) > 64
                    || !body.path("payment_ref").asString().matches("ch_[A-Za-z0-9_]+"))
                throw new RefundException("INVALID_REQUEST");
        }
        return body;
    }

    static String canonical(ObjectMapper mapper, JsonNode body) {
        var sorted = new TreeMap<String, JsonNode>();
        body.properties().forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return mapper.writeValueAsString(sorted);
    }

    static String fingerprint(ObjectMapper mapper, String path, JsonNode body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(("POST" + path + canonical(mapper, body)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
