package dev.ledgerlab.investigation.internal;

import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.shared.InvariantCheck;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the {@link InvariantCheck}s of every module in one {@code REPEATABLE READ} read-only transaction on the
 * readonly role (ADR-0014), so a write that commits during the check cannot produce a false alarm.
 */
@RestController
final class InvariantQueryController {
    private static final List<String> ALL = List.of(
            "INV-1", "INV-2", "INV-3", "INV-4", "INV-5", "INV-6", "INV-7", "INV-8", "INV-9", "INV-10", "INV-11",
            "INV-12");

    private final JdbcClient jdbc;
    private final TransactionTemplate snapshot;
    private final List<InvariantCheck> checks;
    private final Clock clock;

    InvariantQueryController(
            @Qualifier("investigationDataSource") DataSource dataSource, Clock clock, List<InvariantCheck> checks) {
        this.jdbc = JdbcClient.create(dataSource);
        this.snapshot = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.checks = List.copyOf(checks);
        this.clock = clock;
    }

    @GetMapping("/api/audit/invariants")
    AuditResult check() {
        Instant checkedAt = clock.instant();
        Map<String, List<String>> found = Objects.requireNonNull(snapshot.execute(status -> run()));
        List<InvariantResult> results = found.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> number(entry.getKey())))
                .map(entry -> new InvariantResult(
                        entry.getKey(),
                        entry.getValue().isEmpty(),
                        entry.getValue().stream()
                                .map(detail -> new LedgerAudit.InvariantViolation(entry.getKey(), detail))
                                .toList()))
                .toList();
        List<LedgerAudit.InvariantViolation> flat =
                results.stream().flatMap(result -> result.violations().stream()).toList();
        return new AuditResult(checkedAt, results, flat);
    }

    private Map<String, List<String>> run() {
        Map<String, List<String>> found = new TreeMap<>();
        ALL.forEach(id -> found.put(id, new ArrayList<>()));
        for (InvariantCheck check : checks) {
            Map<String, List<String>> part = check.check(jdbc);
            check.ids().forEach(id -> found.computeIfAbsent(id, key -> new ArrayList<>()));
            part.forEach((id, details) ->
                    found.computeIfAbsent(id, key -> new ArrayList<>()).addAll(details));
        }
        return found;
    }

    private static int number(String id) {
        return Integer.parseInt(id.substring("INV-".length()));
    }

    record InvariantResult(String id, boolean ok, List<LedgerAudit.InvariantViolation> violations) {}

    /** {@code violations} is every violation of every check, flat, as the sandbox scripts have always read it. */
    record AuditResult(
            Instant checkedAt, List<InvariantResult> checks, List<LedgerAudit.InvariantViolation> violations) {}
}
