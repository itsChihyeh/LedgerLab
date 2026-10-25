package dev.ledgerlab.settlement.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.settlement.PayoutEvidence;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BatchRecheckTest {
    private CorrectionFixture f;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        mvc = MockMvcBuilders.standaloneSetup(new BatchRecheckController(f.rechecks))
                .build();
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private MockHttpServletResponse recheck(String batch) throws Exception {
        return mvc.perform(post("/api/batches/" + batch + "/recheck"))
                .andReturn()
                .getResponse();
    }

    private String text(String sql) {
        return f.jdbc.sql(sql).query(String.class).single();
    }

    private void error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(f.mapper.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo(code);
    }

    /** A P2 historical HELD row and immutable archive, not a replay through today's line support. */
    private PayoutEvidence historicalRefundBatch() {
        String payment = f.s1();
        f.batch("po_initial", "ch_p1", 10000, 320);
        var refund = f.accept(payment, 1000);
        f.refundEvidence(refund.refundId(), ProcessorRefundFact.Status.pending);
        f.payment("ch_p2", 10000);
        var evidence = f.archivedBatch(
                "po_historical",
                List.of(
                        f.line("txn_p2", "charge", "ch_p2", 10000, 320),
                        f.line("txn_refund", "refund", "re_" + refund.refundId(), -1000, 0)));
        var now = f.clock.instant().atOffset(ZoneOffset.UTC);
        f.jdbc.sql("""
                INSERT INTO core.settlement_batch(batch_id,payout_amount,currency,automatic,method,arrival_date,
                    status,hold_reason,source_event_id,occurred_at,recorded_at,updated_at,lines_fetch_id)
                VALUES ('po_historical',8680,'cad',true,'standard',?,'HELD','UNSUPPORTED_LINE',
                    'evt_po_historical',?,?,?,?)
                """)
                .params(
                        now.toLocalDate(),
                        now,
                        now,
                        now,
                        java.util.Objects.requireNonNull(evidence.lines()).fetchId())
                .update();
        f.jdbc.sql("""
                INSERT INTO core.batch_transition(subject_id,from_status,to_status,evidence_type,evidence_id,
                    occurred_at,recorded_at,hold_reason,lines_fetch_id)
                VALUES ('po_historical','RECEIVED','HELD','inbox_event','evt_po_historical',?,?,'UNSUPPORTED_LINE',?)
                """)
                .params(
                        now,
                        now,
                        java.util.Objects.requireNonNull(evidence.lines()).fetchId())
                .update();
        f.tx.executeWithoutResult(tx -> f.issues.open("UNSUPPORTED_EVENT", "batch", "po_historical", "{}", "{}", "[]"));
        return evidence;
    }

    @Test
    void ac_6_12_unknownPaymentKeepsOriginalCutoffForLegacyHistoryWithoutFetchId() {
        String payment = f.s1();
        f.batch("po_initial", "ch_p1", 10000, 320);
        String refund = f.accept(payment, 1000).refundId();
        var evidence = f.archivedBatch(
                "po_legacy",
                List.of(
                        f.line("txn_legacy", "charge", "ch_legacy", 5000, 175),
                        f.line("txn_legacy_refund", "refund", "re_legacy", -1000, 0)));
        refundArchive(refund);
        var now = f.clock.instant().atOffset(ZoneOffset.UTC);
        f.jdbc.sql("""
                INSERT INTO core.settlement_batch(batch_id,payout_amount,currency,automatic,method,arrival_date,
                    status,hold_reason,source_event_id,occurred_at,recorded_at,updated_at,lines_fetch_id)
                VALUES ('po_legacy',3825,'cad',true,'standard',?,'HELD','UNKNOWN_PAYMENT',
                    'evt_po_legacy',?,?,?,?)
                """)
                .params(
                        now.toLocalDate(),
                        now,
                        now,
                        now,
                        java.util.Objects.requireNonNull(evidence.lines()).fetchId())
                .update();
        // V11 deliberately did not backfill old transition.lines_fetch_id.
        f.jdbc.sql("""
                INSERT INTO core.batch_transition(subject_id,from_status,to_status,evidence_type,evidence_id,
                    occurred_at,recorded_at,hold_reason)
                VALUES ('po_legacy','RECEIVED','HELD','inbox_event','evt_po_legacy',?,?,'UNKNOWN_PAYMENT')
                """).params(now, now).update();
        f.clock.set(f.clock.instant().plusSeconds(1));
        refundArchive("rfd_unrelated_later_metadata");
        f.payment("ch_legacy", 5000);
        long fetches = f.count("io.processor_fetch");
        f.tx.executeWithoutResult(tx -> f.rechecks.recheckUnknownPayment("ch_legacy", "evt_ch_legacy"));
        assertThat(text("SELECT status FROM core.settlement_batch WHERE batch_id='po_legacy'"))
                .isEqualTo("CONFIRMED");
        assertThat(text("SELECT status FROM core.refund")).isEqualTo("SUCCEEDED");
        assertThat(text("SELECT processor_refund_ref FROM core.refund")).isEqualTo("re_legacy");
        assertThat(f.count("io.processor_fetch")).isEqualTo(fetches);
        f.invariants();
    }

    private void refundArchive(String refundId) {
        String body = f.mapper.writeValueAsString(java.util.Map.of(
                "id",
                "re_legacy",
                "amount",
                1000,
                "charge",
                "ch_p1",
                "metadata",
                java.util.Map.of("ledgerlab_refund_id", refundId)));
        var now = f.clock.instant().atOffset(ZoneOffset.UTC);
        f.jdbc.sql("""
                INSERT INTO io.processor_fetch(purpose,request,response_body,response_sha256,complete,fetched_at,recorded_at)
                VALUES ('REFUND_QUERY','{"path":"/v1/refunds/re_legacy"}',?,encode(sha256(convert_to(?,'UTF8')),'hex'),true,?,?)
                """).params(body, body, now, now).update();
    }

    @Test
    void ac_6_12_historicalRefundBatchRechecksFixedArchiveAndExecutesOnlyOnce() throws Exception {
        var original = historicalRefundBatch();
        // A later complete fetch contradicts the fixed archive. Recheck must ignore it.
        f.archivedBatch("po_historical", List.of(f.line("txn_later", "charge", "ch_p2", 12000, 320)));
        long fetches = f.count("io.processor_fetch");
        var response = recheck("po_historical");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(f.mapper
                        .readTree(response.getContentAsString())
                        .path("status")
                        .asString())
                .isEqualTo("CONFIRMED");
        assertThat(f.number("SELECT lines_fetch_id FROM core.settlement_batch WHERE batch_id='po_historical'"))
                .isEqualTo(java.util.Objects.requireNonNull(original.lines()).fetchId());
        assertThat(text("SELECT status FROM core.refund")).isEqualTo("SUCCEEDED");
        assertThat(text("SELECT batch_id FROM core.refund")).isEqualTo("po_historical");
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='REFUND_EXECUTED'"))
                .isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE source_key='batch:po_historical:settled'"))
                .isEqualTo(1);
        assertThat(text("SELECT status FROM recon.issue WHERE subject_id='po_historical'"))
                .isEqualTo("RESOLVED");
        assertThat(text("SELECT explanation FROM recon.issue WHERE subject_id='po_historical'"))
                .isEqualTo("运营重核通过");
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE resolution_correction_id IS NOT NULL"))
                .isZero();
        assertThat(text("SELECT reason FROM recon.issue_transition WHERE to_status='RESOLVED'"))
                .isEqualTo("OPERATOR");
        long entries = f.count("ledger.journal_entry");
        long history = f.count("core.batch_transition");
        error(recheck("po_historical"), 409, "BATCH_NOT_HELD");
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(f.count("core.batch_transition")).isEqualTo(history);
        assertThat(f.count("io.processor_fetch")).isEqualTo(fetches);
        f.invariants();
    }

    @Test
    void ac_6_12_recheckFailureRefreshesReasonAndKeepsOldIssueOpen() throws Exception {
        historicalRefundBatch();
        // New local evidence says the refund failed. The old archive still has the execution line.
        String refund = text("SELECT id FROM core.refund");
        f.refundEvidence(refund, ProcessorRefundFact.Status.failed);
        long entries = f.count("ledger.journal_entry");
        assertThat(recheck("po_historical").getStatus()).isEqualTo(200);
        assertThat(text("SELECT hold_reason FROM core.settlement_batch WHERE batch_id='po_historical'"))
                .isEqualTo("REFUND_STATE_CONFLICT");
        assertThat(text("SELECT status FROM recon.issue WHERE category='UNSUPPORTED_EVENT'"))
                .isEqualTo("OPEN");
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM core.batch_transition WHERE subject_id='po_historical' AND to_status='HELD'"))
                .isEqualTo(2);
        f.invariants();
    }

    @Test
    void ac_6_12_unsupportedPayoutAndMissingBatchRejectWithoutWrites() throws Exception {
        var base = f.archivedBatch("po_manual", List.of(f.line("txn_manual", "charge", "ch_missing", 10000, 320)));
        var manual = new PayoutEvidence(
                base.eventId(),
                base.evidenceType(),
                base.evidenceId(),
                base.eventType(),
                base.batchId(),
                base.amount(),
                base.currency(),
                false,
                base.method(),
                base.reconciliationStatus(),
                base.arrivalDate(),
                base.created(),
                base.lines());
        f.tx.executeWithoutResult(tx -> f.batches.process(manual));
        long history = f.count("core.batch_transition");
        error(recheck("po_manual"), 409, "BATCH_NOT_RECHECKABLE");
        error(recheck("po_missing"), 404, "BATCH_NOT_FOUND");
        assertThat(f.count("core.batch_transition")).isEqualTo(history);
        assertThat(f.count("ledger.journal_entry")).isZero();
    }

    @Test
    void ac_6_12_concurrentOperatorRechecksConfirmAndExecuteOnlyOnce() throws Exception {
        historicalRefundBatch();
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return recheck("po_historical").getStatus();
            });
            var second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return recheck("po_historical").getStatus();
            });
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='REFUND_EXECUTED'"))
                .isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE source_key='batch:po_historical:settled'"))
                .isEqualTo(1);
        f.invariants();
    }

    @Test
    void ac_6_12_recheckJoinsCallerTransactionAndRollsBackAllEffects() {
        historicalRefundBatch();
        long entries = f.count("ledger.journal_entry");
        long history = f.count("core.batch_transition");
        assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> {
                    assertThat(f.rechecks.recheck("po_historical").status()).isEqualTo("CONFIRMED");
                    throw new IllegalStateException("rollback after recheck");
                }))
                .hasMessage("rollback after recheck");
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(f.count("core.batch_transition")).isEqualTo(history);
        assertThat(text("SELECT status FROM core.settlement_batch WHERE batch_id='po_historical'"))
                .isEqualTo("HELD");
        assertThat(text("SELECT status FROM core.refund")).isEqualTo("SUBMITTED");
        assertThat(text("SELECT status FROM recon.issue WHERE subject_id='po_historical'"))
                .isEqualTo("OPEN");
        f.invariants();
    }
}
