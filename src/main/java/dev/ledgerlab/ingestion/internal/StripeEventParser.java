package dev.ledgerlab.ingestion.internal;

import java.time.DateTimeException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
final class StripeEventParser {
    private final ObjectReader reader;

    StripeEventParser(ObjectMapper mapper) {
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    IncomingEvent parse(byte[] body) {
        // Deliberate intake policy: signed but malformed/incomplete JSON gets 400 and no database writes.
        // It cannot provide the required inbox metadata; retain only valid event envelopes (data dictionary §3).
        try {
            JsonNode root = reader.readTree(body);
            String id = text(root, "id");
            String type = text(root, "type");
            String version = text(root, "api_version");
            JsonNode live = root.path("livemode");
            JsonNode created = root.path("created");
            if (!id.startsWith("evt_")
                    || !live.isBoolean()
                    || !created.isIntegralNumber()
                    || !created.canConvertToLong()) {
                throw invalid();
            }
            return new IncomingEvent(
                    id, type, version, live.booleanValue(), Instant.ofEpochSecond(created.longValue()));
        } catch (JacksonException | DateTimeException e) {
            throw invalid();
        }
    }

    private static String text(JsonNode root, String name) {
        JsonNode field = root.path(name);
        if (!field.isString() || field.asString().isBlank()) {
            throw invalid();
        }
        return field.asString();
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe event envelope");
    }
}
