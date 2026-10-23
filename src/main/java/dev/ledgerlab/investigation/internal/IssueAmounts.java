package dev.ledgerlab.investigation.internal;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Compare monetary fields only; counts, dates and booleans are not amounts. */
final class IssueAmounts {
    private IssueAmounts() {}

    record Comparison(
            String metric, @Nullable String statementLineId, long expectedAmount, long actualAmount, long difference) {}

    static List<Comparison> compare(JsonNode expected, JsonNode actual) {
        List<Comparison> result = new ArrayList<>();
        compare(expected, actual, result);
        return List.copyOf(result);
    }

    private static void compare(JsonNode expected, JsonNode actual, List<Comparison> result) {
        if (actual.isArray()) {
            actual.forEach(line -> compare(expected, line, result));
            return;
        }
        for (String field : List.of(
                "amount",
                "gross",
                "fee",
                "net",
                "unapplied",
                "closing_balance",
                "payout_amount",
                "line_net",
                "max_fee")) {
            JsonNode left = expected.path(field.equals("line_net") ? "payout_amount" : field);
            String actualField = field.equals("closing_balance") ? "cash" : field;
            if (field.equals("max_fee")) actualField = "fee";
            if (field.equals("net") && !actual.has("net") && actual.has("amount")) actualField = "amount";
            JsonNode right = actual.path(actualField);
            if (left.isIntegralNumber() && right.isIntegralNumber()) {
                if (!left.canConvertToLong() || !right.canConvertToLong())
                    throw new ArithmeticException("Evidence amount exceeds long range");
                long a = left.longValue();
                long b = right.longValue();
                long difference = Math.subtractExact(a, b);
                if (difference <= -9007199254740992L || difference >= 9007199254740992L)
                    throw new ArithmeticException("Difference exceeds JSON safe integer range");
                result.add(
                        new Comparison(field, actual.path("statement_line_id").asString(null), a, b, difference));
            }
        }
        if (actual.path("line").isObject()) compare(expected, actual.path("line"), result);
        if (actual.path("fees").isArray()) actual.path("fees").forEach(line -> compare(expected, line, result));
        if (actual.path("lines").isArray()) actual.path("lines").forEach(line -> compare(expected, line, result));
    }
}
