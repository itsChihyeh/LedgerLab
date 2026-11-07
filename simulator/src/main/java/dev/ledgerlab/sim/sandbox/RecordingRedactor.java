package dev.ledgerlab.sim.sandbox;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Pure export transform. Stored evidence is never changed; unknown secrets fail closed. */
public final class RecordingRedactor {
    private static final Set<String> PRIVATE = Set.of(
            "email",
            "receipt_email",
            "name",
            "first_name",
            "last_name",
            "address",
            "phone",
            "billing_details",
            "shipping",
            "receipt_url",
            "client_secret",
            "fingerprint");
    private static final Pattern ACCOUNT = Pattern.compile("acct_[A-Za-z0-9_]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN =
            Pattern.compile("sk_|rk_|whsec_|_secret_|@|acct_(?!REDACTED(?![A-Za-z0-9_]))", Pattern.CASE_INSENSITIVE);

    public JsonNode redact(JsonNode input) {
        JsonNode result = transform(input);
        scan(result.toString());
        return result;
    }

    public JsonNode parse(byte[] body, ObjectMapper mapper) {
        try {
            return redact(mapper.reader()
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body));
        } catch (RuntimeException failure) {
            // Jackson errors can quote the raw body. Never expose them or their causes.
            throw new IllegalArgumentException("Recording cannot be safely exported");
        }
    }

    public static void scan(String output) {
        if (FORBIDDEN.matcher(output).find()) throw new IllegalArgumentException("Unsafe recording export");
    }

    private JsonNode transform(JsonNode node) {
        var factory = JsonNodeFactory.instance;
        if (node.isObject()) {
            var result = factory.objectNode();
            for (var property : node.properties()) {
                String key = property.getKey();
                if (PRIVATE.contains(key.toLowerCase(Locale.ROOT))) continue;
                // Stripe risk/network keys contain the literal forbidden key-prefix substrings.
                // None are adapter inputs; omit them so the final byte-level scan stays strict.
                String safeKey = ACCOUNT.matcher(key).replaceAll("acct_" + "REDACTED");
                if (FORBIDDEN.matcher(safeKey).find()) continue;
                result.set(safeKey, transform(property.getValue()));
            }
            return result;
        }
        if (node.isArray()) {
            var result = factory.arrayNode();
            node.forEach(item -> result.add(transform(item)));
            return result;
        }
        if (node.isString()) {
            String value = node.asString();
            if (value.contains("@")) return factory.nullNode();
            return factory.stringNode(ACCOUNT.matcher(value).replaceAll("acct_" + "REDACTED"));
        }
        return node;
    }
}
