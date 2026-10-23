package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.IngestionIssues;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Test-only bridge to package-private wiring; keeps the production worker API unchanged. */
public final class ModelWorkers {
    private ModelWorkers() {}

    public static InboxWorker create(ApplicationContext context, String owner, WorkerHooks hooks) {
        return new InboxWorker(
                context.getBean(JdbcClient.class),
                context.getBean(PlatformTransactionManager.class),
                context.getBean(PaymentCapture.class),
                context.getBean(IngestionIssues.class),
                context.getBean(ObjectMapper.class),
                context.getBean(ChargeParser.class),
                context.getBean(Clock.class),
                new WorkerProperties(owner, 1, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(5), 5),
                hooks);
    }
}
