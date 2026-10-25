package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.RefundResult;
import dev.ledgerlab.refunds.Refunds;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Reuses the existing P3 fixture without making internal refund services public. */
public final class CorrectionFixture implements AutoCloseable {
    private final RefundFixture f = new RefundFixture();
    public final TestDatabase.Handle db = f.db;
    public final JdbcClient jdbc = f.jdbc;
    public final Ledger ledger = f.ledger;
    public final MutableClock clock = f.clock;
    public final IdGenerator ids = f.ids;
    public final ObjectMapper mapper = f.mapper;
    public final PlatformTransactionManager manager = f.manager;
    public final TransactionTemplate tx = f.tx;
    public final Refunds refunds = f.refunds;
    public final BatchSettlement batches = f.batches;
    public final dev.ledgerlab.settlement.BatchRechecks rechecks = f.rechecks;
    public final PaymentCapture capture = f.capture;
    public final IngestionIssues issues = f.issues;

    public String s1() {
        return f.s1();
    }

    public String payment(String ref, long amount) {
        return f.payment(ref, amount);
    }

    public void batch(String id, String ref, long amount, long fee) {
        f.batch(id, ref, amount, fee);
    }

    /** Complete immutable Stripe archive, including the payout query and JSON page strings. */
    public PayoutEvidence archivedBatch(String id, java.util.List<PayoutEvidence.Line> lines) {
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM io.inbox_event WHERE event_id=?)")
                .param("evt_" + id)
                .query(Boolean.class)
                .single()) f.inbox("evt_" + id);
        String page = mapper.writeValueAsString(java.util.Map.of(
                "object",
                "list",
                "has_more",
                false,
                "data",
                lines.stream()
                        .map(line -> java.util.Map.of(
                                "id",
                                line.id(),
                                "type",
                                line.type(),
                                "source",
                                line.source(),
                                "amount",
                                line.gross(),
                                "fee",
                                line.fee(),
                                "net",
                                line.net(),
                                "currency",
                                line.currency(),
                                "created",
                                line.occurredAt().getEpochSecond()))
                        .toList()));
        String body = mapper.writeValueAsString(java.util.List.of(page));
        long fetch = jdbc.sql("""
                INSERT INTO io.processor_fetch(purpose,request,response_body,response_sha256,complete,fetched_at,recorded_at)
                VALUES ('BATCH_LINES',CAST(? AS jsonb),?,encode(sha256(convert_to(?,'UTF8')),'hex'),true,?,?) RETURNING id
                """)
                .params(
                        mapper.writeValueAsString(java.util.Map.of("query", java.util.Map.of("payout", id))),
                        body,
                        body,
                        clock.instant().atOffset(java.time.ZoneOffset.UTC),
                        clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .query(Long.class)
                .single();
        long net = lines.stream().mapToLong(PayoutEvidence.Line::net).reduce(0, Math::addExact);
        return new PayoutEvidence(
                "evt_" + id,
                "inbox_event",
                "evt_" + id,
                "payout.reconciliation_completed",
                id,
                net,
                "cad",
                true,
                "standard",
                "completed",
                clock.instant(),
                clock.instant(),
                new PayoutEvidence.Lines(fetch, lines));
    }

    public PayoutEvidence.Line line(String id, String type, String ref, long gross, long fee) {
        return new PayoutEvidence.Line(
                id, type, ref, gross, fee, Math.subtractExact(gross, fee), "cad", clock.instant());
    }

    public void bankWaiting(String batch, long amount) {
        String csv = "H,BANK_STATEMENT,SIM-CAD-001,2026-10-23,2026-10-23,1,CAD\r\n"
                + "D,bt_wait,CREDIT," + amount + ",CAD,BOOKED," + batch + ",2026-10-23,2026-10-23,fixture\r\n"
                + "T,1," + amount + ",0,0," + amount + "\r\n";
        org.assertj.core.api.Assertions.assertThat(f.banks
                        .importBankStatement("waiting.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .status())
                .isEqualTo("ACCEPTED");
    }

    public PayoutEvidence batchEvidence(String id) {
        return f.batchEvidence(id, "ch_p1", 10000, 320);
    }

    public RefundResult accept(String payment, long amount) {
        return f.accept(payment, amount);
    }

    public long count(String table) {
        return f.count(table);
    }

    public long number(String sql) {
        return f.number(sql);
    }

    public void invariants() {
        f.invariants();
        org.assertj.core.api.Assertions.assertThat(
                        f.jdbc.sql("""
                SELECT balance=(SELECT coalesce(sum(amount),0) FROM core.payment WHERE status='CAPTURED')
                FROM ledger.account WHERE code=2101
                """).query(Boolean.class).single())
                .as("INV-9 captured payment amount")
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(
                        f.jdbc.sql("""
                SELECT balance=(SELECT coalesce(sum(net),0) FROM core.settlement_batch WHERE status='CONFIRMED')
                FROM ledger.account WHERE code=1102
                """).query(Boolean.class).single())
                .as("INV-9 confirmed batch amount")
                .isTrue();
    }

    public void bankEvidence() {
        String csv = "H,BANK_STATEMENT,SIM-CAD-001,2026-10-23,2026-10-23,1,CAD\r\n"
                + "D,bt_evidence,CREDIT,9680,CAD,BOOKED,po_evidence,2026-10-23,2026-10-23,fixture\r\n"
                + "T,1,9680,0,0,9680\r\n";
        org.assertj.core.api.Assertions.assertThat(f.banks
                        .importBankStatement("evidence.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .status())
                .isEqualTo("ACCEPTED");
    }

    public void refundEvidence(String id, ProcessorRefundFact.Status status) {
        var service =
                new JdbcRefundEvidence(f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        f.tx.executeWithoutResult(tx -> service.apply(new ProcessorRefundFact(
                id,
                "re_" + id,
                status,
                status == ProcessorRefundFact.Status.failed ? "declined" : null,
                ProcessorRefundFact.Source.COMMAND_QUERY,
                "query_" + id,
                f.clock.instant())));
    }

    @Override
    public void close() {
        f.close();
    }
}
