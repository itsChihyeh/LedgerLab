package dev.ledgerlab.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JDK-only P3 oracle. Inputs are identities and facts, never production status or calculations. */
final class RefundModel {
    static final class Refund {
        final String id;
        final String payment;
        final String merchantRef;
        final long amount;
        String status = "AWAITING_FUNDS";
        String processorRef;
        String batch;
        boolean command;
        boolean commandDone;
        boolean sent;
        java.time.Instant firstSent;

        Refund(String id, String payment, String merchantRef, long amount) {
            this.id = id;
            this.payment = payment;
            this.merchantRef = merchantRef;
            this.amount = amount;
        }
    }

    final Map<String, Refund> refunds = new LinkedHashMap<>();
    final Map<String, SettlementModel.Entry> entries = new TreeMap<>();
    final Map<String, PaymentModel.Outcome> outcomes = new TreeMap<>();
    final Map<String, Long> captured;
    final SettlementModel settlement;
    final long availableBefore;

    RefundModel(Map<String, Long> captured, SettlementModel settlement, long availableBefore) {
        this.captured = captured;
        this.settlement = settlement;
        this.availableBefore = availableBefore;
    }

    long remaining(String payment) {
        long reserved = 0;
        for (Refund r : refunds.values()) {
            if (r.payment.equals(payment) && !List.of("FAILED", "CANCELLED").contains(r.status))
                reserved = Math.addExact(reserved, r.amount);
        }
        return Math.subtractExact(captured.get(payment), reserved);
    }

    void accept(String id, String payment, String ref, long amount) {
        if (amount < 1 || amount > remaining(payment)) throw new AssertionError("Invalid oracle acceptance");
        var r = new Refund(id, payment, ref, amount);
        refunds.put(id, r);
        post(r, "ACCEPTED", 2102, 2103);
        release();
    }

    void cancel(Refund r) {
        if (!r.status.equals("AWAITING_FUNDS")) throw new AssertionError("Invalid oracle cancellation");
        r.status = "CANCELLED";
        post(r, "CANCELLED", 2103, 2102);
        release();
    }

    String observe(Refund r, String status, String ref) {
        if (ref != null) r.processorRef = ref;
        boolean terminal = List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(r.status);
        if (terminal)
            return status.equals("pending") || status.equalsIgnoreCase(r.status)
                    ? "DUPLICATE_FACT"
                    : "BUSINESS_CONFLICT";
        if (r.status.equals("AWAITING_FUNDS")) throw new AssertionError("Evidence before release");
        String next = switch (status) {
            case "pending" -> "SUBMITTED";
            case "timeout" -> "UNKNOWN";
            case "succeeded" -> "SUCCEEDED";
            case "failed" -> "FAILED";
            default -> throw new IllegalArgumentException(status);
        };
        if (r.status.equals(next)) return "DUPLICATE_FACT";
        r.status = next;
        if (next.equals("SUCCEEDED")) post(r, "EXECUTED", 2103, 1101);
        if (next.equals("FAILED")) {
            post(r, "FAILED", 2103, 2102);
            release();
        }
        return "APPLIED";
    }

    void release() {
        long capacity = Math.addExact(availableBefore, settlement.deltas().getOrDefault(2102, 0L));
        capacity = Math.addExact(capacity, deltas().getOrDefault(2102, 0L));
        for (Refund r : refunds.values())
            if (r.status.equals("AWAITING_FUNDS")) capacity = Math.addExact(capacity, r.amount);
        for (Refund r : refunds.values()) {
            if (!r.status.equals("AWAITING_FUNDS")) continue;
            if (r.amount > capacity) break;
            capacity = Math.subtractExact(capacity, r.amount);
            r.status = "READY";
            r.command = true;
        }
    }

    private void post(Refund r, String action, int debit, int credit) {
        String key = "refund:" + r.id + ":" + action.toLowerCase(java.util.Locale.ROOT);
        var entry = new SettlementModel.Entry(
                "REFUND_" + action,
                List.of(
                        new SettlementModel.Posting(debit, "D", r.amount),
                        new SettlementModel.Posting(credit, "C", r.amount)));
        if (entries.putIfAbsent(key, entry) != null) throw new AssertionError("Duplicate oracle posting " + key);
    }

    Map<Integer, Long> deltas() {
        Map<Integer, Long> result = new TreeMap<>();
        for (var e : entries.values())
            for (var p : e.postings()) {
                boolean positive =
                        p.account() < 2000 ? p.side().equals("D") : p.side().equals("C");
                result.merge(p.account(), positive ? p.amount() : Math.negateExact(p.amount()), Math::addExact);
            }
        return result;
    }
}
