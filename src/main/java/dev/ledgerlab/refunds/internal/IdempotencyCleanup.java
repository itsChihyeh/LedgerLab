package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Ledger;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
final class IdempotencyCleanup {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final RefundApiProperties properties;
    private final TransactionTemplate transaction;
    private final Ledger ledger;

    IdempotencyCleanup(
            JdbcClient jdbc,
            Clock clock,
            RefundApiProperties properties,
            PlatformTransactionManager manager,
            Ledger ledger) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.transaction = new TransactionTemplate(manager);
        this.ledger = ledger;
    }

    int cleanBatch() {
        return Objects.requireNonNull(transaction.execute(tx -> {
            // Serialize against API INSERT/conflict/read: cleanup must not remove a replay between those statements.
            ledger.lockFunds();
            return jdbc.sql("""
                WITH expired AS (
                    SELECT client_id, idem_key FROM io.idempotency_record
                    WHERE expires_at <= :now ORDER BY expires_at, client_id, idem_key
                    LIMIT :batch FOR UPDATE SKIP LOCKED
                ) DELETE FROM io.idempotency_record r USING expired e
                  WHERE r.client_id = e.client_id AND r.idem_key = e.idem_key
                """)
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                    .param("batch", properties.getIdempotencyCleanupBatchSize())
                    .update();
        }));
    }
}
