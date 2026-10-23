package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class RuleResults {
    final List<Finding> findings = new ArrayList<>();
    final Map<Finding.Key, ReconRule.Explanation> scope = new LinkedHashMap<>();
    private final ReconJson json;

    RuleResults(ReconJson json) {
        this.json = json;
    }

    void check(
            String category,
            String type,
            String id,
            boolean different,
            Object expected,
            Object actual,
            Object refs,
            @Nullable Instant due,
            String explanation) {
        var key = new Finding.Key(category, type, id);
        scope.put(key, new ReconRule.Explanation(explanation, json.write(refs)));
        if (different)
            findings.add(
                    new Finding(category, type, id, json.write(expected), json.write(actual), json.write(refs), due));
    }
}
