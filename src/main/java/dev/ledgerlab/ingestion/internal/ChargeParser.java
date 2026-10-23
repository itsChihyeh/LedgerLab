package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.payments.ChargeEvidence;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class ChargeParser {
    private final ObjectMapper mapper;

    ChargeParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ChargeEvidence parse(String eventId, byte[] payload, boolean live, Instant receivedAt) {
        JsonNode charge = mapper.readTree(payload).path("data").path("object");
        return new ChargeEvidence(
                eventId,
                Objects.requireNonNull(text(charge, "id")),
                text(charge, "payment_intent"),
                integer(charge, "amount"),
                integer(charge, "amount_captured"),
                text(charge, "currency"),
                charge.path("captured").isBoolean() ? charge.path("captured").booleanValue() : null,
                text(charge, "status"),
                live || charge.path("livemode").asBoolean(false),
                Instant.ofEpochSecond(Objects.requireNonNull(integer(charge, "created"))),
                receivedAt);
    }

    private static @Nullable Long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
