package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.payouts.PayoutException;
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
final class PayoutJson {
    private PayoutJson() {}

    static JsonNode request(ObjectMapper mapper, @Nullable String raw) {
        if (raw == null) throw new PayoutException("INVALID_REQUEST");
        var values = new TreeMap<String, Object>();
        Set<String> fields = Set.of("amount", "merchantPayoutRef");
        try (var parser = mapper.createParser(raw)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new PayoutException("INVALID_REQUEST");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw new PayoutException("INVALID_REQUEST");
                String name = parser.currentName();
                if (name == null || !fields.contains(name) || values.containsKey(name))
                    throw new PayoutException("INVALID_REQUEST");
                JsonToken token = parser.nextToken();
                if (name.equals("amount")) {
                    if (token != JsonToken.VALUE_NUMBER_INT) throw new PayoutException("INVALID_AMOUNT");
                    long amount;
                    try {
                        amount = parser.getLongValue();
                    } catch (JacksonException e) {
                        throw new PayoutException("INVALID_AMOUNT", e);
                    }
                    if (amount < 1 || amount > 99999999) throw new PayoutException("INVALID_AMOUNT");
                    values.put(name, amount);
                } else {
                    if (token != JsonToken.VALUE_STRING) throw new PayoutException("INVALID_REQUEST");
                    String value = parser.getString();
                    if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
                        throw new PayoutException("INVALID_REQUEST");
                    values.put(name, value);
                }
            }
            if (parser.nextToken() != null) throw new PayoutException("INVALID_REQUEST");
        } catch (JacksonException e) {
            throw new PayoutException("INVALID_REQUEST", e);
        }
        if (!values.containsKey("amount")) throw new PayoutException("INVALID_AMOUNT");
        if (!values.keySet().equals(fields)) throw new PayoutException("INVALID_REQUEST");
        JsonNode body = mapper.valueToTree(values);
        if (!body.path("merchantPayoutRef").asString().matches("[!-~]{1,64}"))
            throw new PayoutException("INVALID_MERCHANT_PAYOUT_REF");
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
