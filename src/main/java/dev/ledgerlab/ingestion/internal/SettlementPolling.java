package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.ingestion.StripeReader.FetchPurpose;
import dev.ledgerlab.settlement.BatchSettlement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Fallback readiness reads. Durable fetch history supplies backoff across restarts. */
@Component
final class SettlementPolling {
    private static final Logger LOG = LoggerFactory.getLogger(SettlementPolling.class);
    private final StripeReader reader;
    private final BatchSettlement batches;
    private final PayoutProcessing processing;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Properties properties;
    private final TransactionTemplate transactions;

    SettlementPolling(
            StripeReader reader,
            BatchSettlement batches,
            PayoutProcessing processing,
            JdbcClient jdbc,
            Clock clock,
            Properties properties,
            PlatformTransactionManager manager) {
        this.reader = reader;
        this.batches = batches;
        this.processing = processing;
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    int poll() {
        int attempted = 0;
        for (var batch : batches.announcedBefore(clock.instant().minus(properties.after()))) {
            var history = jdbc.sql("""
                    SELECT count(*) AS attempts, max(fetched_at) AS latest FROM io.processor_fetch
                    WHERE event_id = :event AND purpose = 'PAYOUT'
                    """)
                    .param("event", batch.sourceEventId())
                    .query((rs, row) -> {
                        var latest = rs.getTimestamp("latest");
                        return new Retry(rs.getLong("attempts"), latest == null ? Instant.MIN : latest.toInstant());
                    })
                    .single();
            if (history.attempts() > 0
                    && clock.instant().isBefore(history.latest().plus(properties.backoff(history.attempts()))))
                continue;
            attempted++;
            try {
                var fetched = reader.retrievePayout(batch.batchId(), FetchPurpose.PAYOUT, batch.sourceEventId());
                if (!fetched.complete()) continue;
                var payout = fetched.items().getFirst();
                if (!payout.id().equals(batch.batchId())) throw new IllegalStateException("Payout identity mismatch");
                if (payout.reconciliationStatus().equals("in_progress")) continue;
                var evidence = processing.prepare(
                        batch.sourceEventId(),
                        "payout.reconciliation_completed",
                        "processor_fetch",
                        Long.toString(fetched.fetchId()),
                        payout);
                transactions.executeWithoutResult(status -> processing.apply(evidence));
            } catch (RuntimeException failure) {
                // The reader already archived evidence; avoid logging upstream data or credentials.
                LOG.warn(
                        "Settlement polling failed for batch {}; retry follows archived fetch backoff",
                        batch.batchId());
            }
        }
        return attempted;
    }

    private record Retry(long attempts, Instant latest) {}

    @ConfigurationProperties("ledgerlab.settlement")
    record Properties(Duration after, Duration initialBackoff, Duration maxBackoff) {
        Properties {
            if (after == null
                    || after.isNegative()
                    || after.isZero()
                    || initialBackoff == null
                    || initialBackoff.isNegative()
                    || initialBackoff.isZero()
                    || maxBackoff == null
                    || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("Invalid settlement polling configuration");
            }
        }

        Duration backoff(long attempts) {
            Duration result = initialBackoff;
            for (long i = 1; i < attempts && result.compareTo(maxBackoff) < 0; i++) {
                result = result.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : result.multipliedBy(2);
            }
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Properties.class)
    static class Config {
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnProperty(
                name = "ledgerlab.settlement.scheduling-enabled",
                havingValue = "true",
                matchIfMissing = true)
        @EnableScheduling
        static class ScheduledPolling {
            private final SettlementPolling polling;

            ScheduledPolling(SettlementPolling polling) {
                this.polling = polling;
            }

            @Scheduled(fixedDelayString = "${ledgerlab.settlement.poll-interval:1m}")
            void poll() {
                polling.poll();
            }
        }
    }
}
