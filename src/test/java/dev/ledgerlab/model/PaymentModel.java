package dev.ledgerlab.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure Java oracle derived from money-model §1/3/5 and state-machines §1/5; no production types. */
final class PaymentModel {
    record Input(
            String id,
            String charge,
            String type,
            String amount,
            String capturedAmount,
            String currency,
            boolean captured,
            boolean envelopeLive,
            boolean chargeLive) {
        Input withId(String nextId) {
            return new Input(
                    nextId, charge, type, amount, capturedAmount, currency, captured, envelopeLive, chargeLive);
        }

        Input withAmount(String nextAmount) {
            return new Input(id, charge, type, nextAmount, nextAmount, currency, captured, envelopeLive, chargeLive);
        }
    }

    record Outcome(String status, String result) {}

    record Received(Input input, Instant at) {}

    final Map<String, Received> events = new LinkedHashMap<>();
    final Map<String, Outcome> outcomes = new LinkedHashMap<>();
    final Map<String, Long> payments = new HashMap<>();
    final Map<String, Set<String>> issues = new HashMap<>();
    final Map<String, Long> conflicts = new HashMap<>();
    final SettlementModel settlement = new SettlementModel();

    void receive(Input input, Instant at) {
        Received original = events.putIfAbsent(input.id(), new Received(input, at));
        if (original == null) {
            outcomes.put(input.id(), new Outcome("RECEIVED", null));
        } else if (!original.input().equals(input)) {
            issue("EVENT_CONTENT_CONFLICT", input.id());
            conflicts.merge(input.id(), 1L, Long::sum);
        }
    }

    List<Input> pending() {
        return events.values().stream()
                .filter(event -> outcomes.get(event.input().id()).status().equals("RECEIVED"))
                .sorted(Comparator.comparing(Received::at)
                        .thenComparing(event -> event.input().id()))
                .map(Received::input)
                .toList();
    }

    void drain() {
        for (Input input : pending()) {
            String result = rejection(input);
            if (result != null) {
                issue(result.equals("LIVEMODE") ? "LIVEMODE_EVENT" : "UNSUPPORTED_EVENT", input.id());
                outcomes.put(input.id(), new Outcome("REJECTED", result));
            } else if (!input.type().equals("charge.succeeded")) {
                outcomes.put(input.id(), new Outcome("IGNORED", null));
            } else {
                long amount = Long.parseLong(input.amount());
                Long previous = payments.putIfAbsent(input.charge(), amount);
                if (previous == null) {
                    outcomes.put(input.id(), new Outcome("PROCESSED", "APPLIED"));
                } else if (previous == amount) {
                    outcomes.put(input.id(), new Outcome("PROCESSED", "DUPLICATE_FACT"));
                } else {
                    outcomes.put(input.id(), new Outcome("REJECTED", "BUSINESS_CONFLICT"));
                    issue("EVENT_BUSINESS_CONFLICT", input.id());
                }
            }
        }
    }

    static String rejection(Input input) {
        boolean charge = input.type().equals("charge.succeeded");
        if (input.envelopeLive() || (charge && input.chargeLive())) return "LIVEMODE";
        if (input.type().startsWith("charge.dispute.")) return "UNSUPPORTED_TYPE";
        if (!charge) return null;
        Long amount = integer(input.amount());
        if (amount == null || amount < 50 || amount > 99_999_999) return "INVALID_AMOUNT";
        if (!input.currency().equalsIgnoreCase("cad")) return "UNSUPPORTED_CURRENCY";
        if (!input.captured() || !amount.equals(integer(input.capturedAmount()))) return "UNSUPPORTED_CAPTURE";
        return null;
    }

    private static Long integer(String token) {
        if (!token.matches("-?[0-9]+")) return null;
        try {
            return Long.valueOf(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void issue(String category, String event) {
        issues.computeIfAbsent(category, ignored -> new HashSet<>()).add(event);
    }

    Map<String, Long> issueCounts() {
        Map<String, Long> counts = new HashMap<>();
        issues.forEach((category, subjects) -> counts.put(category, (long) subjects.size()));
        return counts;
    }

    List<Input> validCharges() {
        List<Input> valid = new ArrayList<>();
        events.values().forEach(event -> {
            if (event.input().type().equals("charge.succeeded") && rejection(event.input()) == null) {
                valid.add(event.input());
            }
        });
        return valid;
    }

    long balanceDelta() {
        return payments.values().stream().reduce(0L, Math::addExact);
    }
}
