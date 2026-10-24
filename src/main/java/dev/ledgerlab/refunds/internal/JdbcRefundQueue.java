package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.refunds.RefundQueue;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class JdbcRefundQueue implements RefundQueue {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final Clock clock;
    private final IdGenerator ids;
    private final RefundHistory history;

    public JdbcRefundQueue(JdbcClient jdbc, Ledger ledger, Clock clock, IdGenerator ids, RefundHistory history) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.ids = ids;
        this.history = history;
    }

    @Override
    public void release() {
        ledger.assertFundsLocked();
        // Funds lock prevents concurrent queue membership changes. Lock same-kind rows by id (ADR-0004),
        // then consider them by queue_seq; those are different orders, deliberately.
        var waiting = jdbc.sql(
                        "SELECT id, amount, queue_seq FROM core.refund WHERE status = 'AWAITING_FUNDS' ORDER BY id FOR UPDATE")
                .query((rs, row) -> new Waiting(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                .list();
        long capacity = ledger.balance(Account.MERCHANT_AVAILABLE);
        for (var refund : waiting) capacity = Math.addExact(capacity, refund.amount());
        long prefix = 0;
        var now = clock.instant();
        for (var refund : waiting.stream()
                .sorted(java.util.Comparator.comparingLong(Waiting::seq))
                .toList()) {
            long next = Math.addExact(prefix, refund.amount());
            if (next > capacity) break;
            prefix = next;
            jdbc.sql("UPDATE core.refund SET status = 'READY' WHERE id = :id")
                    .param("id", refund.id())
                    .update();
            history.append(refund.id(), "AWAITING_FUNDS", "READY", "QUEUE", now);
            jdbc.sql("""
                    INSERT INTO io.outbound_command
                        (id, kind, subject_id, request_id, status, next_attempt_at, created_at, recorded_at)
                    VALUES (:id, 'SUBMIT_REFUND', :refund, :refund, 'PENDING', :now, :now, :now)
                    """)
                    .param("id", ids.next("cmd"))
                    .param("refund", refund.id())
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    private record Waiting(String id, long amount, long seq) {}
}
