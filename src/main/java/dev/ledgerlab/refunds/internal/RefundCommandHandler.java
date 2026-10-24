package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.outbound.CommandExecution;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class RefundCommandHandler implements CommandHandler {
    private final JdbcClient jdbc;
    private final PaymentRefunds payments;
    private final ProcessorRefundGateway gateway;
    private final JdbcRefundEvidence evidence;
    private final Clock clock;
    private final OutboundProperties properties;

    public RefundCommandHandler(
            JdbcClient jdbc,
            PaymentRefunds payments,
            ProcessorRefundGateway gateway,
            JdbcRefundEvidence evidence,
            Clock clock,
            OutboundProperties properties) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.gateway = gateway;
        this.evidence = evidence;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public String kind() {
        return "SUBMIT_REFUND";
    }

    @Override
    public CommandResult execute(CommandSnapshot command, CommandExecution execution) {
        var refund = jdbc.sql("SELECT payment_id, amount, processor_refund_ref FROM core.refund WHERE id=:id")
                .param("id", command.subjectId())
                .query((rs, row) -> new Refund(rs.getString(1), rs.getLong(2), rs.getString(3)))
                .single();
        String charge = payments.processorRef(refund.paymentId());
        String resend = "FIRST_SEND";
        if (command.firstSentAt() != null || command.retry()) {
            String ref = refund.ref() == null ? command.externalRef() : refund.ref();
            var query = execution.call(
                    summary(command, ref == null ? "LIST_ALL" : "RETRIEVE", "QUERY_BEFORE_RESEND"),
                    () -> ref == null ? gateway.findByRefundId(charge, command.subjectId()) : gateway.retrieve(ref));
            if (query instanceof ProcessorRefundGateway.Found found) {
                if (!matches(found, command, charge, refund.amount()))
                    return new CommandResult.Unknown("REFUND_RESPONSE_MISMATCH", clock.instant());
                return done(command, found, ProcessorRefundFact.Source.COMMAND_QUERY);
            }
            if (!(query instanceof ProcessorRefundGateway.NotFound)) {
                return new CommandResult.Unknown("QUERY_INCOMPLETE", clock.instant());
            }
            resend = command.firstSentAt() != null
                            && Duration.between(command.firstSentAt(), clock.instant())
                                            .compareTo(properties.refundResendAfter())
                                    > 0
                    ? "COMPLETE_NOT_FOUND_AFTER_THRESHOLD"
                    : "COMPLETE_NOT_FOUND_WITHIN_THRESHOLD";
        }
        var response = execution.call(
                summary(command, "CREATE", resend),
                () -> gateway.create(command.requestId(), charge, refund.amount(), command.subjectId()));
        if (response instanceof ProcessorRefundGateway.Found found) {
            if (!matches(found, command, charge, refund.amount()))
                return new CommandResult.Unknown("REFUND_RESPONSE_MISMATCH", clock.instant());
            return done(command, found, ProcessorRefundFact.Source.COMMAND_RESPONSE);
        }
        if (response instanceof ProcessorRefundGateway.Rejected rejected) {
            var fact = new ProcessorRefundFact(
                    command.subjectId(),
                    null,
                    ProcessorRefundFact.Status.failed,
                    rejected.errorCode(),
                    ProcessorRefundFact.Source.COMMAND_RESPONSE,
                    evidenceId(command),
                    rejected.receivedAt() == null ? clock.instant() : rejected.receivedAt());
            return new CommandResult.Done(new Receipt(fact), null, "PROCESSOR_REJECTED", fact.occurredAt());
        }
        return new CommandResult.Unknown("CREATE_UNKNOWN", clock.instant());
    }

    @Override
    public void applyInTransaction(CommandSnapshot command, CommandResult result) {
        if (result instanceof CommandResult.Done done) {
            evidence.apply(((Receipt) done.receipt()).fact());
        } else {
            evidence.unknown(command.subjectId(), evidenceId(command), result.observedAt());
        }
    }

    private CommandResult done(
            CommandSnapshot command, ProcessorRefundGateway.Found found, ProcessorRefundFact.Source source) {
        var fact = new ProcessorRefundFact(
                command.subjectId(),
                found.processorRefundRef(),
                found.status(),
                found.failureReason(),
                source,
                evidenceId(command),
                found.details() == null ? clock.instant() : found.details().receivedAt());
        return new CommandResult.Done(
                new Receipt(fact),
                found.processorRefundRef(),
                "REFUND_" + found.status().name().toUpperCase(java.util.Locale.ROOT),
                fact.occurredAt());
    }

    private static Map<String, Object> summary(CommandSnapshot command, String operation, String decision) {
        return Map.of(
                "operation",
                operation,
                "decision",
                decision,
                "request_id",
                command.requestId(),
                "refund_id",
                command.subjectId());
    }

    private static boolean matches(
            ProcessorRefundGateway.Found found, CommandSnapshot command, String charge, long amount) {
        var details = found.details();
        return details == null
                || (details.amount() == amount
                        && details.chargeRef().equals(charge)
                        && command.subjectId().equals(details.refundId()));
    }

    private static String evidenceId(CommandSnapshot command) {
        return command.id() + ":lease:" + command.leaseEpoch();
    }

    private record Receipt(ProcessorRefundFact fact) implements CommandResult.Receipt {}

    private record Refund(
            String paymentId, long amount, @Nullable String ref) {}
}
