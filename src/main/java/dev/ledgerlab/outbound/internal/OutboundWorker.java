package dev.ledgerlab.outbound.internal;

import dev.ledgerlab.outbound.CommandExecution;
import dev.ledgerlab.outbound.CommandHandler;
import dev.ledgerlab.outbound.CommandResult;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public OutboundWorker(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            List<CommandHandler> handlers,
            Clock clock,
            OutboundProperties properties,
            RandomGenerator random,
            OutboundHooks hooks,
            ObjectMapper mapper) {
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
                hooks.afterClaim(command);
                var handler = Objects.requireNonNull(handlers.get(command.kind()));
                CommandResult result;
                try {
                    result = handler.execute(command, new Execution(command));
                } catch (LostLease ignoredLease) {
                    continue;
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
            return claims.size();
        }));
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
        if (exhausted) LOG.warn("Outbound command {} reached attempt limit; result remains UNKNOWN", command.id());
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
                throw new TransportFailure();
            }
            archive(attempt, sent, summary, response);
            return response.value();
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

    private static final class LostLease extends RuntimeException {}

    private static final class AttemptLimit extends RuntimeException {}

    private static final class TransportFailure extends RuntimeException {}
}
