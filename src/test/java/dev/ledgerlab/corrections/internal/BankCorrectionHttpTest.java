package dev.ledgerlab.corrections.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.reconciliation.internal.IssueStore;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.shared.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.ObjectNode;

class BankCorrectionHttpTest {
    private CorrectionFixture f;
    private MockMvc mvc;
    private BankStatements banks;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        f.s1();
        f.batch("po_b1", "ch_p1", 10000, 320);
        banks = banks(f.ledger);
        mvc = controller(f.ledger, () -> {});
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private BankStatements banks(Ledger ledger) {
        return new BankStatements(
                f.jdbc, ledger, f.issues, f.clock, f.ids, new BusinessCalendar(), f.mapper, f.manager, 2);
    }

    private MockMvc controller(Ledger ledger, Runnable fault) {
        return controller(ledger, fault, new IssueStore(f.jdbc, f.ids, f.clock, f.manager));
    }

    private MockMvc controller(Ledger ledger, Runnable fault, dev.ledgerlab.reconciliation.IssueDisposition issues) {
        var service = new JdbcCorrections(
                f.jdbc,
                ledger,
                new PaymentCorrections(f.jdbc, ledger),
                issues,
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

    private ObjectNode request(boolean restore, String... ids) {
        var body = (ObjectNode) f.mapper.valueToTree(Map.of(
                "type",
                restore ? "RESTORE_FAILED_PAYOUT" : "APPLY_UNAPPLIED_CASH",
                "batchId",
                "po_b1",
                "reasonCode",
                restore ? "PAYOUT_FAILURE_DISPROVED" : "SPLIT_DEPOSIT",
                "reasonNote",
                "Verified synthetic bank evidence",
                "requestedBy",
                "operator",
                "evidence",
                List.of(Map.of("type", "bank_txn", "id", ids[0]))));
        if (restore) body.put("bankTxnId", ids[0]);
        else body.set("bankTxnIds", f.mapper.valueToTree(List.of(ids)));
        return body;
    }

    private MockHttpServletResponse submit(ObjectNode body) throws Exception {
        return mvc.perform(post("/api/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(f.mapper.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    private String success(ObjectNode body) throws Exception {
        var response = submit(body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return f.mapper
                .readTree(response.getContentAsString())
                .path("correction")
                .path("id")
                .asString();
    }

    private void rejected(ObjectNode body, int status, String code) throws Exception {
        var before = snapshot();
        var response = submit(body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(f.mapper.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo(code);
        assertThat(snapshot()).isEqualTo(before);
    }

    private List<String> snapshot() {
        return List.of(
                        "ledger.correction",
                        "ledger.correction_target",
                        "ledger.journal_entry",
                        "ledger.posting",
                        "ledger.account",
                        "core.settlement_batch",
                        "core.settlement_batch_line",
                        "core.batch_transition",
                        "recon.bank_line",
                        "recon.issue",
                        "recon.issue_transition")
                .stream()
                .map(table -> text("SELECT coalesce(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text FROM "
                        + "(SELECT to_jsonb(t) AS row FROM " + table + " t) data"))
                .toList();
    }

    private String text(String sql) {
        return f.jdbc.sql(sql).query(String.class).single();
    }

    private void statement(long opening, long closing, String... rows) {
        statement(banks, opening, closing, rows);
    }

    private void statement(BankStatements target, long opening, long closing, String... rows) {
        long credits = 0, debits = 0;
        for (String row : rows) {
            var fields = row.split(",");
            if (!fields[5].equals("BOOKED")) continue;
            if (fields[2].equals("CREDIT")) credits = Math.addExact(credits, Long.parseLong(fields[3]));
            else debits = Math.addExact(debits, Long.parseLong(fields[3]));
        }
        String csv = "H,BANK_STATEMENT,SIM-CAD-001,2026-10-23,2026-10-23,1,CAD\r\n"
                + String.join("\r\n", rows) + "\r\nT," + rows.length + "," + credits + "," + debits
                + "," + opening + "," + closing + "\r\n";
        assertThat(target.importBankStatement("fixture", csv.getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo("ACCEPTED");
    }

    private String credit(String id, long amount, String reference) {
        return "D," + id + ",CREDIT," + amount + ",CAD,BOOKED," + reference + ",2026-10-23,2026-10-23,fixture";
    }

    private void split() {
        statement(0, 9680, credit("T5", 5000, "po_b1"), credit("T6", 4680, "po_b1"));
    }

    private void failPayout() {
        f.tx.executeWithoutResult(tx -> f.batches.process(new PayoutEvidence(
                "evt_po_b1",
                "inbox_event",
                "evt_po_b1",
                "payout.failed",
                "po_b1",
                9680,
                "cad",
                true,
                "standard",
                "completed",
                f.clock.instant(),
                f.clock.instant(),
                null)));
    }

    private void balances(long receivable, long transit, long cash, long unapplied) {
        assertThat(f.jdbc.sql("SELECT balance FROM ledger.account ORDER BY code")
                        .query(Long.class)
                        .list())
                .containsExactly(receivable, transit, cash, 0L, 9680L, 0L, 0L, unapplied);
        f.invariants();
        assertThat(f.number("""
                SELECT balance-(SELECT coalesce(sum(amount),0) FROM (
                    SELECT DISTINCT ON(bank_txn_id) amount,match_status FROM recon.bank_line
                    ORDER BY bank_txn_id,version DESC) l WHERE match_status='UNAPPLIED')
                FROM ledger.account WHERE code=2105
                """)).as("INV-12").isZero();
    }

    @Test
    void ac_6_10_splitApplicationPostsOnceAndClosesIssues() throws Exception {
        split();
        bankBalanceMatches();
        balances(0, 9680, 9680, 9680);
        rejected(request(false, "T5"), 409, "APPLICATION_AMOUNT_MISMATCH");
        String id = success(request(false, "T6", "T5"));
        balances(0, 0, 9680, 0);
        bankBalanceMatches();
        assertThat(f.count("ledger.correction")).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT role FROM ledger.correction_target")
                        .query(String.class)
                        .list())
                .containsExactly("APPLIED", "APPLIED");
        assertThat(f.jdbc.sql("""
                SELECT p.account_code||':'||p.side||':'||p.amount FROM ledger.posting p
                JOIN ledger.journal_entry e ON e.id=p.entry_id WHERE e.correction_id=?
                """).param(id).query(String.class).list())
                .containsExactlyInAnyOrder("2105:D:9680", "1102:C:9680");
        assertThat(f.jdbc.sql("SELECT match_status||':'||matched_batch_id FROM recon.bank_line")
                        .query(String.class)
                        .list())
                .containsExactly("APPLIED:po_b1", "APPLIED:po_b1");
        assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo("BANKED");
        assertThat(
                        text(
                                "SELECT evidence_id FROM core.batch_transition WHERE to_status='BANKED' AND evidence_type='correction'"))
                .isEqualTo(id);
        assertThat(f.jdbc.sql("SELECT status||':'||resolution_correction_id FROM recon.issue")
                        .query(String.class)
                        .list())
                .containsOnly("RESOLVED:" + id)
                .hasSize(2);
        var before = snapshot();
        var duplicate = submit(request(false, "T5", "T6"));
        assertThat(duplicate.getStatus()).isEqualTo(409);
        var error = f.mapper.readTree(duplicate.getContentAsString());
        assertThat(error.path("code").asString()).isEqualTo("BATCH_NOT_AWAITING_DEPOSIT");
        assertThat(error.path("correctionId").asString()).isEqualTo(id);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_11_restoreReversesFailureAndReturnsToS3() throws Exception {
        failPayout();
        statement(0, 9680, credit("T1", 9680, "po_b1"));
        balances(9680, 0, 9680, 9680);
        bankBalanceMatches();
        String id = success(request(true, "T1"));
        balances(0, 0, 9680, 0);
        bankBalanceMatches();
        assertThat(f.jdbc.sql("SELECT role FROM ledger.correction_target")
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder("REVERSED", "APPLIED");
        assertThat(f.jdbc.sql("""
                SELECT e.entry_type||':'||p.account_code||':'||p.side||':'||p.amount
                FROM ledger.posting p JOIN ledger.journal_entry e ON e.id=p.entry_id WHERE e.correction_id=?
                """).param(id).query(String.class).list())
                .containsExactlyInAnyOrder(
                        "REVERSAL:1102:D:9680",
                        "REVERSAL:1101:C:9680",
                        "CORRECTION:2105:D:9680",
                        "CORRECTION:1102:C:9680");
        assertThat(text("SELECT status FROM core.settlement_batch")).isEqualTo("BANKED");
        assertThat(text("SELECT status||':'||resolution_correction_id FROM recon.issue"))
                .isEqualTo("RESOLVED:" + id);
        rejected(request(true, "T1"), 409, "BATCH_NOT_PAYOUT_FAILED");
    }

    @Test
    void ac_6_10_invalidBankListsReasonsAndMissingEvidenceNeverWrite() throws Exception {
        split();
        for (var ids : List.of(
                List.of(),
                List.of("T5", "T5"),
                java.util.stream.IntStream.range(0, 11).mapToObj(i -> "T" + i).toList(),
                List.of(""))) {
            var body = request(false, "T5", "T6");
            body.set("bankTxnIds", f.mapper.valueToTree(ids));
            rejected(body, 400, "INVALID_REQUEST");
        }
        var body = request(false, "T5", "T6");
        body.remove("bankTxnIds");
        rejected(body, 400, "INVALID_REQUEST");
        for (String reason : List.of("CAPTURE_RECORDED_IN_ERROR", "CAPTURE_AMOUNT_ERROR", "PAYOUT_FAILURE_DISPROVED"))
            rejected(request(false, "T5", "T6").put("reasonCode", reason), 400, "INVALID_REQUEST");
        body = request(false, "T5", "T6");
        body.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", "bank_txn", "id", "missing"))));
        rejected(body, 400, "EVIDENCE_NOT_FOUND");
        rejected(request(false, "T5", "T6").put("batchId", "po_missing"), 404, "BATCH_NOT_FOUND");
        success(request(false, "T5", "T6").put("reasonCode", "OTHER"));
    }

    @Test
    void ac_6_10_wrongReferenceAndReturnedLineNeverApply() throws Exception {
        split();
        f.payment("ch_other", 10000);
        f.batch("po_other", "ch_other", 10000, 320);
        rejected(request(false, "T5", "T6").put("batchId", "po_other"), 409, "BANK_LINE_REFERENCE_MISMATCH");
        statement(9680, 4680, credit("return", 5000, "T5").replace(",CREDIT,", ",DEBIT,"));
        rejected(request(false, "T5", "T6"), 409, "BANK_LINE_NOT_UNAPPLIED");
        assertThat(text("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='T5'"))
                .isEqualTo("RETURNED");
        f.invariants();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"missing", "pending", "debit", "foreign", "waiting", "matched"})
    void ac_6_10_ineligibleCurrentBankVersionIsRejected(String kind) throws Exception {
        split();
        String row = credit("bad", 9680, "po_b1");
        switch (kind) {
            case "pending" -> row = row.replace("BOOKED", "PENDING");
            case "debit" -> row = row.replace("CREDIT", "DEBIT");
            case "foreign" -> row = row.replace("CAD", "USD");
            case "waiting" -> row = row.replace("po_b1", "po_later");
            case "matched" -> {
                f.payment("ch_other", 10000);
                f.batch("po_other", "ch_other", 10000, 320);
                row = row.replace("po_b1", "po_other");
            }
            default -> {}
        }
        if (!kind.equals("missing"))
            statement(kind.equals("debit") ? 9680 : 0, kind.equals("pending") || kind.equals("debit") ? 0 : 9680, row);
        var body = request(false, "T5", "T6");
        body.set("bankTxnIds", f.mapper.valueToTree(List.of("bad")));
        rejected(body, 409, "BANK_LINE_NOT_UNAPPLIED");
    }

    @Test
    void ac_6_10_pendingThenBookedUsesOnlyLatestVersion() throws Exception {
        statement(0, 0, credit("T5", 5000, "po_b1").replace("BOOKED", "PENDING"));
        split();
        success(request(false, "T5", "T6"));
        assertThat(f.jdbc.sql("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='T5' ORDER BY version")
                        .query(String.class)
                        .list())
                .containsExactly("IGNORED_PENDING", "APPLIED");
        balances(0, 0, 9680, 0);
    }

    @Test
    void ac_6_11_invalidRestoreParametersAndPreconditionsNeverWrite() throws Exception {
        split();
        rejected(request(true, "T5"), 409, "BATCH_NOT_PAYOUT_FAILED");
        failPayout();
        rejected(request(false, "T5", "T6"), 409, "BATCH_NOT_AWAITING_DEPOSIT");
        rejected(request(true, "T5"), 409, "APPLICATION_AMOUNT_MISMATCH");
        rejected(request(true, "T5").put("reasonCode", "SPLIT_DEPOSIT"), 400, "INVALID_REQUEST");
        var body = request(true, "T5");
        body.remove("bankTxnId");
        rejected(body, 400, "INVALID_REQUEST");
        body.set("bankTxnIds", f.mapper.valueToTree(List.of("T5", "T6")));
        rejected(body, 400, "INVALID_REQUEST");
        body.put("bankTxnId", "T5");
        rejected(body, 400, "INVALID_REQUEST");
        rejected(request(true, "T5").put("bankTxnId", "missing"), 409, "BANK_LINE_NOT_UNAPPLIED");
        statement(9680, 0, credit("return", 9680, "other").replace("CREDIT", "DEBIT"));
        rejected(request(true, "T5").put("bankTxnId", "return"), 409, "BANK_LINE_NOT_UNAPPLIED");
        statement(0, 9680, credit("wrong", 9680, "unknown"));
        rejected(request(true, "wrong"), 409, "BANK_LINE_REFERENCE_MISMATCH");
    }

    @Test
    void ac_6_11_exampleHAlreadyReturnedFailureCannotBeRestored() throws Exception {
        failPayout();
        var resend = f.archivedBatch("po_b2", List.of(f.line("txn_return", "payout_failure", "po_b1", 9680, 0)));
        f.tx.executeWithoutResult(tx -> f.batches.process(resend));
        assertThat(text("SELECT status FROM core.settlement_batch WHERE batch_id='po_b2'"))
                .isEqualTo("CONFIRMED");
        statement(0, 9680, credit("T1", 9680, "po_b1"));
        rejected(request(true, "T1"), 409, "FAILED_PAYOUT_ALREADY_RETURNED");
        balances(0, 9680, 9680, 9680);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ac_6_10_ac_6_11_laterDepositUsesSecondPaymentRule(boolean restore) throws Exception {
        if (restore) {
            failPayout();
            statement(0, 9680, credit("T1", 9680, "po_b1"));
            success(request(true, "T1"));
        } else {
            split();
            success(request(false, "T5", "T6"));
        }
        statement(9680, 19360, credit("second", 9680, "po_b1"));
        assertThat(text("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='second'"))
                .isEqualTo("UNAPPLIED");
        assertThat(text("SELECT category FROM recon.issue WHERE subject_id='second'"))
                .isEqualTo("UNMATCHED_BANK_LINE");
        balances(0, 0, 19360, 9680);
    }

    @Test
    void ac_6_11_returnLineAfterRestoreIsHeldWithoutPosting() throws Exception {
        failPayout();
        statement(0, 9680, credit("T1", 9680, "po_b1"));
        success(request(true, "T1").put("reasonCode", "OTHER"));
        long entries = f.count("ledger.journal_entry");
        var resend = f.archivedBatch("po_b2", List.of(f.line("txn_return", "payout_failure", "po_b1", 9680, 0)));
        f.tx.executeWithoutResult(tx -> f.batches.process(resend));
        assertThat(text("SELECT status||':'||hold_reason FROM core.settlement_batch WHERE batch_id='po_b2'"))
                .isEqualTo("HELD:UNSUPPORTED_LINE");
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        balances(0, 0, 9680, 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ac_6_10_ac_6_11_ledgerFaultRollsBackEveryWrite(boolean restore) {
        if (restore) {
            failPayout();
            statement(0, 9680, credit("T1", 9680, "po_b1"));
        } else split();
        var before = snapshot();
        mvc = controller(f.ledger, () -> {
            throw new IllegalStateException("injected after ledger writes");
        });
        assertThatThrownBy(() -> submit(restore ? request(true, "T1") : request(false, "T5", "T6")))
                .hasRootCauseMessage("injected after ledger writes");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    private void bankBalanceMatches() {
        var cutoff = java.time.Instant.parse("2026-10-24T04:00:00Z");
        var rule = new dev.ledgerlab.reconciliation.internal.BankRule(
                new dev.ledgerlab.reconciliation.internal.BankEvidence(f.jdbc),
                new BusinessCalendar(),
                new dev.ledgerlab.reconciliation.internal.AgingPolicy(2, 2, 2, java.time.Duration.ofHours(1), 2, 5, 3),
                new dev.ledgerlab.reconciliation.internal.ReconJson(f.mapper),
                f.jdbc);
        var inputs = new java.util.HashMap<String, String>();
        f.jdbc.sql("SELECT id,sha256 FROM io.imported_file WHERE kind='BANK_STATEMENT'")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list()
                .forEach(e -> inputs.put(e.getKey(), e.getValue()));
        var context = new dev.ledgerlab.reconciliation.internal.ReconRule.Context(
                new dev.ledgerlab.reconciliation.internal.RunRequest(
                        dev.ledgerlab.reconciliation.internal.RunRequest.Kind.BANK,
                        cutoff,
                        cutoff.plusSeconds(1),
                        "p4-v1",
                        List.copyOf(inputs.keySet()),
                        true),
                inputs);
        f.tx.executeWithoutResult(tx -> {
            assertThat(rule.evaluate(context))
                    .noneMatch(finding -> finding.category().equals("BANK_BALANCE_MISMATCH"));
            assertThat(rule.checkedScope(context).keySet())
                    .anyMatch(key -> key.category().equals("BANK_BALANCE_MISMATCH")
                            && key.subjectType().equals("bank_statement"));
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void ac_6_10_ac_6_11_applicationAndBankReturnHaveOnlyOneWinner(boolean restore, boolean correctionFirst)
            throws Exception {
        if (restore) {
            failPayout();
            statement(0, 9680, credit("T1", 9680, "po_b1"));
        } else split();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var correctionLedger = orderedLedger(correctionFirst, barrier);
        var returnLedger = orderedLedger(!correctionFirst, barrier);
        mvc = controller(correctionLedger, () -> {});
        var returningBank = banks(returnLedger);
        String creditId = restore ? "T1" : "T5";
        long amount = restore ? 9680 : 5000;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var correction = pool.submit(() -> submit(restore ? request(true, "T1") : request(false, "T5", "T6")));
            var returned = pool.submit(() -> statement(
                    returningBank,
                    9680,
                    9680 - amount,
                    credit("return", amount, creditId).replace("CREDIT", "DEBIT")));
            var response = correction.get(15, java.util.concurrent.TimeUnit.SECONDS);
            returned.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(correctionFirst ? 201 : 409);
            if (!correctionFirst)
                assertThat(f.mapper
                                .readTree(response.getContentAsString())
                                .path("code")
                                .asString())
                        .isEqualTo("BANK_LINE_NOT_UNAPPLIED");
        }
        assertThat(f.count("ledger.correction")
                        + f.number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE entry_type='UNAPPLIED_CASH_RETURNED'"))
                .isEqualTo(1);
        assertThat(text("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='return'"))
                .isEqualTo(correctionFirst ? "UNMATCHED_NO_POSTING" : "RETURN_POSTED");
        balances(
                correctionFirst || !restore ? 0 : 9680,
                correctionFirst || restore ? 0 : 9680,
                correctionFirst ? 9680 : 9680 - amount,
                correctionFirst ? 0 : 9680 - amount);
    }

    private Ledger orderedLedger(boolean first, java.util.concurrent.CyclicBarrier barrier) {
        return new dev.ledgerlab.testsupport.DelegatingLedger(f.ledger) {
            private final java.util.concurrent.atomic.AtomicBoolean entered =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void lockFunds() {
                if (!entered.compareAndSet(false, true)) {
                    super.lockFunds();
                    return;
                }
                if (first) super.lockFunds();
                try {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                if (!first) super.lockFunds();
            }
        };
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ac_6_10_ac_6_11_issueFailureRollsBackLedgerBusinessAndDisposition(boolean restore) {
        if (restore) {
            failPayout();
            statement(0, 9680, credit("T1", 9680, "po_b1"));
        } else split();
        var delegate = new IssueStore(f.jdbc, f.ids, f.clock, f.manager);
        var issues = new dev.ledgerlab.reconciliation.IssueDisposition() {
            @Override
            public void accept(String id, String reason) {
                delegate.accept(id, reason);
            }

            @Override
            public void resolve(String id, String reason) {
                delegate.resolve(id, reason);
            }

            @Override
            public void resolveByCorrection(String id, String correction, String note) {
                delegate.resolveByCorrection(id, correction, note);
                throw new IllegalStateException("injected after issue disposition");
            }
        };
        mvc = controller(f.ledger, () -> {}, issues);
        var before = snapshot();
        assertThatThrownBy(() -> submit(restore ? request(true, "T1") : request(false, "T5", "T6")))
                .hasRootCauseMessage("injected after issue disposition");
        assertThat(snapshot()).isEqualTo(before);
        f.invariants();
    }

    private String issue(String category, String subjectType, String subject) {
        f.tx.executeWithoutResult(tx -> f.issues.open(category, subjectType, subject, "{}", "{}", "[]"));
        return f.jdbc.sql("SELECT id FROM recon.issue WHERE category=? AND subject_type=? AND subject_id=?")
                .params(category, subjectType, subject)
                .query(String.class)
                .single();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ac_6_10_ac_6_11_closeOnlyEligibleOpenIssuesAndExposeAffectedObjects(boolean restore) throws Exception {
        if (restore) {
            failPayout();
            statement(0, 9680, credit("T1", 9680, "po_b1"));
        } else split();
        String bank = restore ? "T1" : "T5";
        String accepted = issue("UNAPPLIED_CASH_OPEN", "bank_txn", bank);
        new IssueStore(f.jdbc, f.ids, f.clock, f.manager).accept(accepted, "verified exception");
        var closed = new java.util.ArrayList<String>();
        for (String category : restore
                ? List.of("BATCH_STATE_CONFLICT")
                : List.of("DEPOSIT_MISSING", "DEPOSIT_NOT_RECEIVED", "TIMING_DIFFERENCE"))
            closed.add(issue(category, "batch", "po_b1"));
        String keep = issue("BATCH_VERSION_CONFLICT", "batch", "po_b1");
        String otherBatch = issue("BATCH_STATE_CONFLICT", "batch", "po_other");
        String explicit = issue("UNMATCHED_BANK_LINE", "bank_txn", "explicit");
        var body = restore ? request(true, "T1") : request(false, "T5", "T6");
        body.set(
                "evidence",
                f.mapper.valueToTree(List.of(
                        Map.of("type", "bank_txn", "id", bank),
                        Map.of("type", "issue", "id", explicit),
                        Map.of("type", "issue", "id", otherBatch))));
        String correction = success(body);
        closed.add(explicit);
        for (String id : closed)
            assertThat(f.jdbc.sql("SELECT status||':'||resolution_correction_id FROM recon.issue WHERE id=?")
                            .param(id)
                            .query(String.class)
                            .single())
                    .isEqualTo("RESOLVED:" + correction);
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE id IN (?,?)")
                        .params(keep, otherBatch)
                        .query(String.class)
                        .list())
                .containsOnly("OPEN");
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE id=?")
                        .param(accepted)
                        .query(String.class)
                        .single())
                .isEqualTo("ACCEPTED");
        assertThat(
                        f.number(
                                "SELECT count(*) FROM recon.issue_transition WHERE to_status='RESOLVED' AND reason<>'OPERATOR'"))
                .isZero();
        var detail = new CorrectionQueries(f.db.readonly(), f.mapper).detail(correction);
        assertThat(detail.affected().payments()).isEmpty();
        assertThat(detail.affected().batches()).containsExactly("po_b1");
        assertThat(detail.affected().bankLines())
                .containsExactlyElementsOf(restore ? List.of("T1") : List.of("T5", "T6"));
        assertThat(detail.affected().issues()).containsAll(closed).doesNotContain(keep, otherBatch, accepted);
    }
}
