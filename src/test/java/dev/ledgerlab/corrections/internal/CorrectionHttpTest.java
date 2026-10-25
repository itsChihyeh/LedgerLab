package dev.ledgerlab.corrections.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionRequest;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.CaptureResult;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.reconciliation.internal.IssueStore;
import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.RefundException;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.testsupport.DelegatingLedger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CorrectionHttpTest {
    private CorrectionFixture f;
    private MockMvc mvc;
    private String payment;
    private String entry;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        payment = f.s1();
        entry = f.jdbc.sql("SELECT id FROM ledger.journal_entry WHERE subject_id=?")
                .param(payment)
                .query(String.class)
                .single();
        mvc = controller(() -> {});
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private MockMvc controller(Runnable fault) {
        return controller(f.ledger, fault);
    }

    private MockMvc controller(dev.ledgerlab.ledger.Ledger ledger, Runnable fault) {
        var service = new JdbcCorrections(
                f.jdbc,
                ledger,
                new PaymentCorrections(f.jdbc, ledger),
                new IssueStore(f.jdbc, f.ids, f.clock, f.manager),
                f.rechecks,
                new dev.ledgerlab.settlement.SettlementCorrections(f.jdbc, ledger),
                f.clock,
                f.mapper,
                f.manager,
                fault);
        return MockMvcBuilders.standaloneSetup(
                        new CorrectionController(service, new CorrectionQueries(f.db.readonly(), f.mapper), f.mapper))
                .build();
    }

    private ObjectNode body(String target) {
        return (ObjectNode) f.mapper.valueToTree(Map.of(
                "type",
                "VOID_CAPTURE",
                "originalEntryId",
                target,
                "reasonCode",
                "CAPTURE_RECORDED_IN_ERROR",
                "reasonNote",
                "Verified synthetic capture error",
                "evidence",
                List.of(Map.of("type", "inbox_event", "id", "evt_ch_p1")),
                "requestedBy",
                "test-operator"));
    }

    private MockHttpServletResponse submit(ObjectNode body) throws Exception {
        return submit(mvc, f.mapper.writeValueAsString(body));
    }

    private MockHttpServletResponse submit(MockMvc target, String raw) throws Exception {
        return target.perform(post("/api/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(raw))
                .andReturn()
                .getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return f.mapper.readTree(response.getContentAsString());
    }

    private void error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(json(response).path("code").asString()).isEqualTo(code);
    }

    private List<String> snapshot() {
        return List.of(
                        "ledger.correction",
                        "ledger.correction_target",
                        "ledger.journal_entry",
                        "ledger.posting",
                        "ledger.account",
                        "core.payment",
                        "core.payment_transition",
                        "core.refund",
                        "core.refund_transition",
                        "recon.issue",
                        "recon.issue_transition",
                        "core.settlement_batch",
                        "core.settlement_batch_line",
                        "core.batch_transition",
                        "recon.bank_line",
                        "io.outbound_command")
                .stream()
                .map(table -> f.jdbc.sql(
                                "SELECT coalesce(jsonb_agg(row ORDER BY row::text), '[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS row FROM "
                                        + table + " t) data")
                        .query(String.class)
                        .single())
                .toList();
    }

    private String text(String sql) {
        return f.jdbc.sql(sql).query(String.class).single();
    }

    @Test
    void ac_6_5_restateUpdatesAmountAndFeeWithTwoEntries() throws Exception {
        var request = body(entry);
        request.put("type", "RESTATE_CAPTURE_AMOUNT");
        request.put("reasonCode", "CAPTURE_AMOUNT_ERROR");
        request.put("newAmount", 5400);
        var response = submit(request);
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(json(response).path("entries").size()).isEqualTo(2);
        assertThat(f.number("SELECT amount FROM core.payment")).isEqualTo(5400);
        assertThat(f.number("SELECT expected_fee FROM core.payment")).isEqualTo(187);
        f.invariants();
    }

    private ObjectNode restate(String target, long amount) {
        return body(target)
                .put("type", "RESTATE_CAPTURE_AMOUNT")
                .put("reasonCode", "CAPTURE_AMOUNT_ERROR")
                .put("newAmount", amount);
    }

    private String currentEntry() {
        return text("SELECT id FROM ledger.journal_entry WHERE subject_id='" + payment + "' ORDER BY seq DESC LIMIT 1");
    }

    private void balances(long pr, long transit, long cash, long pending, long available, long refunds) {
        assertThat(f.jdbc.sql("SELECT balance FROM ledger.account ORDER BY code")
                        .query(Long.class)
                        .list())
                .containsExactly(pr, transit, cash, pending, available, refunds, 0L, 0L);
        f.invariants();
    }

    @Test
    void ac_1_4d_exampleERechecksArchiveAndResolvesIssueAtomically() throws Exception {
        // Establish the erroneous capture at 4500, using its own real capture entry.
        f.close();
        f = new CorrectionFixture();
        payment = f.payment("ch_p5", 4500);
        entry = currentEntry();
        mvc = controller(() -> {});
        balances(4500, 0, 0, 4500, 0, 0);
        var evidence = f.archivedBatch("po_b6", List.of(f.line("txn_b6", "charge", "ch_p5", 5400, 187)));
        f.tx.executeWithoutResult(tx -> f.batches.process(evidence));
        balances(4500, 0, 0, 4500, 0, 0);
        String issue = text("SELECT id FROM recon.issue WHERE subject_id='po_b6'");
        var request = restate(entry, 5400);
        request.set(
                "evidence",
                f.mapper.valueToTree(List.of(
                        Map.of("type", "issue", "id", issue),
                        Map.of(
                                "type",
                                "processor_fetch",
                                "id",
                                Long.toString(java.util.Objects.requireNonNull(evidence.lines())
                                        .fetchId())))));
        var response = submit(request);
        assertThat(response.getStatus()).isEqualTo(201);
        var result = json(response);
        String correction = result.path("correction").path("id").asString();
        assertThat(result.path("affected").path("batches")).isEqualTo(f.mapper.valueToTree(List.of("po_b6")));
        assertThat(result.path("affected").path("issues")).isEqualTo(f.mapper.valueToTree(List.of(issue)));
        assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo("CONFIRMED");
        assertThat(text("SELECT status FROM core.payment")).isEqualTo("SETTLED");
        assertThat(text("SELECT status FROM recon.issue")).isEqualTo("RESOLVED");
        assertThat(text("SELECT resolution_correction_id FROM recon.issue")).isEqualTo(correction);
        assertThat(f.number("SELECT expected_fee FROM core.payment")).isEqualTo(187);
        assertThat(f.jdbc.sql("""
                SELECT e.entry_type||':'||p.account_code||':'||p.side||':'||p.amount
                FROM ledger.journal_entry e JOIN ledger.posting p ON p.entry_id=e.id
                WHERE e.correction_id=? ORDER BY e.seq,p.line_no
                """).param(correction).query(String.class).list())
                .containsExactly(
                        "REVERSAL:1101:C:4500", "REVERSAL:2101:D:4500",
                        "CORRECTION:1101:D:5400", "CORRECTION:2101:C:5400");
        // Per-posting balances expose the reversal and correction checkpoints within this transaction.
        assertThat(f.jdbc.sql("""
                SELECT p.balance_after FROM ledger.posting p JOIN ledger.journal_entry e ON e.id=p.entry_id
                WHERE e.correction_id=? ORDER BY e.seq,p.line_no
                """).param(correction).query(Long.class).list()).containsExactly(0L, 0L, 5400L, 5400L);
        assertThat(f.number(
                        "SELECT count(*) FROM core.payment_transition WHERE from_status='CAPTURED' AND to_status='CAPTURED' AND evidence_id='"
                                + correction + "'"))
                .isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT to_status FROM core.batch_transition WHERE evidence_id=? ORDER BY id")
                        .param(correction)
                        .query(String.class)
                        .list())
                .containsExactly("RECEIVED", "CONFIRMED");
        balances(0, 5213, 0, 0, 5213, 0);
        assertThat(json(mvc.perform(get("/api/corrections/" + correction))
                        .andReturn()
                        .getResponse()))
                .isEqualTo(result);
    }

    @Test
    void ac_6_5_restateAgainThenVoidUsesCurrentCorrectionEntry() throws Exception {
        assertThat(submit(restate(entry, 5400)).getStatus()).isEqualTo(201);
        String first = currentEntry();
        assertThat(submit(restate(first, 6000)).getStatus()).isEqualTo(201);
        var before = snapshot();
        error(submit(restate(entry, 7000)), 409, "ALREADY_REVERSED");
        error(submit(restate(first, 7000)), 409, "ALREADY_REVERSED");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(submit(body(currentEntry())).getStatus()).isEqualTo(201);
        balances(0, 0, 0, 0, 0, 0);
    }

    @Test
    void ac_6_5_refundFloorAndZeroRemainingApplyImmediately() throws Exception {
        f.accept(payment, 3000);
        var before = snapshot();
        error(submit(restate(entry, 2999)), 409, "AMOUNT_BELOW_REFUNDED");
        error(submit(restate(entry, 10000)), 409, "AMOUNT_UNCHANGED");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(submit(restate(entry, 3000)).getStatus()).isEqualTo(201);
        before = snapshot();
        assertThatThrownBy(() -> f.accept(payment, 1))
                .isInstanceOfSatisfying(
                        RefundException.class, e -> assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_REMAINING"));
        assertThat(snapshot()).isEqualTo(before);
        balances(3000, 0, 0, 3000, -3000, 3000);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, 0, 49, 100000000, Long.MAX_VALUE})
    void ac_6_5_amountRangeRejectsWithoutWrites(long amount) throws Exception {
        var before = snapshot();
        error(submit(restate(entry, amount)), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(longs = {50, 99999999})
    void ac_6_5_amountRangeIncludesBothEndpoints(long amount) throws Exception {
        assertThat(submit(restate(entry, amount)).getStatus()).isEqualTo(201);
        assertThat(f.number("SELECT amount FROM core.payment")).isEqualTo(amount);
        assertThat(f.number("SELECT expected_fee FROM core.payment")).isEqualTo(amount == 50 ? 31 : 2900030);
        balances(amount, 0, 0, amount, 0, 0);
    }

    @Test
    void ac_6_5_restatePreservesTargetErrorOrderAndSettledGuard() throws Exception {
        var before = snapshot();
        error(submit(restate("je_missing", 5400)), 404, "ENTRY_NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
        var corrected = json(submit(restate(entry, 5400)));
        before = snapshot();
        error(
                submit(restate(corrected.path("entries").get(0).path("entryId").asString(), 6000)),
                409,
                "CANNOT_REVERSE_REVERSAL");
        assertThat(snapshot()).isEqualTo(before);
        String target = currentEntry();
        f.batch("po_settled", "ch_p1", 5400, 187);
        f.accept(payment, 1000);
        before = snapshot();
        error(submit(restate(target, 500)), 409, "USE_REFUND_FOR_SETTLED_PAYMENT");
        error(
                submit(restate(text("SELECT id FROM ledger.journal_entry WHERE entry_type='BATCH_SETTLED'"), 500)),
                409,
                "ENTRY_NOT_CORRECTABLE");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    @Test
    void ac_6_5_missingAmountAndBankOnlyEvidenceRejectWithoutWrites() throws Exception {
        var request = restate(entry, 5400);
        request.remove("newAmount");
        error(submit(request), 400, "INVALID_REQUEST");
        f.bankEvidence();
        var before = snapshot();
        request = restate(entry, 5400);
        request.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", "bank_txn", "id", "bt_evidence"))));
        error(submit(request), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_5_unsuccessfulRecheckCommitsCorrectionAndKeepsReferencedIssueOpen() throws Exception {
        var evidence = f.archivedBatch("po_still_held", List.of(f.line("txn_still", "charge", "ch_p1", 5400, 187)));
        f.tx.executeWithoutResult(tx -> f.batches.process(evidence));
        String issue = text("SELECT id FROM recon.issue");
        var request = restate(entry, 5000);
        request.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", "issue", "id", issue))));
        assertThat(submit(request).getStatus()).isEqualTo(201);
        assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo("HELD");
        assertThat(text("SELECT hold_reason FROM core.settlement_batch")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(text("SELECT status FROM recon.issue")).isEqualTo("OPEN");
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE resolution_correction_id IS NOT NULL"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM core.batch_transition WHERE to_status='HELD'"))
                .isEqualTo(2);
        balances(5000, 0, 0, 5000, 0, 0);
    }

    @Test
    void ac_6_5_recheckRefreshesReasonWithoutTouchingUnrelatedBatches() throws Exception {
        var evidence = f.archivedBatch(
                "po_refresh",
                List.of(
                        f.line("txn_refresh", "charge", "ch_p1", 5400, 187),
                        f.line("txn_missing", "charge", "ch_missing", 1000, 59)));
        f.tx.executeWithoutResult(tx -> f.batches.process(evidence));
        f.payment("ch_unrelated", 1000);
        var other =
                f.archivedBatch("po_unrelated", List.of(f.line("txn_unrelated", "charge", "ch_unrelated", 2000, 88)));
        f.tx.executeWithoutResult(tx -> f.batches.process(other));
        assertThat(submit(restate(entry, 5400)).getStatus()).isEqualTo(201);
        assertThat(text("SELECT hold_reason FROM core.settlement_batch WHERE batch_id='po_refresh'"))
                .isEqualTo("UNKNOWN_PAYMENT");
        assertThat(text("SELECT hold_reason FROM core.settlement_batch WHERE batch_id='po_unrelated'"))
                .isEqualTo("AMOUNT_MISMATCH");
        assertThat(
                        f.number(
                                "SELECT count(*) FROM core.batch_transition WHERE subject_id='po_unrelated' AND evidence_type='correction'"))
                .isZero();
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE status='OPEN'"))
                .isEqualTo(2);
        f.invariants();
    }

    @Test
    void ac_6_5_recheckReleasesFifoAndWakesBankWaiting() throws Exception {
        f.accept(payment, 1000);
        f.accept(payment, 1000);
        var evidence = f.archivedBatch("po_wake", List.of(f.line("txn_wake", "charge", "ch_p1", 5400, 187)));
        f.tx.executeWithoutResult(tx -> f.batches.process(evidence));
        f.bankWaiting("po_wake", 5213);
        assertThat(text("SELECT match_status FROM recon.bank_line")).isEqualTo("WAITING");
        assertThat(submit(restate(entry, 5400)).getStatus()).isEqualTo(201);
        assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo("BANKED");
        assertThat(text("SELECT match_status FROM recon.bank_line")).isEqualTo("MATCHED");
        assertThat(f.number("SELECT count(*) FROM core.refund WHERE status='READY'"))
                .isEqualTo(2);
        assertThat(f.count("io.outbound_command")).isEqualTo(2);
        balances(0, 0, 5213, 0, 3213, 2000);
    }

    @Test
    void ac_6_5_failureAfterRecheckRollsBackCorrectionBatchBankAndIssues() {
        var evidence = f.archivedBatch("po_rollback", List.of(f.line("txn_rollback", "charge", "ch_p1", 5400, 187)));
        f.tx.executeWithoutResult(tx -> f.batches.process(evidence));
        f.bankWaiting("po_rollback", 5213);
        var before = snapshot();
        assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> {
                    try {
                        assertThat(submit(restate(entry, 5400)).getStatus()).isEqualTo(201);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo("BANKED");
                    throw new IllegalStateException("after recheck");
                }))
                .hasMessage("after recheck");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(text("SELECT match_status FROM recon.bank_line")).isEqualTo("WAITING");
        f.invariants();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_6_5_restateAndRefundSerializeBothOrders(boolean restateFirst) throws Exception {
        var barrier = new CyclicBarrier(2);
        var locked = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return f.tx.execute(tx -> {
                    f.ledger.lockFunds();
                    locked.countDown();
                    await(attempting);
                    if (restateFirst) {
                        try {
                            return submit(restate(entry, 5000)).getStatus();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    f.accept(payment, 6000);
                    return 201;
                });
            });
            var second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                await(locked);
                return f.tx.execute(tx -> {
                    attempting.countDown();
                    f.ledger.lockFunds();
                    if (!restateFirst) {
                        try {
                            error(submit(restate(entry, 5000)), 409, "AMOUNT_BELOW_REFUNDED");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    } else {
                        assertThatThrownBy(() -> f.accept(payment, 6000))
                                .isInstanceOfSatisfying(
                                        RefundException.class,
                                        e -> assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_REMAINING"));
                        // The joined rejection marks this outer test transaction rollback-only.
                        tx.setRollbackOnly();
                    }
                    f.invariants();
                    tx.setRollbackOnly();
                    return 409;
                });
            });
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(f.number("SELECT amount FROM core.payment")).isEqualTo(restateFirst ? 5000 : 10000);
        assertThat(f.number("SELECT refunded_amount FROM core.payment")).isEqualTo(restateFirst ? 0 : 6000);
        f.invariants();
    }

    @Test
    void ac_6_3_voidIsAtomicAndTraceableAndChargeReplayIsDuplicate() throws Exception {
        String original = text("SELECT to_jsonb(e)::text FROM ledger.journal_entry e WHERE id='" + entry + "'");
        String originalPostings =
                text("SELECT jsonb_agg(to_jsonb(p) ORDER BY line_no)::text FROM ledger.posting p WHERE entry_id='"
                        + entry + "'");
        var response = submit(body(entry));
        assertThat(response.getStatus()).isEqualTo(201);
        var result = json(response);
        String id = result.path("correction").path("id").asString();
        assertThat(id).matches("cor_[0-7][0-9A-HJKMNP-TV-Z]{25}");
        assertThat(result.path("entries").get(0).path("entryType").asString()).isEqualTo("REVERSAL");
        assertThat(result.path("entries").get(0).path("postings")).isEqualTo(f.mapper.readTree("""
                [{"lineNo":1,"accountCode":1101,"side":"C","amount":10000},{"lineNo":2,"accountCode":2101,"side":"D","amount":10000}]
                """));
        assertThat(text("SELECT to_jsonb(e)::text FROM ledger.journal_entry e WHERE id='" + entry + "'"))
                .isEqualTo(original);
        assertThat(text("SELECT jsonb_agg(to_jsonb(p) ORDER BY line_no)::text FROM ledger.posting p WHERE entry_id='"
                        + entry + "'"))
                .isEqualTo(originalPostings);
        assertThat(f.number("SELECT count(*) FROM ledger.account WHERE balance<>0"))
                .isZero();
        assertThat(text("SELECT status FROM core.payment")).isEqualTo("VOIDED");
        assertThat(f.number("SELECT amount FROM core.payment")).isEqualTo(10000);
        assertThat(f.number("SELECT expected_fee FROM core.payment")).isEqualTo(320);
        assertThat(f.number(
                        "SELECT count(*) FROM core.payment_transition WHERE from_status='CAPTURED' AND to_status='VOIDED' AND evidence_type='correction' AND evidence_id='"
                                + id + "'"))
                .isEqualTo(1);
        assertThat(json(mvc.perform(get("/api/corrections/" + id)).andReturn().getResponse()))
                .isEqualTo(result);
        assertThat(json(mvc.perform(get("/api/corrections")
                                        .param("subjectType", "payment")
                                        .param("subjectId", payment))
                                .andReturn()
                                .getResponse())
                        .get(0))
                .isEqualTo(result);
        assertThat(json(mvc.perform(get("/api/corrections").param("subjectId", "unrelated"))
                                .andReturn()
                                .getResponse())
                        .isEmpty())
                .isTrue();
        error(mvc.perform(get("/api/corrections/cor_unknown")).andReturn().getResponse(), 404, "CORRECTION_NOT_FOUND");
        var before = snapshot();
        f.tx.executeWithoutResult(tx -> assertThat(f.capture.capture(new ChargeEvidence(
                        "evt_ch_p1",
                        "ch_p1",
                        null,
                        10000L,
                        10000L,
                        "cad",
                        true,
                        "succeeded",
                        false,
                        f.clock.instant(),
                        f.clock.instant())))
                .isEqualTo(CaptureResult.DUPLICATE_FACT));
        assertThatThrownBy(() -> f.accept(payment, 100))
                .isInstanceOfSatisfying(
                        RefundException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_NOT_REFUNDABLE"));
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_6_4_settledPaymentRejectsBeforeRefundCheckWithoutWrites(boolean hasRefund) throws Exception {
        f.batch("po_b1", "ch_p1", 10000, 320);
        if (hasRefund) f.accept(payment, 100);
        var before = snapshot();
        error(submit(body(entry)), 409, "USE_REFUND_FOR_SETTLED_PAYMENT");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    @Test
    void ac_6_3_refundedUnsettledPaymentCannotBeVoided() throws Exception {
        f.accept(payment, 100);
        var before = snapshot();
        error(submit(body(entry)), 409, "PAYMENT_HAS_REFUNDS");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    @Test
    void ac_6_6_repeatAndReversalTargetsHaveOrderedConflicts() throws Exception {
        var first = json(submit(body(entry)));
        String id = first.path("correction").path("id").asString();
        var before = snapshot();
        var repeat = submit(body(entry));
        error(repeat, 409, "ALREADY_REVERSED");
        assertThat(json(repeat).path("correctionId").asString()).isEqualTo(id);
        error(submit(body(first.path("entries").get(0).path("entryId").asString())), 409, "CANNOT_REVERSE_REVERSAL");
        error(submit(body("je_missing")), 404, "ENTRY_NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "reasonCode", "reasonNote", "evidence", "requestedBy", "originalEntryId"})
    void ac_6_7_requiredFieldsRejectWithoutWrites(String field) throws Exception {
        var before = snapshot();
        var request = body(entry);
        request.remove(field);
        error(submit(request), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"issue", "statement_line", "bank_txn", "inbox_event", "processor_fetch"})
    void ac_6_7_eachEvidenceKindMustExist(String type) throws Exception {
        var before = snapshot();
        var request = body(entry);
        request.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", type, "id", "missing"))));
        error(submit(request), 400, "EVIDENCE_NOT_FOUND");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_7_invalidJsonReasonsAndEmptyEvidenceRejectWithoutWrites() throws Exception {
        var before = snapshot();
        for (String field : List.of("reasonNote", "requestedBy")) {
            var request = body(entry);
            request.put(field, " \t\n");
            error(submit(request), 400, "INVALID_REQUEST");
        }
        var request = body(entry);
        request.set("evidence", f.mapper.createArrayNode());
        error(submit(request), 400, "INVALID_REQUEST");
        request = body(entry);
        request.put("reasonNote", "x".repeat(2001));
        error(submit(request), 400, "INVALID_REQUEST");
        request = body(entry);
        request.put("reasonCode", "CAPTURE_AMOUNT_ERROR");
        error(submit(request), 400, "INVALID_REQUEST");
        String valid = f.mapper.writeValueAsString(body(entry));
        for (String raw : List.of(
                "[]",
                "null",
                "{",
                valid + " {}",
                valid.replace("\"type\":", "\"type\":\"VOID_CAPTURE\",\"type\":"),
                valid.replace("\"reasonNote\":", "\"newAmount\":1.1,\"reasonNote\":")))
            error(submit(mvc, raw), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_8_httpFailureAfterReversalRollsBackEverything() {
        f.tx.executeWithoutResult(
                tx -> f.issues.open("EVENT_BUSINESS_CONFLICT", "event", "evt_rollback", "{}", "{}", "[]"));
        var before = snapshot();
        var broken = controller(() -> {
            throw new IllegalStateException("injected after reversal");
        });
        assertThatThrownBy(() -> submit(broken, f.mapper.writeValueAsString(body(entry))))
                .hasRootCauseMessage("injected after reversal");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(text("SELECT status FROM core.payment")).isEqualTo("CAPTURED");
        f.invariants();
    }

    @Test
    void ac_6_7_existingBankStatementAndProcessorEvidenceAreAccepted() throws Exception {
        f.payment("ch_evidence", 10000);
        f.batch("po_evidence", "ch_evidence", 10000, 320);
        f.bankEvidence();
        String fetch = text("SELECT id::text FROM io.processor_fetch WHERE purpose='BATCH_LINES'");
        var request = body(entry);
        request.set(
                "evidence",
                f.mapper.valueToTree(List.of(
                        Map.of("type", "statement_line", "id", "txn_po_evidence"),
                        Map.of("type", "bank_txn", "id", "bt_evidence"),
                        Map.of("type", "processor_fetch", "id", fetch))));
        assertThat(submit(request).getStatus()).isEqualTo(201);
        f.invariants();
    }

    @Test
    void ac_6_3_referencedOpenIssuesResolveAndClosedIssuesRemainUnchanged() throws Exception {
        f.tx.executeWithoutResult(tx -> {
            f.issues.open("EVENT_BUSINESS_CONFLICT", "event", "evt_open", "{}", "{}", "[]");
            f.issues.open("EVENT_BUSINESS_CONFLICT", "event", "evt_closed", "{}", "{}", "[]");
        });
        String open = text("SELECT id FROM recon.issue WHERE subject_id='evt_open'");
        String closed = text("SELECT id FROM recon.issue WHERE subject_id='evt_closed'");
        new IssueStore(f.jdbc, f.ids, f.clock, f.manager).accept(closed, "already reviewed");
        var request = body(entry);
        request.set(
                "evidence",
                f.mapper.valueToTree(
                        List.of(Map.of("type", "issue", "id", open), Map.of("type", "issue", "id", closed))));
        var result = json(submit(request));
        String id = result.path("correction").path("id").asString();
        assertThat(text("SELECT status FROM recon.issue WHERE id='" + open + "'"))
                .isEqualTo("RESOLVED");
        assertThat(text("SELECT resolution_correction_id FROM recon.issue WHERE id='" + open + "'"))
                .isEqualTo(id);
        assertThat(text("SELECT status FROM recon.issue WHERE id='" + closed + "'"))
                .isEqualTo("ACCEPTED");
        assertThat(f.number("SELECT count(*) FROM recon.issue_transition WHERE issue_id='" + open
                        + "' AND reason='OPERATOR' AND to_status='RESOLVED'"))
                .isEqualTo(1);
        assertThat(result.path("affected").path("issues")).isEqualTo(f.mapper.valueToTree(List.of(open)));
        assertThat(json(mvc.perform(get("/api/corrections/" + id)).andReturn().getResponse()))
                .isEqualTo(result);
    }

    @Test
    void ac_6_2_refundBusinessEntriesNeverBecomeCorrections() throws Exception {
        var cancelled = f.accept(payment, 100);
        f.refunds.cancel(cancelled.refundId(), "customer cancelled");
        f.batch("po_b1", "ch_p1", 10000, 320);
        var executed = f.accept(payment, 200);
        f.refundEvidence(executed.refundId(), ProcessorRefundFact.Status.succeeded);
        var failed = f.accept(payment, 300);
        f.refundEvidence(failed.refundId(), ProcessorRefundFact.Status.failed);
        assertThat(f.jdbc.sql(
                                "SELECT DISTINCT entry_type FROM ledger.journal_entry WHERE subject_type='refund' ORDER BY entry_type")
                        .query(String.class)
                        .list())
                .containsExactly("REFUND_ACCEPTED", "REFUND_CANCELLED", "REFUND_EXECUTED", "REFUND_FAILED");
        assertThat(
                        f.number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE reverses_entry_id IS NOT NULL OR correction_id IS NOT NULL"))
                .isZero();
        assertThat(f.count("ledger.correction")).isZero();
        var before = snapshot();
        for (String target : f.jdbc.sql("SELECT id FROM ledger.journal_entry WHERE subject_type IN ('refund', 'batch')")
                .query(String.class)
                .list()) error(submit(body(java.util.Objects.requireNonNull(target))), 409, "ENTRY_NOT_CORRECTABLE");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLY_UNAPPLIED_CASH", "RESTORE_FAILED_PAYOUT"})
    void ac_6_1_bankCorrectionTypesRequireTheirOwnParameters(String type) throws Exception {
        var before = snapshot();
        var request = body(entry);
        request.put("type", type);
        error(submit(request), 400, "INVALID_REQUEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_3_currentRestatedAmountEntryCanBeVoided() throws Exception {
        String target = f.tx.execute(tx -> {
            f.ledger.lockFunds();
            var restated = f.ledger.recordCorrection(new CorrectionRequest(
                    CorrectionType.RESTATE_CAPTURE_AMOUNT,
                    CorrectionReason.CAPTURE_AMOUNT_ERROR,
                    "prior correction fixture",
                    "[{\"type\":\"inbox_event\",\"id\":\"evt_ch_p1\"}]",
                    "{}",
                    SubjectType.PAYMENT,
                    payment,
                    "operator",
                    List.of(entry),
                    List.of(),
                    List.of(
                            PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 12000),
                            PostingLine.credit(Account.MERCHANT_PENDING, 12000)),
                    f.clock.instant()));
            f.jdbc.sql("UPDATE core.payment SET amount=12000, expected_fee=378 WHERE id=?")
                    .param(payment)
                    .update();
            return restated.entries().getLast().entryId();
        });
        assertThat(submit(body(java.util.Objects.requireNonNull(target))).getStatus())
                .isEqualTo(201);
        assertThat(f.number("SELECT count(*) FROM ledger.account WHERE balance<>0"))
                .isZero();
        f.invariants();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ac_6_3_voidAndBatchConfirmationSerializeInBothOrders(boolean voidFirst) throws Exception {
        var evidence = f.batchEvidence("po_race");
        var start = new CyclicBarrier(2);
        var firstLocked = new CountDownLatch(1);
        var secondAttempted = new CountDownLatch(1);
        var secondLedger = new DelegatingLedger(f.ledger) {
            @Override
            public void lockFunds() {
                secondAttempted.countDown();
                super.lockFunds();
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return f.tx.execute(tx -> {
                    f.ledger.lockFunds();
                    firstLocked.countDown();
                    await(secondAttempted);
                    if (voidFirst) {
                        try {
                            return submit(body(entry)).getStatus();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                    f.batches.process(evidence);
                    return 0;
                });
            });
            var second = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                await(firstLocked);
                if (!voidFirst)
                    return submit(controller(secondLedger, () -> {}), f.mapper.writeValueAsString(body(entry)))
                            .getStatus();
                return f.tx.execute(tx -> {
                    secondLedger.lockFunds();
                    f.batches.process(evidence);
                    return 0;
                });
            });
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(voidFirst ? 201 : 0);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(voidFirst ? 0 : 409);
        }
        assertThat(text("SELECT status FROM core.payment")).isEqualTo(voidFirst ? "VOIDED" : "SETTLED");
        assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo(voidFirst ? "HELD" : "CONFIRMED");
        if (voidFirst) {
            assertThat(text("SELECT hold_reason FROM core.settlement_batch")).isEqualTo("PAYMENT_STATE_CONFLICT");
            assertThat(text("SELECT category FROM recon.issue")).isEqualTo("BATCH_STATE_CONFLICT");
        }
        assertThat(f.count("ledger.correction")).isEqualTo(voidFirst ? 1 : 0);
        f.invariants();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void ac_6_3_concurrentAcceptCommitsBeforeCorrectionReadsIssue() throws Exception {
        String issue = correctionRaceIssue();
        String request = correctionRaceRequest(issue);
        var attempting = new CountDownLatch(1);
        var correctionPid = new AtomicInteger();
        var controlled = controller(() -> {
            correctionPid.set(backendPid());
            attempting.countDown();
        });
        var store = new IssueStore(f.jdbc, f.ids, f.clock, f.manager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var correction = f.tx.execute(tx -> {
                // The committed version is still OPEN while accept holds the updated row.
                store.accept(issue, "concurrent operator acceptance");
                int acceptingPid = backendPid();
                var pending = pool.submit(() -> submit(controlled, request));
                await(attempting);
                awaitBlockedBy(correctionPid.get(), acceptingPid);
                // Without FOR UPDATE, correction has read stale OPEN and is now blocked on UPDATE.
                return pending;
            });
            var response = java.util.Objects.requireNonNull(correction).get(20, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(201);
            assertCorrectionRaceCommitted(issue, "ACCEPTED");
            assertThat(text("SELECT explanation FROM recon.issue WHERE id='" + issue + "'"))
                    .isEqualTo("concurrent operator acceptance");
            assertThat(f.number("SELECT count(*) FROM recon.issue WHERE resolution_correction_id IS NOT NULL"))
                    .isZero();
            assertThat(f.number("SELECT count(*) FROM recon.issue_transition WHERE to_status='RESOLVED'"))
                    .isZero();
        }
    }

    @Test
    void ac_6_3_correctionCommitsBeforeConcurrentAcceptReadsIssue() throws Exception {
        String issue = correctionRaceIssue();
        String request = correctionRaceRequest(issue);
        var attempting = new CountDownLatch(1);
        var acceptingPid = new AtomicInteger();
        var store = new IssueStore(f.jdbc, f.ids, f.clock, f.manager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var acceptance = f.tx.execute(tx -> {
                try {
                    assertThat(submit(mvc, request).getStatus()).isEqualTo(201);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                int correctionPid = backendPid();
                var pending = pool.submit(() -> assertThatThrownBy(() -> f.tx.executeWithoutResult(ignored -> {
                            acceptingPid.set(backendPid());
                            attempting.countDown();
                            store.accept(issue, "concurrent operator acceptance");
                        }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Only OPEN issues can be disposed"));
                await(attempting);
                awaitBlockedBy(acceptingPid.get(), correctionPid);
                return pending;
            });
            java.util.Objects.requireNonNull(acceptance).get(20, TimeUnit.SECONDS);
            assertCorrectionRaceCommitted(issue, "RESOLVED");
            String correctionId = text("SELECT id FROM ledger.correction");
            assertThat(text("SELECT resolution_correction_id FROM recon.issue WHERE id='" + issue + "'"))
                    .isEqualTo(correctionId);
            assertThat(text("SELECT evidence_refs->0->>'id' FROM recon.issue_transition WHERE reason='OPERATOR'"))
                    .isEqualTo(correctionId);
            assertThat(text("SELECT evidence_refs->0->>'type' FROM recon.issue_transition WHERE reason='OPERATOR'"))
                    .isEqualTo("correction");
        }
    }

    private String correctionRaceIssue() {
        f.tx.executeWithoutResult(
                tx -> f.issues.open("EVENT_BUSINESS_CONFLICT", "event", "evt_correction_race", "{}", "{}", "[]"));
        return text("SELECT id FROM recon.issue WHERE subject_id='evt_correction_race'");
    }

    private String correctionRaceRequest(String issue) {
        var request = body(entry);
        request.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", "issue", "id", issue))));
        return f.mapper.writeValueAsString(request);
    }

    private int backendPid() {
        return f.jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private void awaitBlockedBy(int waitingPid, int holdingPid) {
        // Observe a real database wait, not elapsed time or thread scheduling.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (f.jdbc.sql("SELECT ? = ANY(pg_blocking_pids(?))")
                    .params(holdingPid, waitingPid)
                    .query(Boolean.class)
                    .single()) return;
        }
        throw new AssertionError("Expected backend " + waitingPid + " to wait for " + holdingPid);
    }

    private void assertCorrectionRaceCommitted(String issue, String status) {
        assertThat(text("SELECT status FROM recon.issue WHERE id='" + issue + "'"))
                .isEqualTo(status);
        assertThat(f.jdbc.sql(
                                "SELECT from_status || '->' || to_status FROM recon.issue_transition WHERE issue_id=? AND reason='OPERATOR'")
                        .param(issue)
                        .query(String.class)
                        .list())
                .containsExactly("OPEN->" + status);
        assertThat(f.count("ledger.correction")).isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='REVERSAL'"))
                .isEqualTo(1);
        assertThat(text("SELECT status FROM core.payment")).isEqualTo("VOIDED");
        f.invariants();
    }
}
