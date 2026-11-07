package dev.ledgerlab.outbound.internal;

import dev.ledgerlab.outbound.CommandExecution;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public final class OutboundWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OutboundWorker.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate outside;
    private final Map<String, CommandHandler> handlers;
    private final Clock clock;
    private final OutboundProperties properties;
    private final RandomGenerator random;
    private final OutboundHooks hooks;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    /** Without a shared registry: attempt counts go to a private one nobody reads (tests that build a worker by hand). */
    public OutboundWorker(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            List<CommandHandler> handlers,
            Clock clock,
            OutboundProperties properties,
            RandomGenerator random,
            OutboundHooks hooks,
            ObjectMapper mapper) {
        this(jdbc, manager, handlers, clock, properties, random, hooks, mapper, new SimpleMeterRegistry());
    }

    @Autowired
    public OutboundWorker(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            List<CommandHandler> handlers,
            Clock clock,
            OutboundProperties properties,
            RandomGenerator random,
            OutboundHooks hooks,
            ObjectMapper mapper,
            MeterRegistry meters) {
        this.meters = meters;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.outside = new TransactionTemplate(manager);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.handlers =
                handlers.stream().collect(Collectors.toUnmodifiableMap(CommandHandler::kind, Function.identity()));
        this.clock = clock;
        this.properties = properties;
        this.random = random;
        this.hooks = hooks;
        this.mapper = mapper;
    }

    public int processAvailable() {
        return Objects.requireNonNull(outside.execute(ignored -> {
            if (handlers.isEmpty()) return 0;
            List<CommandSnapshot> claims = Objects.requireNonNull(tx.execute(status -> claim()));
            for (var command : claims) {
                try (CommandScope scope = new CommandScope(command)) {
                    handle(command);
                }
            }
            return claims.size();
        }));
    }

    private void handle(CommandSnapshot command) {
        hooks.afterClaim(command);
        var handler = Objects.requireNonNull(handlers.get(command.kind()));
        CommandResult result;
        try {
            result = handler.execute(command, new Execution(command));
        } catch (LostLease ignoredLease) {
            return;
        } catch (AttemptLimit limit) {
            result = new CommandResult.Unknown("ATTEMPT_LIMIT", clock.instant());
        } catch (TransportFailure failure) {
            result = new CommandResult.Unknown("TRANSPORT_ERROR", clock.instant());
        }
        hooks.beforeWriteback(command);
        CommandResult completed = result;
        try {
            tx.executeWithoutResult(status -> writeback(command, handler, completed));
        } catch (LostLease ignoredLease) {
            // Entire business transaction rolled back; the independently stored attempt remains.
        }
    }

    private List<CommandSnapshot> claim() {
        Instant now = clock.instant();
        var commands = jdbc.sql("""
                SELECT * FROM io.outbound_command WHERE kind IN (:kinds) AND
                    ((status IN ('PENDING','UNKNOWN') AND next_attempt_at<=:now)
                     OR (status='IN_FLIGHT' AND lease_until<:now))
                ORDER BY created_at,id LIMIT :batch FOR UPDATE SKIP LOCKED
                """)
                .param("kinds", handlers.keySet())
                .param("now", utc(now))
                .param("batch", properties.batchSize())
                .query((rs, row) -> {
                    var first = rs.getTimestamp("first_sent_at");
                    return new CommandSnapshot(
                            rs.getString("id"),
                            rs.getString("kind"),
                            rs.getString("subject_id"),
                            rs.getString("request_id"),
                            first == null ? null : first.toInstant(),
                            rs.getString("external_ref"),
                            rs.getInt("attempts"),
                            Math.incrementExact(rs.getLong("lease_epoch")),
                            !rs.getString("status").equals("PENDING") || rs.getInt("attempts") > 0);
                })
                .list();
        for (var command : commands) {
            jdbc.sql("""
                    UPDATE io.outbound_command SET status='IN_FLIGHT', lease_owner=:owner,
                        lease_until=:until, lease_epoch=:epoch WHERE id=:id
                    """)
                    .param("id", command.id())
                    .param("epoch", command.leaseEpoch())
                    .param("owner", properties.owner())
                    .param("until", utc(now.plus(properties.leaseDuration())))
                    .update();
        }
        return commands;
    }

    private void writeback(CommandSnapshot command, CommandHandler handler, CommandResult result) {
        // Do business first (funds lock before business/command rows), then fence at commit.
        handler.applyInTransaction(command, result);
        boolean done = result instanceof CommandResult.Done;
        int attempts = jdbc.sql("SELECT attempts FROM io.outbound_command WHERE id=:id")
                .param("id", command.id())
                .query(Integer.class)
                .single();
        boolean exhausted = !done && attempts >= properties.maxAttempts();
        int updated = jdbc.sql("""
                UPDATE io.outbound_command SET status=:status, last_outcome=:outcome,
                    external_ref=coalesce(:ref, external_ref), lease_owner=NULL, lease_until=NULL,
                    next_attempt_at=CASE WHEN :exhausted THEN 'infinity'::timestamptz ELSE :next END
                WHERE id=:id AND lease_epoch=:epoch AND status='IN_FLIGHT'
                """)
                .param("id", command.id())
                .param("epoch", command.leaseEpoch())
                .param("status", done ? "DONE" : "UNKNOWN")
                .param("outcome", result.code())
                .param("ref", result instanceof CommandResult.Done success ? success.externalRef() : null)
                .param("exhausted", exhausted)
                .param(
                        "next",
                        utc(clock.instant()
                                .plus(done ? java.time.Duration.ZERO : properties.backoff(attempts, random))))
                .update();
        if (updated == 0) throw new LostLease();
        if (exhausted)
            LOG.atWarn()
                    .addKeyValue("event.action", "outbound.exhausted")
                    .addKeyValue("attempts", attempts)
                    .log("Outbound command reached its attempt limit; result remains UNKNOWN");
    }

    private final class Execution implements CommandExecution {
        private final CommandSnapshot command;

        private Execution(CommandSnapshot command) {
            this.command = command;
        }

        @Override
        public <T> T call(Map<String, Object> summary, Supplier<Response<T>> request) {
            hooks.beforeHttp(command);
            Instant sent = clock.instant();
            int attempt = Objects.requireNonNull(tx.execute(status -> {
                var number = jdbc.sql("""
                        UPDATE io.outbound_command SET attempts=attempts+1, first_sent_at=coalesce(first_sent_at,:now)
                        WHERE id=:id AND lease_epoch=:epoch AND status='IN_FLIGHT' AND lease_until>=:now
                            AND attempts<:maximum RETURNING attempts
                        """)
                        .param("id", command.id())
                        .param("epoch", command.leaseEpoch())
                        .param("now", utc(sent))
                        .param("maximum", properties.maxAttempts())
                        .query(Integer.class)
                        .optional();
                if (number.isPresent()) return number.get();
                boolean current = jdbc.sql("""
                        SELECT count(*) FROM io.outbound_command WHERE id=:id AND lease_epoch=:epoch
                            AND status='IN_FLIGHT' AND lease_until>=:now
                        """)
                                .param("id", command.id())
                                .param("epoch", command.leaseEpoch())
                                .param("now", utc(sent))
                                .query(Long.class)
                                .single()
                        == 1;
                if (current) throw new AttemptLimit();
                throw new LostLease();
            }));
            Response<T> response;
            try {
                response = request.get();
            } catch (RuntimeException failure) {
                archive(attempt, sent, summary, new Response<>("TRANSPORT_ERROR", null, null, "TRANSPORT_ERROR"));
                count(attempt, "TRANSPORT_ERROR");
                LOG.atInfo()
                        .addKeyValue("event.action", "outbound.attempt")
                        .addKeyValue("attempt", attempt)
                        .addKeyValue("outcome", "TRANSPORT_ERROR")
                        .log("Outbound call finished");
                throw new TransportFailure();
            }
            archive(attempt, sent, summary, response);
            count(attempt, outcome(response));
            LOG.atInfo()
                    .addKeyValue("event.action", "outbound.attempt")
                    .addKeyValue("attempt", attempt)
                    .addKeyValue("outcome", outcome(response))
                    .log("Outbound call finished");
            return response.value();
        }

        // Kind, outcome (see below) and whether this is the second or a later attempt: all closed sets, no ids.
        private void count(int attempt, String outcome) {
            meters.counter(
                            "ledgerlab.outbound.attempts",
                            "kind",
                            command.kind(),
                            "outcome",
                            outcome,
                            "retry",
                            Boolean.toString(attempt > 1))
                    .increment();
        }

        // Status code, or the short error code the gateway gave (TIMEOUT...); never a body or free text.
        private static String outcome(Response<?> response) {
            if (response.status() != null) return "HTTP_" + response.status();
            String error = response.error();
            if (error == null) return "NO_RESPONSE";
            return error.matches("[A-Z_]{1,40}") ? error : "ERROR";
        }

        private void archive(int attempt, Instant sent, Map<String, Object> summary, Response<?> response) {
            Instant finished = clock.instant();
            tx.executeWithoutResult(status -> jdbc.sql("""
                    INSERT INTO io.outbound_attempt(command_id, attempt_no, lease_epoch, sent_at, finished_at,
                        request_summary, response_status, response_body, error, recorded_at)
                    VALUES (:id,:attempt,:epoch,:sent,:finished,CAST(:summary AS jsonb),:status,:body,:error,:now)
                    """)
                    .param("id", command.id())
                    .param("attempt", attempt)
                    .param("epoch", command.leaseEpoch())
                    .param("sent", utc(sent))
                    .param("finished", utc(finished))
                    .param("summary", mapper.writeValueAsString(summary))
                    .param("status", response.status())
                    .param("body", response.body())
                    .param("error", response.error())
                    .param("now", utc(clock.instant()))
                    .update());
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Puts {@code commandId} and {@code commandKind} into the MDC for one command and restores the previous values.
     * This is {@code shared.LogContext} written out by hand: the {@code outbound} module may not depend on any other
     * module (Modulith), so it cannot call it; the command table stays dependency-free on purpose, and these few
     * duplicated lines are the accepted price. The key spellings are the ones in docs/design/observability.md 1.2.
     */
    private static final class CommandScope implements AutoCloseable {
        private final @Nullable String previousId = MDC.get("commandId");
        private final @Nullable String previousKind = MDC.get("commandKind");

        CommandScope(CommandSnapshot command) {
            MDC.put("commandId", command.id());
            MDC.put("commandKind", command.kind());
        }

        @Override
        public void close() {
            restore("commandId", previousId);
            restore("commandKind", previousKind);
        }

        private static void restore(String key, @Nullable String previous) {
            if (previous == null) MDC.remove(key);
            else MDC.put(key, previous);
        }
    }

    private static final class LostLease extends RuntimeException {}

    private static final class AttemptLimit extends RuntimeException {}

    private static final class TransportFailure extends RuntimeException {}
}
