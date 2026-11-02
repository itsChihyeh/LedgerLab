package dev.ledgerlab.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent P5 oracle. Depends only on the JDK and independent reference models. */
final class PayoutModel {
    static final class Payout {
        final String id;
        final long amount;
        String status = "RESERVED";
        String debit;
        String returned;
        final List<String> history = new ArrayList<>(List.of("RESERVED"));

        Payout(String id, long amount) {
            this.id = id;
            this.amount = amount;
        }

        void transition(String next) {
            if (!status.equals(next)) {
                status = next;
                history.add(next);
            }
        }

        boolean reserved() {
            return List.of("RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED").contains(status);
        }
    }

    static final class Command {
        final String id;
        final String payout;
        final String kind;
        boolean sent;
        boolean done;
        boolean responseLost;

        Command(String id, String payout, String kind) {
            this.id = id;
            this.payout = payout;
            this.kind = kind;
        }
    }

    static final class Bank {
        final String id;
        final String payout;
        final long amount;
        final boolean debit;
        String match;

        Bank(String id, String payout, long amount, boolean debit) {
            this.id = id;
            this.payout = payout;
            this.amount = amount;
            this.debit = debit;
        }
    }

    final Map<String, Payout> payouts = new LinkedHashMap<>();
    final Map<String, Command> commands = new LinkedHashMap<>();
    final Map<String, Bank> banks = new LinkedHashMap<>();
    final Map<SettlementModel.Issue, String> issues = new LinkedHashMap<>();
    final CorrectionModel corrections;
    final SettlementModel journal;
    final RefundModel refunds;

    PayoutModel(CorrectionModel corrections) {
        this.corrections = corrections;
        refunds = corrections.refunds;
        journal = new SettlementModel(corrections.payments);
    }

    Map<Integer, Long> balances() {
        var result = new LinkedHashMap<Integer, Long>();
        for (int code : List.of(1101, 1102, 1103, 2101, 2102, 2103, 2104, 2105)) result.put(code, 0L);
        result.put(1101, corrections.payments.balanceDelta());
        result.put(2101, corrections.payments.balanceDelta());
        for (var deltas : List.of(
                corrections.settlement.deltas(), refunds.deltas(), corrections.journal.deltas(), journal.deltas()))
            deltas.forEach((code, amount) -> result.merge(code, amount, Math::addExact));
        return result;
    }

    long freeCash() {
        var b = balances();
        return Math.subtractExact(
                Math.subtractExact(Math.subtractExact(b.get(1103), b.get(2104)), b.get(2105)),
                Math.max(0, Math.negateExact(b.get(1101))));
    }

    String rejection(long amount) {
        if (amount < 1 || amount > 99_999_999) return "INVALID_AMOUNT";
        if (amount > balances().get(2102)) return "MERCHANT_BALANCE";
        return amount > freeCash() ? "FREE_CASH" : null;
    }

    void reserve(String id, long amount) {
        if (rejection(amount) != null) throw new AssertionError("Ineligible oracle reservation");
        payouts.put(id, new Payout(id, amount));
        post("payout:" + id + ":reserved", "PAYOUT_RESERVED", 2102, 2104, amount);
    }

    void receipt(Command c, String outcome) {
        var p = payouts.get(c.payout);
        c.sent = true;
        c.responseLost = c.responseLost || outcome.equals("DROP");
        c.done = !List.of("TIMEOUT", "DROP").contains(outcome);
        if (!c.done) {
            if (p.status.equals("RESERVED") || p.status.equals("SUBMITTED")) p.transition("UNKNOWN");
            return;
        }
        boolean rejected = outcome.equals("REJECTED");
        if (!p.reserved()) {
            if (p.status.equals("FAILED") != rejected) conflict(p);
            return;
        }
        p.transition(rejected ? "FAILED" : "ACCEPTED");
        if (rejected) {
            post("payout:" + p.id + ":failed", "PAYOUT_FAILED", 2104, 2102, p.amount);
            refunds.release();
        }
    }

    void bank(String id, Payout p, boolean debit) {
        if (banks.containsKey(id)) return;
        var bank = new Bank(id, p.id, p.amount, debit);
        banks.put(id, bank);
        if (debit && p.reserved()) {
            bank.match = "PAYOUT_PAID";
            p.debit = id;
            p.transition("PAID");
            post("bank_txn:" + id, "PAYOUT_PAID", 2104, 1103, p.amount);
        } else if (!debit && p.status.equals("PAID")) {
            bank.match = "PAYOUT_RETURNED";
            p.returned = id;
            p.transition("RETURNED");
            post("bank_txn:" + id, "PAYOUT_RETURNED", 1103, 2102, p.amount);
            refunds.release();
        } else {
            bank.match = debit ? "UNMATCHED_NO_POSTING" : "UNAPPLIED";
            if (!debit) post("bank_txn:" + id, "UNAPPLIED_CASH_RECEIVED", 1103, 2105, p.amount);
            if (p.status.equals("FAILED")) conflict(p);
            else issues.put(new SettlementModel.Issue("UNMATCHED_BANK_LINE", "bank_txn", id), "");
        }
    }

    void conflict(Payout p) {
        issues.putIfAbsent(new SettlementModel.Issue("PAYOUT_STATE_CONFLICT", "payout", p.id), "");
    }

    void correct(Payout p, Bank bank, String id, String failedEntry) {
        corrections.corrections.put(id, "CONFIRM_FAILED_PAYOUT_PAID");
        corrections.targets.put("payout:" + p.id + ":failed", new CorrectionModel.Target(id, "REVERSED"));
        corrections.journal.post(
                "reversal:" + failedEntry,
                "REVERSAL",
                new SettlementModel.Posting(2102, "D", p.amount),
                new SettlementModel.Posting(2104, "C", p.amount));
        corrections.journal.post(
                "correction:" + id,
                "CORRECTION",
                new SettlementModel.Posting(2104, "D", p.amount),
                new SettlementModel.Posting(1103, "C", p.amount));
        p.transition("PAID");
        p.debit = bank.id;
        bank.match = "PAYOUT_PAID";
        issues.replaceAll((issue, previous) ->
                issue.subjectId().equals(p.id) || issue.subjectId().equals(bank.id) ? id : previous);
    }

    long cashEvidence() {
        long total = 0;
        for (Bank b : banks.values())
            if (!b.match.equals("UNMATCHED_NO_POSTING")) total = Math.addExact(total, b.debit ? -b.amount : b.amount);
        return total;
    }

    private void post(String key, String type, int debit, int credit, long amount) {
        journal.post(
                key,
                type,
                new SettlementModel.Posting(debit, "D", amount),
                new SettlementModel.Posting(credit, "C", amount));
    }
}
