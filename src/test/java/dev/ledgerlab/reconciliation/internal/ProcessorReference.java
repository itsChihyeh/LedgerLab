package dev.ledgerlab.reconciliation.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Independent pure Java model: no application types, fee helper, SQL or rule implementation. */
final class ProcessorReference {
    enum Operation {
        CLEAN,
        DUPLICATE,
        FEE,
        INCOMPLETE,
        REPLAY
    }

    final Set<Operation> archived = new HashSet<>();
    Operation latest = Operation.REPLAY;
    int rows;
    boolean conflict;

    void apply(Operation operation) {
        if (operation == Operation.REPLAY || !archived.add(operation)) return;
        if (operation == Operation.INCOMPLETE) return;
        rows += operation == Operation.DUPLICATE ? 2 : 1;
        if (operation == Operation.FEE)
            conflict = archived.contains(Operation.CLEAN) || archived.contains(Operation.DUPLICATE);
        else if (archived.contains(Operation.FEE)) conflict = true;
        latest = operation;
    }

    Map<String, Long> findings() {
        Map<String, Long> result = new HashMap<>();
        if (latest == Operation.REPLAY) return result;
        if (latest == Operation.DUPLICATE) result.put("DUPLICATE_STATEMENT_LINE", 1L);
        if (latest == Operation.FEE) {
            result.put("FEE_MISMATCH", 1L);
            result.put("AMOUNT_MISMATCH", 1L);
            result.put("BATCH_TOTAL_MISMATCH", 1L);
        }
        return result;
    }
}
