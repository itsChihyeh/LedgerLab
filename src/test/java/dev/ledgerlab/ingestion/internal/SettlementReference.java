package dev.ledgerlab.ingestion.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent pure Java reference: imports no application types or business functions. */
final class SettlementReference {
    String status = "ABSENT";
    String holdReason = "";
    int history;
    boolean captured;
    boolean settled;
    boolean failed;
    boolean reissued;
    boolean otherBatch;
    boolean changedArchive;
    final List<Action> waiting = new ArrayList<>();
    final Set<String> issues = new HashSet<>();
    final Map<String, Bank> banks = new LinkedHashMap<>();
    final Set<Action> bankFiles = new HashSet<>();
    boolean expired;
    int bankVersions;
    int bankEntries;
    long cash;
    long unapplied;
    boolean banked;

    enum Action {
        CAPTURE,
        ANNOUNCE,
        CREATED_READY,
        READY,
        CHANGED_VERSION,
        PAID,
        FAILED,
        CANCELED,
        REISSUE,
        OTHER_BATCH,
        UNSUPPORTED,
        UNSUPPORTED_LINE,
        FEE_EXCEEDS_GROSS,
        BANK,
        SECOND_BANK,
        PENDING_BANK,
        SHORT_BANK,
        FOREIGN_BANK,
        DEBIT_BANK,
        EXPIRE_BANK
    }

    boolean enabled(Action action) {
        return switch (action) {
            case REISSUE -> failed;
            case OTHER_BATCH -> settled;
            default -> true;
        };
    }

    void apply(Action action) {
        if (action == Action.EXPIRE_BANK) {
            expired = true;
            rematchBanks();
            return;
        }
        if (action.name().contains("BANK")) {
            if (!bankFiles.add(action)) return;
            String id = switch (action) {
                case BANK, PENDING_BANK -> "model_bank";
                default -> action.name().toLowerCase(java.util.Locale.ROOT);
            };
            Bank old = banks.get(id);
            if (old != null && !old.state.equals("IGNORED_PENDING")) {
                issues.add("STATEMENT_VERSION_CONFLICT:" + id);
                return;
            }
            Bank bank = new Bank(
                    id,
                    action == Action.SHORT_BANK ? 9679 : 9680,
                    action == Action.PENDING_BANK ? "IGNORED_PENDING" : "WAITING");
            bankVersions++;
            banks.remove(id);
            banks.put(id, bank);
            if (action == Action.DEBIT_BANK) {
                bank.state = "UNMATCHED_NO_POSTING";
                issues.add("UNMATCHED_BANK_LINE:" + id);
            } else if (action == Action.FOREIGN_BANK) {
                unapply(bank, "UNMATCHED_BANK_LINE");
            } else rematchBanks();
            return;
        }
        if (action == Action.CAPTURE) {
            captured = true;
            if ("UNKNOWN_PAYMENT".equals(holdReason)) match(changedArchive);
            return;
        }
        if (action == Action.REISSUE) {
            history += reissued ? 1 : 3;
            reissued = true;
            return;
        }
        if (action == Action.OTHER_BATCH) {
            history += otherBatch ? 1 : 3;
            otherBatch = true;
            issues.add("DUPLICATE_STATEMENT_LINE:po_other");
            return;
        }
        if (action == Action.PAID || action == Action.FAILED || action == Action.CANCELED) {
            if (status.equals("ABSENT")) waiting.add(action);
            else {
                history++;
                if (action != Action.PAID && status.equals("CONFIRMED")) {
                    status = "PAYOUT_FAILED";
                    failed = true;
                } else if (action != Action.PAID && status.equals("BANKED")) {
                    issues.add("BATCH_STATE_CONFLICT:po_b1");
                }
            }
            return;
        }
        boolean created = action == Action.ANNOUNCE || action == Action.CREATED_READY || action == Action.UNSUPPORTED;
        if (!status.equals("ABSENT") && (created || !status.equals("ANNOUNCED"))) {
            history++;
            if (settled && !created && action != Action.READY) issues.add("BATCH_VERSION_CONFLICT:po_b1");
            return;
        }
        if (status.equals("ABSENT")) {
            status = "ANNOUNCED";
            history++;
        }
        switch (action) {
            case UNSUPPORTED -> hold("UNSUPPORTED_PAYOUT", "UNSUPPORTED_PAYOUT");
            case UNSUPPORTED_LINE -> hold("UNSUPPORTED_LINE", "UNSUPPORTED_EVENT");
            case FEE_EXCEEDS_GROSS -> hold("FEE_EXCEEDS_GROSS", "AMOUNT_MISMATCH");
            case CREATED_READY, READY, CHANGED_VERSION -> {
                changedArchive = action == Action.CHANGED_VERSION;
                match(changedArchive);
            }
            default -> {}
        }
        var pending = List.copyOf(waiting);
        waiting.clear();
        pending.forEach(this::apply);
    }

    private void match(boolean changed) {
        history++;
        if (!captured) hold("UNKNOWN_PAYMENT", "");
        else if (changed) hold("AMOUNT_MISMATCH", "AMOUNT_MISMATCH");
        else {
            history++;
            status = "CONFIRMED";
            holdReason = "";
            settled = true;
            rematchBanks();
        }
    }

    private void rematchBanks() {
        for (Bank bank : banks.values()) {
            if (!bank.state.equals("WAITING")) continue;
            if (status.equals("CONFIRMED") && bank.amount == 9680) {
                bank.state = "MATCHED";
                banked = true;
                status = "BANKED";
                history++;
                bankEntries++;
                cash = Math.addExact(cash, bank.amount);
            } else if (status.equals("CONFIRMED")) unapply(bank, "DEPOSIT_AMOUNT_MISMATCH");
            else if (status.equals("PAYOUT_FAILED")) unapply(bank, "BATCH_STATE_CONFLICT");
            else if (status.equals("BANKED") || expired) unapply(bank, "UNMATCHED_BANK_LINE");
        }
    }

    private void unapply(Bank bank, String category) {
        bank.state = "UNAPPLIED";
        bankEntries++;
        cash = Math.addExact(cash, bank.amount);
        unapplied = Math.addExact(unapplied, bank.amount);
        issues.add(category + ":" + bank.id);
    }

    static final class Bank {
        final String id;
        final long amount;
        String state;

        Bank(String id, long amount, String state) {
            this.id = id;
            this.amount = amount;
            this.state = state;
        }
    }

    private void hold(String reason, String category) {
        history++;
        status = "HELD";
        holdReason = reason;
        if (!category.isEmpty()) issues.add(category + ":po_b1");
    }

    List<Long> balances() {
        long receivable = captured ? 10000L : 0L;
        long transit = 0;
        long pending = receivable;
        long available = 0;
        if (settled) {
            receivable = Math.subtractExact(receivable, 10000L);
            pending = Math.subtractExact(pending, 10000L);
            transit = Math.addExact(transit, 9680L);
            available = Math.addExact(available, 9680L);
        }
        if (failed) {
            receivable = Math.addExact(receivable, 9680L);
            transit = Math.subtractExact(transit, 9680L);
        }
        if (reissued) {
            receivable = Math.subtractExact(receivable, 9680L);
            transit = Math.addExact(transit, 9680L);
        }
        if (banked) transit = Math.subtractExact(transit, 9680);
        return List.of(receivable, transit, cash, pending, available, 0L, 0L, unapplied);
    }
}
