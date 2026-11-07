package dev.ledgerlab.ledger.internal;

import dev.ledgerlab.ledger.LedgerAudit.InvariantViolation;
import dev.ledgerlab.shared.InvariantCheck;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** INV-1 to INV-5 and INV-8: the books recomputed from postings, in the caller's snapshot. */
@Component
public final class LedgerInvariantCheck implements InvariantCheck {

    @Override
    public List<String> ids() {
        return List.of("INV-1", "INV-2", "INV-3", "INV-4", "INV-5", "INV-8");
    }

    @Override
    public Map<String, List<String>> check(JdbcClient snapshot) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (InvariantViolation violation : JdbcLedgerAudit.checkInCallerSnapshot(snapshot)) {
            // One query covers the entry header: it is INV-1 (balanced, positive) and INV-4 (sealed counts).
            for (String id : violation.invariant().split("/")) {
                found.computeIfAbsent(id, key -> new ArrayList<>()).add(violation.detail());
            }
        }
        return found;
    }
}
