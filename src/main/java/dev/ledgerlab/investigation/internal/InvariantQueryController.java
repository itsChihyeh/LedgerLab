package dev.ledgerlab.investigation.internal;

import dev.ledgerlab.ledger.LedgerAudit;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("sandbox")
final class InvariantQueryController {
    private final LedgerAudit audit;
    private final Clock clock;

    InvariantQueryController(@Qualifier("investigationDataSource") DataSource dataSource, Clock clock) {
        this.audit = LedgerAudit.readonly(dataSource);
        this.clock = clock;
    }

    @GetMapping("/api/audit/invariants")
    AuditResult check() {
        Instant checkedAt = clock.instant();
        return new AuditResult(checkedAt, audit.check());
    }

    record AuditResult(Instant checkedAt, List<LedgerAudit.InvariantViolation> violations) {}
}
