package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.outbound.CommandExecution;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.payouts.BankPayoutGateway;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

final class PayoutCommandHandler implements CommandHandler {
    private final JdbcClient jdbc;
    private final BankPayoutGateway gateway;
    private final PayoutCommandEvidence evidence;
    private final Clock clock;
    private final boolean queryOnly;

    PayoutCommandHandler(JdbcClient jdbc, BankPayoutGateway gateway, PayoutCommandEvidence evidence, Clock clock) {
        this(jdbc, gateway, evidence, clock, false);
    }

    PayoutCommandHandler(
            JdbcClient jdbc,
            BankPayoutGateway gateway,
            PayoutCommandEvidence evidence,
            Clock clock,
            boolean queryOnly) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.evidence = evidence;
        this.clock = clock;
        this.queryOnly = queryOnly;
    }

    @Override
    public String kind() {
        return queryOnly ? "QUERY_PAYOUT" : "SUBMIT_PAYOUT";
    }

    @Override
    public CommandResult execute(CommandSnapshot command, CommandExecution execution) {
        if (queryOnly) {
            return result(command, execution.call(summary(command, "QUERY"), () -> gateway.query(command.requestId())));
        }
        if (command.attempts() > 0 || command.retry() || command.firstSentAt() != null) {
            var response = execution.call(summary(command, "QUERY"), () -> gateway.query(command.requestId()));
            if (!(response instanceof BankPayoutGateway.NotFound)) return result(command, response);
        }
        long amount = jdbc.sql("SELECT amount FROM core.payout WHERE id=:id")
                .param("id", command.subjectId())
                .query(Long.class)
                .single();
        return result(
                command, execution.call(summary(command, "SUBMIT"), () -> gateway.submit(command.requestId(), amount)));
    }

    private CommandResult result(CommandSnapshot command, BankPayoutGateway.Outcome outcome) {
        if (queryOnly && outcome instanceof BankPayoutGateway.NotFound) {
            return new CommandResult.Done(new Receipt(outcome), null, "PAYOUT_NOT_FOUND", clock.instant());
        }
        if (outcome instanceof BankPayoutGateway.Conflict conflict) {
            return new CommandResult.Done(new Receipt(outcome), null, "REQUEST_CONFLICT", conflict.receivedAt());
        }
        if (outcome instanceof BankPayoutGateway.Found found) {
            if (!command.requestId().equals(found.requestId()))
                return new CommandResult.Unknown("PAYOUT_RESPONSE_MISMATCH", clock.instant());
            // EXECUTED/RETURNED finish the command; only statement evidence moves cash.
            return new CommandResult.Done(
                    new Receipt(found), found.paymentId(), "PAYOUT_" + found.status(), found.receivedAt());
        }
        return new CommandResult.Unknown(
                outcome instanceof BankPayoutGateway.Unknown unknown ? unknown.code() : "SUBMIT_UNKNOWN",
                clock.instant());
    }

    @Override
    public void applyInTransaction(CommandSnapshot command, CommandResult result) {
        evidence.apply(
                command,
                result,
                result instanceof CommandResult.Done done ? ((Receipt) done.receipt()).outcome() : null);
    }

    private static Map<String, Object> summary(CommandSnapshot command, String operation) {
        return Map.of("operation", operation, "request_id", command.requestId(), "payout_id", command.subjectId());
    }

    private record Receipt(BankPayoutGateway.Outcome outcome) implements CommandResult.Receipt {}
}
