package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.payments.CaptureResult;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.shared.LogContext;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable leases fence all writes, including failures, independently of worker identity. */
@Component
public final class InboxWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InboxWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PaymentCapture capture;
    private final IngestionIssues issues;
    private final ObjectMapper mapper;
    private final ChargeParser parser;
    private final Clock clock;
    private final WorkerProperties properties;
    private final WorkerHooks hooks;
    private final PayoutProcessing payouts;
    private final RefundProcessing refunds;

    InboxWorker(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            PaymentCapture capture,
            IngestionIssues issues,
            ObjectMapper mapper,
            ChargeParser parser,
            Clock clock,
            WorkerProperties properties,
            WorkerHooks hooks,
            PayoutProcessing payouts,
            RefundProcessing refunds) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.capture = capture;
        this.issues = issues;
        this.mapper = mapper;
        this.parser = parser;
        this.clock = clock;
        this.properties = properties;
        this.hooks = hooks;
        this.payouts = payouts;
        this.refunds = refunds;
    }

    public int processAvailable() {
        List<Claim> claims = Objects.requireNonNull(transactions.execute(status -> claim()));
        for (Claim claim : claims) {
            try (LogContext ignored = LogContext.with(LogContext.EVENT_ID, claim.id())) {
                handle(claim);
            }
        }
        return claims.size();
    }

    private void handle(Claim claim) {
        hooks.afterClaim(claim.id());
        try {
            PayoutEvidence prepared = payouts.prepare(claim.id(), claim.type(), claim.live(), claim.payload());
            try (LogContext ignored =
                    LogContext.with(LogContext.BATCH_ID, prepared == null ? null : prepared.batchId())) {
                hooks.afterFetch(claim.id());
                String status = Objects.requireNonNull(transactions.execute(tx -> process(claim, prepared)));
                LOG.atInfo()
                        .addKeyValue("event.action", "inbox.processed")
                        .addKeyValue("eventType", claim.type())
                        .addKeyValue("status", status)
                        .addKeyValue("attempts", claim.attempts())
                        .log("Inbox event processed");
            }
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> recordFailure(claim, failure));
            LOG.atWarn()
                    .addKeyValue("event.action", "inbox.failed")
                    .addKeyValue("attempts", claim.attempts() + 1)
                    .addKeyValue("errorClass", failure.getClass().getName())
                    .log("Inbox event failed");
        }
    }

    private List<Claim> claim() {
        Instant now = clock.instant();
        // NO KEY UPDATE permits the KEY SHARE held by core.payment.source_event_id's FK.
        List<Claim> claims = jdbc.sql("""
                SELECT event_id, event_type, livemode, payload, received_at, lease_epoch, attempts
                FROM io.inbox_event WHERE status = 'RECEIVED' AND next_attempt_at <= :now
                    AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY received_at, event_id LIMIT :batch FOR NO KEY UPDATE SKIP LOCKED
                """)
                .param("now", utc(now))
                .param("batch", properties.batchSize())
                .query((rs, row) -> new Claim(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getBoolean("livemode"),
                        rs.getBytes("payload"),
                        rs.getTimestamp("received_at").toInstant(),
                        rs.getLong("lease_epoch") + 1,
                        rs.getInt("attempts")))
                .list();
        for (Claim claim : claims) {
            jdbc.sql("""
                    UPDATE io.inbox_event SET lease_owner = :owner, lease_until = :until,
                        lease_epoch = lease_epoch + 1 WHERE event_id = :id
                    """)
                    .param("owner", properties.owner())
                    .param("until", utc(now.plus(properties.leaseDuration())))
                    .param("id", claim.id())
                    .update();
        }
        return claims;
    }

    private String process(Claim claim, @Nullable PayoutEvidence prepared) {
        String outcome =
                prepared == null ? route(claim) : payouts.apply(prepared).name();
        boolean parked = "PARKED".equals(outcome);
        String result = parked ? null : outcome;
        String status = parked
                ? "PARKED"
                : result == null
                        ? "IGNORED"
                        : (result.equals("APPLIED") || result.equals("DUPLICATE_FACT") ? "PROCESSED" : "REJECTED");
        hooks.afterBusiness(claim.id());
        OffsetDateTime now = utc(clock.instant());
        int updated = jdbc.sql("""
                UPDATE io.inbox_event SET status = :status, result = :result, processed_at = :now,
                    lease_owner = NULL, lease_until = NULL, parked_on = :dependency
                WHERE event_id = :id AND lease_epoch = :epoch AND status = 'RECEIVED'
                """)
                .param("status", status)
                .param("result", result)
                .param("now", parked ? null : now)
                .param(
                        "dependency",
                        parked ? "batch:" + Objects.requireNonNull(prepared).batchId() : null)
                .param("id", claim.id())
                .param("epoch", claim.epoch())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Inbox lease was superseded");
        }
        InboxStatusHistory.append(jdbc, claim.id(), "RECEIVED", now);
        return status;
    }

    private @Nullable String route(Claim claim) {
        if (claim.type().equals("charge.succeeded")) {
            var evidence = parser.parse(claim.id(), claim.payload(), claim.live(), claim.receivedAt());
            var result = capture.capture(evidence);
            if (result == CaptureResult.APPLIED || result == CaptureResult.DUPLICATE_FACT) {
                payouts.recheckByPayment(evidence.chargeId(), claim.id());
            }
            return result.name();
        }
        if (claim.live()) {
            LOG.atWarn()
                    .addKeyValue("event.action", "webhook.rejected")
                    .addKeyValue("reason", "livemode")
                    .log("Live-mode event rejected");
            openIssue(claim, "LIVEMODE_EVENT", "LIVEMODE");
            return "LIVEMODE";
        }
        if (claim.type().startsWith("charge.dispute.")) {
            openIssue(claim, "UNSUPPORTED_EVENT", "UNSUPPORTED_TYPE");
            return "UNSUPPORTED_TYPE";
        }
        if (claim.type().equals("refund.created")
                || claim.type().equals("refund.updated")
                || claim.type().equals("refund.failed")) {
            return refunds.apply(claim.id(), claim.payload());
        }
        return null;
    }

    private void openIssue(Claim claim, String category, String result) {
        issues.open(
                category,
                "event",
                claim.id(),
                "{}",
                mapper.writeValueAsString(Map.of("result", result)),
                mapper.writeValueAsString(List.of(Map.of("table", "io.inbox_event", "id", claim.id()))));
    }

    private void recordFailure(Claim claim, RuntimeException failure) {
        int attempts = claim.attempts() + 1;
        String error = failure.toString();
        Instant now = clock.instant();
        int updated = jdbc.sql("""
                UPDATE io.inbox_event SET attempts = attempts + 1, last_error = :error,
                    next_attempt_at = :next, status = :status, lease_owner = NULL, lease_until = NULL
                WHERE event_id = :id AND lease_epoch = :epoch AND status = 'RECEIVED'
                """)
                .param("error", error.substring(0, Math.min(error.length(), 2000)))
                .param("next", utc(now.plus(properties.backoff(attempts))))
                .param("status", attempts > properties.maxAttempts() ? "DEAD" : "RECEIVED")
                .param("id", claim.id())
                .param("epoch", claim.epoch())
                .update();
        if (updated == 1 && attempts > properties.maxAttempts()) {
            InboxStatusHistory.append(jdbc, claim.id(), "RECEIVED", utc(now));
        }
    }

    private static OffsetDateTime utc(Instant time) {
        return time.atOffset(ZoneOffset.UTC);
    }

    // Private transaction-local carrier; payload bytes are neither mutated nor compared by record equality.
    @SuppressWarnings("ArrayRecordComponent")
    private record Claim(
            String id, String type, boolean live, byte[] payload, Instant receivedAt, long epoch, int attempts) {}
}
