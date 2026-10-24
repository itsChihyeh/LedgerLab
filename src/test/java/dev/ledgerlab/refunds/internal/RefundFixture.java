package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.ledger.internal.JdbcLedgerAudit;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.payments.PaymentSettlement;
import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.refunds.RefundQueue;
import dev.ledgerlab.refunds.RefundResult;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.RefundAssertions;
import dev.ledgerlab.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** P2 capture/batch/bank services, connected as app to a real migrated PostgreSQL. */
final class RefundFixture implements AutoCloseable {
    final TestDatabase.Handle db = TestDatabase.freshMigrated();
    final DataSource source = db.app();
    final JdbcClient jdbc = JdbcClient.create(source);
    final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
    final TransactionTemplate tx = new TransactionTemplate(manager);
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T14:00:00Z"));
    final IdGenerator ids = IdGenerator.seeded(clock, 304);
    final JsonMapper mapper = new JsonMapper();
    final Ledger ledger = new JdbcLedger(source, clock, ids);
    final JdbcLedgerAudit audit = new JdbcLedgerAudit(source, manager);
    final PaymentRefunds payments = new PaymentRefunds(jdbc, ledger);
    final RefundHistory history = new RefundHistory(jdbc);
    final RefundQueue queue = new JdbcRefundQueue(jdbc, ledger, clock, ids, history);
    final JdbcRefunds refunds = service(ledger, new LockedRefundLimitGuard(ledger, payments), () -> {});
    final JdbcIngestionIssues issues = new JdbcIngestionIssues(jdbc, ids, clock);
    final PaymentCapture capture = new PaymentCapture(ledger, jdbc, issues, ids, clock, mapper);
    final BankStatements banks =
            new BankStatements(jdbc, ledger, issues, clock, ids, new BusinessCalendar(), mapper, manager, 2);
    final BatchSettlement batches =
            new BatchSettlement(jdbc, ledger, new PaymentSettlement(jdbc), issues, clock, mapper, banks, queue);
    int next;

    JdbcRefunds service(Ledger target, RefundLimitGuard guard, Runnable inserted) {
        return new JdbcRefunds(jdbc, target, payments, guard, queue, history, clock, ids, mapper, manager, inserted);
    }

    String s1() {
        return payment("ch_p1", 10000);
    }

    String s3() {
        String id = s1();
        batch("po_b1", "ch_p1", 10000, 320);
        byte[] csv = ("H,BANK_STATEMENT,SIM-CAD-001,2026-10-23,2026-10-23,1,CAD\r\n"
                        + "D,bt_b1,CREDIT,9680,CAD,BOOKED,po_b1,2026-10-23,2026-10-23,fixture\r\n"
                        + "T,1,9680,0,0,9680\r\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(banks.importBankStatement("s3.csv", csv).status()).isEqualTo("ACCEPTED");
        return id;
    }

    String payment(String ref, long amount) {
        String event = "evt_" + ref;
        inbox(event);
        tx.executeWithoutResult(ignored -> capture.capture(new ChargeEvidence(
                event, ref, null, amount, amount, "cad", true, "succeeded", false, clock.instant(), clock.instant())));
        return jdbc.sql("SELECT id FROM core.payment WHERE processor_payment_ref = :ref")
                .param("ref", ref)
                .query(String.class)
                .single();
    }

    void inbox(String id) {
        jdbc.sql("""
                INSERT INTO io.inbox_event (event_id, event_type, api_version, livemode, payload, payload_sha256,
                    signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
                VALUES (:id, 'fixture', '2026-09-30.endive', false, '{}'::bytea, encode(sha256('{}'::bytea),'hex'),
                    :now, :now, :now, :now, 'RECEIVED', :now)
                """)
                .param("id", id)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO io.inbox_status_history (event_id, from_status, to_status, result, parked_on, lease_epoch, recorded_at)
                SELECT event_id, NULL, status, result, parked_on, lease_epoch, recorded_at
                FROM io.inbox_event WHERE event_id=:id
                """).param("id", id).update();
    }

    void batch(String batch, String ref, long gross, long fee) {
        var evidence = batchEvidence(batch, ref, gross, fee);
        tx.executeWithoutResult(
                ignored -> assertThat(batches.process(evidence)).isEqualTo(BatchSettlement.Result.APPLIED));
    }

    PayoutEvidence batchEvidence(String batch, String ref, long gross, long fee) {
        String event = "evt_" + batch;
        inbox(event);
        String body = "[{\"object\":\"list\",\"has_more\":false,\"data\":[{\"id\":\"txn_" + batch
                + "\",\"type\":\"charge\",\"source\":\"" + ref + "\",\"amount\":" + gross
                + ",\"fee\":" + fee + ",\"net\":" + Math.subtractExact(gross, fee) + "}]}]";
        long fetch = jdbc.sql("""
                INSERT INTO io.processor_fetch (purpose, request, response_body, response_sha256, complete, fetched_at, recorded_at)
                VALUES ('BATCH_LINES', '{}', :body, encode(sha256(convert_to(:body,'UTF8')),'hex'), true, :now, :now) RETURNING id
                """)
                .param("body", body)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        return new PayoutEvidence(
                event,
                "fixture",
                event,
                "payout.reconciliation_completed",
                batch,
                Math.subtractExact(gross, fee),
                "cad",
                true,
                "standard",
                "completed",
                clock.instant(),
                clock.instant(),
                new PayoutEvidence.Lines(
                        fetch,
                        List.of(new PayoutEvidence.Line(
                                "txn_" + batch,
                                "charge",
                                ref,
                                gross,
                                fee,
                                Math.subtractExact(gross, fee),
                                "cad",
                                clock.instant()))));
    }

    RefundResult accept(String payment, long amount) {
        return refunds.accept(payment, "merchant-" + ++next, amount, "fingerprint-" + next);
    }

    String status(String refund) {
        return jdbc.sql("SELECT status FROM core.refund WHERE id = :id")
                .param("id", refund)
                .query(String.class)
                .single();
    }

    long number(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    long count(String table) {
        return number("SELECT count(*) FROM " + table);
    }

    long balance(int code) {
        return number("SELECT balance FROM ledger.account WHERE code = " + code);
    }

    void invariants() {
        RefundAssertions.invariants(jdbc, audit);
    }

    @Override
    public void close() {
        db.drop();
    }
}
