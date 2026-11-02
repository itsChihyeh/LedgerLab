package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.payouts.BankPayoutGateway;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.RefundQueue;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Joins the worker writeback transaction; its final epoch fence rolls back all these writes together. */
@Component
final class PayoutCommandEvidence {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final RefundQueue queue;
    private final IngestionIssues issues;
    private final ObjectMapper mapper;
    private final Clock clock;

    PayoutCommandEvidence(
            JdbcClient jdbc,
            Ledger ledger,
            RefundQueue queue,
            IngestionIssues issues,
            ObjectMapper mapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.queue = queue;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
    }

    void apply(CommandSnapshot command, CommandResult result, BankPayoutGateway.@Nullable Outcome outcome) {
        ledger.lockFunds();
        var payout = jdbc.sql("SELECT status, amount, bank_txn_id FROM core.payout WHERE id=:id FOR UPDATE")
                .param("id", command.subjectId())
                .query((rs, row) -> new Payout(rs.getString(1), rs.getLong(2), rs.getString(3)))
                .single();
        if (outcome instanceof BankPayoutGateway.Conflict) {
            conflict(command, payout, outcome);
            return;
        }
        if (!(outcome instanceof BankPayoutGateway.Found found)) {
            if (payout.status().equals("RESERVED") || payout.status().equals("SUBMITTED")) {
                jdbc.sql("""
                        UPDATE core.payout SET status='UNKNOWN', unknown_at=coalesce(unknown_at,:observed), updated_at=:now
                        WHERE id=:id
                        """)
                        .param("id", command.subjectId())
                        .param("observed", result.observedAt().atOffset(ZoneOffset.UTC))
                        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                        .update();
                history(command, payout.status(), "UNKNOWN", result);
            }
            return;
        }
        boolean rejected = found.status() == BankPayoutGateway.Status.REJECTED;
        if (payout.bankTxnId() != null
                || payout.status().equals("PAID")
                || payout.status().equals("RETURNED")) {
            if (rejected) conflict(command, payout, found);
            return;
        }
        if (payout.status().equals("FAILED")) {
            if (!rejected) conflict(command, payout, found);
            return;
        }
        String target = rejected ? "FAILED" : "ACCEPTED";
        if (payout.status().equals(target)) return;
        var observed = result.observedAt().atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                UPDATE core.payout SET status=:status, bank_ack_ref=:ref, bank_execution_date=:execution,
                    failure_reason=:reason,
                    accepted_at=CASE WHEN :status='ACCEPTED' THEN coalesce(accepted_at,:observed) ELSE accepted_at END,
                    failed_at=CASE WHEN :status='FAILED' THEN coalesce(failed_at,:observed) ELSE failed_at END,
                    updated_at=:now WHERE id=:id
                """)
                .param("id", command.subjectId())
                .param("status", target)
                .param("ref", found.paymentId())
                .param("execution", found.executionDate())
                .param("reason", rejected ? Objects.requireNonNull(found.reason()) : null)
                .param("observed", observed)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        history(command, payout.status(), target, result);
        if (rejected) {
            ledger.post(new EntryRequest(
                    EntryType.PAYOUT_FAILED,
                    "payout:" + command.subjectId() + ":failed",
                    result.observedAt(),
                    SubjectType.PAYOUT,
                    command.subjectId(),
                    List.of(
                            PostingLine.debit(Account.PAYOUT_IN_TRANSIT, payout.amount()),
                            PostingLine.credit(Account.MERCHANT_AVAILABLE, payout.amount()))));
            queue.release();
        }
    }

    private void history(CommandSnapshot command, String from, String to, CommandResult result) {
        jdbc.sql("""
                INSERT INTO core.payout_status_history(payout_id, from_status, to_status, evidence_type,
                    evidence_id, occurred_at, recorded_at)
                VALUES (:id,:from,:to,'bank_api',:evidence,:occurred,:now)
                """)
                .param("id", command.subjectId())
                .param("from", from)
                .param("to", to)
                .param("evidence", command.id() + ":lease:" + command.leaseEpoch())
                .param("occurred", result.observedAt().atOffset(ZoneOffset.UTC))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private void conflict(CommandSnapshot command, Payout payout, BankPayoutGateway.Outcome outcome) {
        issues.open(
                "PAYOUT_STATE_CONFLICT",
                "payout",
                command.subjectId(),
                mapper.writeValueAsString(
                        Map.of("status", payout.status(), "bank_txn_id", Objects.toString(payout.bankTxnId(), ""))),
                mapper.writeValueAsString(outcome),
                mapper.writeValueAsString(List.of(Map.of(
                        "source", "bank_api", "command_id", command.id(), "lease_epoch", command.leaseEpoch()))));
    }

    private record Payout(
            String status, long amount, @Nullable String bankTxnId) {}
}
