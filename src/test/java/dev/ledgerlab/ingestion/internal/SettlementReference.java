package dev.ledgerlab.ingestion.internal;

/** Independent pure Java reference: imports no application types or business functions. */
final class SettlementReference {
    String status = "ABSENT";
    int history;
    int waitingPaid;

    enum Action {
        ANNOUNCE,
        CREATED_READY,
        READY,
        PAID,
        UNSUPPORTED
    }

    void apply(Action action) {
        if (action == Action.PAID) {
            if (status.equals("ABSENT")) waitingPaid++;
            else history++;
            return;
        }
        if (!status.equals("ABSENT") && (action != Action.READY || !status.equals("ANNOUNCED"))) {
            history++;
            return;
        }
        if (status.equals("ABSENT")) {
            status = "ANNOUNCED";
            history += 1 + waitingPaid;
            waitingPaid = 0;
        }
        if (action == Action.UNSUPPORTED) {
            status = "HELD";
            history++;
        } else if (action == Action.CREATED_READY || action == Action.READY) {
            status = "CONFIRMED";
            history += 2;
        }
    }

    boolean settled() {
        return "CONFIRMED".equals(status);
    }
}
