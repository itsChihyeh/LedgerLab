package dev.ledgerlab.sim.relay;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Ordered, immutable rules. Parsing is strict so a rejected plan never replaces the active one. */
public record RelayPlan(List<Rule> rules) {
    public RelayPlan {
        rules = List.copyOf(rules);
    }

    public enum Action {
        PASS,
        DUPLICATE,
        DELAY,
        HOLD_AND_REORDER,
        DROP
    }

    public record Rule(@Nullable String eventId, @Nullable String eventType, Action action, int count) {
        boolean matches(@Nullable String id, @Nullable String type) {
            return (eventId == null || eventId.equals(id)) && (eventType == null || eventType.equals(type));
        }
    }

    public Rule match(@Nullable String id, @Nullable String type) {
        return rules.stream()
                .filter(rule -> rule.matches(id, type))
                .findFirst()
                .orElse(new Rule(null, null, Action.PASS, 1));
    }

    public static RelayPlan parse(JsonNode input) {
        if (!input.isObject() || !input.path("rules").isArray())
            throw new IllegalArgumentException("rules must be an array");
        List<Rule> rules = new ArrayList<>();
        for (JsonNode node : input.path("rules")) {
            Action action = Action.valueOf(node.path("action").asText());
            int count = switch (action) {
                case DUPLICATE -> integer(node, "copies", 2);
                case DELAY -> integer(node, "delay_seconds", 0);
                case HOLD_AND_REORDER -> integer(node, "after_events", 1);
                default -> 1;
            };
            if (action == Action.DELAY && count > 240)
                throw new IllegalArgumentException("更长的延迟用 fake Stripe 的 redeliver 模拟");
            rules.add(new Rule(text(node, "event_id"), text(node, "event_type"), action, count));
        }
        return new RelayPlan(rules);
    }

    private static int integer(JsonNode node, String field, int minimum) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < minimum)
            throw new IllegalArgumentException("Invalid " + field);
        return value.asInt();
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) return null;
        if (!value.isString() || value.asText().isBlank()) throw new IllegalArgumentException("Invalid " + field);
        return value.asText();
    }
}
