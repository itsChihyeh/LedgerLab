package dev.ledgerlab.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** ADR-0011 oracle: JDK only, no application calculations, services or database observations. */
final class CorrectionModel {
    record Target(String correction, String role) {}

    final PaymentModel payments;
    final RefundModel refunds;
    final SettlementModel settlement;
    final SettlementModel journal;
    final Map<String, String> corrections = new LinkedHashMap<>();
    final Map<String, Target> targets = new LinkedHashMap<>();
    final Map<String, String> current = new LinkedHashMap<>();

    CorrectionModel(PaymentModel payments, RefundModel refunds) {
        this.payments = payments;
        this.refunds = refunds;
        settlement = payments.settlement;
        journal = new SettlementModel(payments);
    }

    String current(String payment) {
        return current.getOrDefault(payment, "payment:" + payment + ":captured");
    }

    // Both capture actions require an active, unsettled amount entry. VOID additionally requires
    // zero reserved refunds; RESTATE requires a different in-range amount >= reserved refunds.
    String captureError(String payment, String target, Long amount) {
        if (targets.containsKey(target)) return "ALREADY_REVERSED";
        if (settlement.paymentBatches.containsKey(payment)) return "USE_REFUND_FOR_SETTLED_PAYMENT";
        long reserved = Math.subtractExact(payments.payments.get(payment), refunds.remaining(payment));
        if (amount == null) return reserved > 0 ? "PAYMENT_HAS_REFUNDS" : null;
        if (amount < 50 || amount > 99_999_999) return "INVALID_REQUEST";
        if (amount.equals(payments.payments.get(payment))) return "AMOUNT_UNCHANGED";
        return amount < reserved ? "AMOUNT_BELOW_REFUNDED" : null;
    }

    void capture(String payment, String target, String targetId, Long amount, String id, Instant now) {
        if (captureError(payment, target, amount) != null) throw new AssertionError("Invalid correction");
        long old = payments.payments.get(payment);
        corrections.put(id, amount == null ? "VOID_CAPTURE" : "RESTATE_CAPTURE_AMOUNT");
        targets.put(target, new Target(id, "REVERSED"));
        journal.post(
                "reversal:" + targetId,
                "REVERSAL",
                new SettlementModel.Posting(2101, "D", old),
                new SettlementModel.Posting(1101, "C", old));
        if (amount == null) payments.voided.add(payment);
        else {
            String key = "correction:" + id;
            journal.post(
                    key,
                    "CORRECTION",
                    new SettlementModel.Posting(1101, "D", amount),
                    new SettlementModel.Posting(2101, "C", amount));
            payments.payments.put(payment, amount);
            current.put(payment, key);
            settlement.batches.values().stream()
                    .filter(b -> b.status.equals("HELD") && "AMOUNT_MISMATCH".equals(b.hold))
                    .filter(b -> b.lines.stream().anyMatch(l -> l.reference().equals(payment)))
                    .sorted(java.util.Comparator.comparing(b -> b.id))
                    .forEach(b -> settlement.recheck(b, now, id));
            refunds.release();
        }
    }

    // APPLY requires CONFIRMED and exact, same-reference UNAPPLIED credits (one or split).
    // RESTORE requires PAYOUT_FAILED, one exact UNAPPLIED credit and no returned failure line.
    String bankError(SettlementModel.Batch batch, List<SettlementModel.Bank> banks, boolean restore) {
        if (batch.status.equals("ABSENT")) return "BATCH_NOT_FOUND";
        if (!batch.status.equals(restore ? "PAYOUT_FAILED" : "CONFIRMED"))
            return restore ? "BATCH_NOT_PAYOUT_FAILED" : "BATCH_NOT_AWAITING_DEPOSIT";
        for (var bank : banks) {
            if (!Objects.equals(bank.match, "UNAPPLIED")) return "BANK_LINE_NOT_UNAPPLIED";
            if (!bank.input.reference().equals(batch.id)) return "BANK_LINE_REFERENCE_MISMATCH";
        }
        if (SettlementModel.sum(banks.stream().map(b -> b.input.amount()).toList()) != batch.net)
            return "APPLICATION_AMOUNT_MISMATCH";
        if (restore
                && settlement.batches.values().stream()
                        .filter(b ->
                                List.of("CONFIRMED", "BANKED", "PAYOUT_FAILED").contains(b.status))
                        .anyMatch(b -> b.lines.stream()
                                .anyMatch(l -> l.type().equals("payout_failure")
                                        && l.reference().equals(batch.id)))) return "FAILED_PAYOUT_ALREADY_RETURNED";
        return null;
    }

    void bank(
            SettlementModel.Batch batch,
            List<SettlementModel.Bank> banks,
            boolean restore,
            String failureEntryId,
            String id) {
        if (bankError(batch, banks, restore) != null) throw new AssertionError("Invalid bank correction");
        corrections.put(id, restore ? "RESTORE_FAILED_PAYOUT" : "APPLY_UNAPPLIED_CASH");
        if (restore) {
            targets.put("batch:" + batch.id + ":payout_failed", new Target(id, "REVERSED"));
            journal.post(
                    "reversal:" + failureEntryId,
                    "REVERSAL",
                    new SettlementModel.Posting(1102, "D", batch.net),
                    new SettlementModel.Posting(1101, "C", batch.net));
        }
        journal.post(
                "correction:" + id,
                "CORRECTION",
                new SettlementModel.Posting(2105, "D", batch.net),
                new SettlementModel.Posting(1102, "C", batch.net));
        for (var bank : banks) {
            targets.put("bank_txn:" + bank.input.id(), new Target(id, "APPLIED"));
            bank.match = "APPLIED";
            bank.matchedBatch = batch.id;
            settlement.resolve(bank.input.id(), id);
        }
        batch.status = "BANKED";
        settlement.resolve(batch.id, id);
    }
}
