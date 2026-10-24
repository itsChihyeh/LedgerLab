package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader.BalanceTransaction;
import dev.ledgerlab.ingestion.StripeReader.Payout;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

final class StripeReadParser {
    private StripeReadParser() {}

    static Payout payout(JsonNode node) {
        return new Payout(
                text(node, "id"),
                integer(node, "amount"),
                text(node, "currency"),
                bool(node, "automatic"),
                text(node, "method"),
                text(node, "status"),
                text(node, "reconciliation_status"),
                time(node, "arrival_date"),
                time(node, "created"));
    }

    static BalanceTransaction balanceTransaction(JsonNode node) {
        return new BalanceTransaction(
                text(node, "id"),
                text(node, "type"),
                source(node),
                integer(node, "amount"),
                integer(node, "fee"),
                integer(node, "net"),
                text(node, "currency"),
                time(node, "created"));
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("INVALID_RESPONSE");
        }
        return value.asString();
    }

    static boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("INVALID_RESPONSE");
        }
        return value.booleanValue();
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("INVALID_RESPONSE");
        }
        return value.longValue();
    }

    private static Instant time(JsonNode node, String field) {
        return Instant.ofEpochSecond(integer(node, field));
    }

    private static @Nullable String source(JsonNode node) {
        // Stripe permits a null source, including for unsupported adjustment rows.
        if (node.path("source").isNull()) {
            return null;
        }
        return text(node, "source");
    }
}
