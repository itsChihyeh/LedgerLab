package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.RefundEvidence;
import dev.ledgerlab.settlement.BatchSettlement;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Test-only bridge into the real intake and worker, with no database replacement. */
public final class RefundInbox {
    private final JdbcClient jdbc;
    private final PlatformTransactionManager manager;
    private final PaymentCapture capture;
    private final IngestionIssues issues;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PayoutProcessing payouts;
    private final RefundProcessing refunds;
    private final InboxIntake intake;

    public RefundInbox(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            PaymentCapture capture,
            IngestionIssues issues,
            ObjectMapper mapper,
            Clock clock,
            BatchSettlement batches,
            RefundEvidence evidence,
            dev.ledgerlab.refunds.RefundSettlement refundSettlement,
            dev.ledgerlab.settlement.BatchRechecks rechecks) {
        this.jdbc = jdbc;
        this.manager = manager;
        this.capture = capture;
        this.issues = issues;
        this.mapper = mapper;
        this.clock = clock;
        payouts = new PayoutProcessing(
                org.mockito.Mockito.mock(StripeReader.class),
                batches,
                jdbc,
                mapper,
                clock,
                org.mockito.Mockito.mock(dev.ledgerlab.refunds.ProcessorRefundGateway.class),
                refundSettlement,
                rechecks);
        refunds = new RefundProcessing(new RefundParser(mapper), evidence, issues, mapper);
        intake = new InboxIntake(jdbc, new TransactionTemplate(manager), issues, mapper, clock);
    }

    public void receive(ObjectNode event) {
        byte[] body = event.toString().getBytes(StandardCharsets.UTF_8);
        intake.receive(new StripeEventParser(mapper).parse(body), body, clock.instant());
    }

    public InboxWorker worker(WorkerHooks hooks) {
        return new InboxWorker(
                jdbc,
                manager,
                capture,
                issues,
                mapper,
                new ChargeParser(mapper),
                clock,
                new WorkerProperties(
                        "refund-webhook", 1, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(4), 5),
                hooks,
                payouts,
                refunds);
    }

    public void drain() {
        var worker = worker(new WorkerHooks() {});
        for (int i = 0; i < 30; i++) if (worker.processAvailable() == 0) return;
        throw new AssertionError("Inbox did not drain");
    }
}
