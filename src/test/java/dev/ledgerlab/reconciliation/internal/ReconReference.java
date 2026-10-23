package dev.ledgerlab.reconciliation.internal;

/** Independent disposition model: plain Java, no business-code calls or fingerprint implementation. */
final class ReconReference {
    enum Operation {
        FIND_205,
        FIND_206,
        ABSENT,
        OUT_OF_SCOPE,
        DRY_RUN,
        ACCEPT,
        RESOLVE
    }

    String state = "NONE";
    long lastAmount;
    long runs;
    long findings;
    long transitions;
    int reopens;

    boolean apply(Operation operation) {
        if (operation == Operation.DRY_RUN) return true;
        if (operation == Operation.ACCEPT || operation == Operation.RESOLVE) {
            if (!state.equals("OPEN")) return false;
            state = operation == Operation.ACCEPT ? "ACCEPTED" : "RESOLVED";
            transitions++;
            return true;
        }
        runs++;
        if (operation == Operation.OUT_OF_SCOPE) return true;
        if (operation == Operation.ABSENT) {
            if (state.equals("OPEN")) {
                state = "EXPLAINED";
                transitions++;
            }
            return true;
        }
        findings++;
        long amount = operation == Operation.FIND_205 ? 205 : 206;
        boolean newlyDetected = state.equals("NONE");
        boolean reappeared = state.equals("EXPLAINED")
                || ((state.equals("ACCEPTED") || state.equals("RESOLVED")) && amount != lastAmount);
        if (newlyDetected || reappeared) {
            state = "OPEN";
            transitions++;
        }
        if (reappeared) reopens++;
        lastAmount = amount;
        return true;
    }
}
