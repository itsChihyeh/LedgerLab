package dev.ledgerlab.reconciliation.internal;

import java.util.List;
import java.util.Map;

/** Rules read evidence inside the runner's transaction and must never write data. */
public interface ReconRule {
    RunRequest.Kind kind();

    String version();

    List<Finding> evaluate(Context context);

    /** Explicit checked keys, including absent findings, with the evidence explaining each absence. */
    Map<Finding.Key, Explanation> checkedScope(Context context);

    record Context(RunRequest request, Map<String, String> inputHashes) {
        public Context {
            inputHashes = Map.copyOf(inputHashes);
        }
    }

    record Explanation(String note, String evidenceRefs) {
        public Explanation {
            if (note.isBlank()) throw new IllegalArgumentException("Scope explanation must not be blank");
        }
    }
}
