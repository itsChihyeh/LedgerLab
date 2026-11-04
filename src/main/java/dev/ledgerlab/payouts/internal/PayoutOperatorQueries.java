package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payouts.PayoutException;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class PayoutOperatorQueries {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transaction;

    PayoutOperatorQueries(
            JdbcClient jdbc, Ledger ledger, IdGenerator ids, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.ids = ids;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    Result enqueue(String payoutId) {
        return Objects.requireNonNull(transaction.execute(tx -> {
            ledger.lockFunds();
            String status = jdbc.sql("SELECT status FROM core.payout WHERE id=:id FOR UPDATE")
                    .param("id", payoutId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new PayoutException("PAYOUT_NOT_FOUND"));
            if (!Set.of("UNKNOWN", "ACCEPTED", "SUBMITTED").contains(status))
                throw new PayoutException("PAYOUT_NOT_QUERYABLE");
            var existing =
                    jdbc.sql("""
                    SELECT id FROM io.outbound_command
                    WHERE kind='QUERY_PAYOUT' AND request_id=:id AND status<>'DONE'
                    """).param("id", payoutId).query(String.class).optional();
            if (existing.isPresent()) return new Result(existing.orElseThrow());
            String commandId = ids.next("cmd");
            jdbc.sql("""
                    INSERT INTO io.outbound_command(id,kind,subject_id,request_id,status,next_attempt_at,created_at,recorded_at)
                    VALUES (:id,'QUERY_PAYOUT',:payout,:payout,'PENDING',:now,:now,:now)
                    """)
                    .param("id", commandId)
                    .param("payout", payoutId)
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                    .update();
            return new Result(commandId);
        }));
    }

    record Result(String commandId) {}
}
