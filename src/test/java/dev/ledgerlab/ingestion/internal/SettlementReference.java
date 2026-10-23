package dev.ledgerlab.ingestion.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        FEE_EXCEEDS_GROSS
    }

    boolean enabled(Action action) {
        return switch (action) {
            case REISSUE -> failed;
            case OTHER_BATCH -> settled;
            default -> true;
        };
    }

    void apply(Action action) {
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
        return List.of(receivable, transit, 0L, pending, available, 0L, 0L, 0L);
    }
}
