package dev.ledgerlab.corrections.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionRequest;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.payouts.PayoutCorrections;
import dev.ledgerlab.payouts.Payouts;
import dev.ledgerlab.reconciliation.internal.IssueStore;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.SettlementCorrections;
import dev.ledgerlab.shared.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.ObjectNode;

class PayoutCorrectionHttpTest {
    private CorrectionFixture f;
    private MockMvc mvc;
    private BankStatements bank;
    private String payout;
    private String payment;
    private int version;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        payment = f.s1();
        f.batch("po_b1", "ch_p1", 10000, 320);
        f.bankWaiting("po_b1", 9680);
        f.clock.set(Instant.parse("2026-11-03T16:00:00Z"));
        payout = payouts().reserve(9680, "T07", "fixture").payoutId();
        bank = new BankStatements(
                f.jdbc,
                f.ledger,
                f.issues,
                f.clock,
                f.ids,
                new BusinessCalendar(),
                f.mapper,
                f.manager,
                2,
                new dev.ledgerlab.payouts.internal.JdbcPayoutBankEvidence(
                        f.jdbc, f.ledger, f.queue, new BusinessCalendar(), f.clock));
        mvc = controller(() -> {});
    }

    private Payouts payouts() {
        return dev.ledgerlab.payouts.internal.PayoutCorrectionFixture.payouts(f);
    }

    private MockMvc controller(Runnable fault) {
        return controller(fault, new IssueStore(f.jdbc, f.ids, f.clock, f.manager));
    }

    private MockMvc controller(Runnable fault, dev.ledgerlab.reconciliation.IssueDisposition issues) {
        var service = new JdbcCorrections(
                f.jdbc,
                f.ledger,
                new PaymentCorrections(f.jdbc, f.ledger),
                issues,
                f.rechecks,
                new SettlementCorrections(f.jdbc, f.ledger),
                new PayoutCorrections(f.jdbc, f.ledger, new BusinessCalendar(), f.clock),
                f.clock,
                f.mapper,
                f.manager,
                fault);
        return MockMvcBuilders.standaloneSetup(
                        new CorrectionController(service, new CorrectionQueries(f.db.readonly(), f.mapper), f.mapper))
                .build();
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private void fail() {
        f.tx.executeWithoutResult(tx -> {
            f.ledger.lockFunds();
            f.jdbc.sql("UPDATE core.payout SET status='FAILED',failure_reason='REJECTED' WHERE id=?")
                    .param(payout)
                    .update();
            f.ledger.post(new EntryRequest(
                    EntryType.PAYOUT_FAILED,
                    "payout:" + payout + ":failed",
                    f.clock.instant(),
                    SubjectType.PAYOUT,
                    payout,
                    List.of(
                            PostingLine.debit(Account.PAYOUT_IN_TRANSIT, 9680),
                            PostingLine.credit(Account.MERCHANT_AVAILABLE, 9680))));
        });
    }

    private String row(String id, String direction, long amount, String currency, String status, String reference) {
        return "D," + id + "," + direction + "," + amount + "," + currency + "," + status + "," + reference
                + ",2026-11-03,2026-11-03,RETURN ACCOUNT_CLOSED";
    }

    private String debit(String id, long amount) {
        return row(id, "DEBIT", amount, "CAD", "BOOKED", payout);
    }

    private void statement(String... rows) {
        long credits = 0, debits = 0;
        for (var row : rows) {
            var fields = row.split(",");
            if (!fields[5].equals("BOOKED")) continue;
            if (fields[2].equals("CREDIT")) credits = Math.addExact(credits, Long.parseLong(fields[3]));
            else debits = Math.addExact(debits, Long.parseLong(fields[3]));
        }
        String csv = "H,BANK_STATEMENT,SIM-CAD-001,2026-11-03,2026-11-03," + (++version) + ",CAD\r\n"
                + String.join("\r\n", rows) + "\r\nT," + rows.length + "," + credits + "," + debits
                + ",100000," + Math.subtractExact(Math.addExact(100000, credits), debits) + "\r\n";
        assertThat(bank.importBankStatement("T07.csv", csv.getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo("ACCEPTED");
    }

    private ObjectNode request(String bankId) {
        return (ObjectNode) f.mapper.valueToTree(Map.of(
                "type",
                "CONFIRM_FAILED_PAYOUT_PAID",
                "payoutId",
                payout,
                "bankTxnId",
                bankId,
                "reasonCode",
                "PAYOUT_FAILURE_DISPROVED",
                "reasonNote",
                "Verified synthetic evidence",
                "requestedBy",
                "operator",
                "evidence",
                List.of(Map.of("type", "bank_txn", "id", bankId))));
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
        String id = f.mapper
                .readTree(response.getContentAsString())
                .path("correction")
                .path("id")
                .asString();
        var detail = mvc.perform(get("/api/corrections/" + id)).andReturn().getResponse();
        assertThat(detail.getStatus()).isEqualTo(200);
        assertThat(f.mapper.readTree(detail.getContentAsString()))
                .isEqualTo(f.mapper.readTree(response.getContentAsString()));
        assertThat(f.mapper
                        .readTree(detail.getContentAsString())
                        .path("affected")
                        .path("payouts")
                        .get(0)
                        .asString())
                .isEqualTo(payout);
        assertThat(f.mapper
                        .readTree(detail.getContentAsString())
                        .path("affected")
                        .path("bankLines")
                        .get(0)
                        .asString())
                .isEqualTo(body.path("bankTxnId").asString());
        return id;
    }

    private List<String> snapshot() {
        return f
                .jdbc
                .sql(
                        "SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('core','ledger','recon','io') ORDER BY 1")
                .query(String.class)
                .list()
                .stream()
                .map(table -> text(
                        "SELECT coalesce(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) row FROM "
                                + table + " t) data"))
                .toList();
    }

    private String text(String sql) {
        return f.jdbc.sql(sql).query(String.class).single();
    }

    private MockHttpServletResponse rejected(ObjectNode body, int status, String code) throws Exception {
        var before = snapshot();
        var response = submit(body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(f.mapper.readTree(response.getContentAsString()).path("code").asString())
                .isEqualTo(code);
        assertThat(snapshot()).isEqualTo(before);
        return response;
    }

    @Test
    void ac_6_15_correctsAtomicallyExposesDetailAndAllowsNormalReturn() throws Exception {
        fail();
        statement(debit("paid", 9680));
        String original = text("SELECT id FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'");
        String id = success(request("paid"));
        assertThat(f.jdbc.sql(
                                "SELECT e.entry_type||':'||p.account_code||':'||p.side||':'||p.amount FROM ledger.posting p JOIN ledger.journal_entry e ON e.id=p.entry_id WHERE e.correction_id=?")
                        .param(id)
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder(
                        "REVERSAL:2102:D:9680",
                        "REVERSAL:2104:C:9680",
                        "CORRECTION:2104:D:9680",
                        "CORRECTION:1103:C:9680");
        assertThat(text("SELECT reverses_entry_id FROM ledger.journal_entry WHERE entry_type='REVERSAL'"))
                .isEqualTo(original);
        assertThat(text("SELECT role FROM ledger.correction_target")).isEqualTo("REVERSED");
        assertThat(text("SELECT status||':'||bank_txn_id FROM core.payout")).isEqualTo("PAID:paid");
        assertThat(text("SELECT match_status||':'||matched_payout_id FROM recon.bank_line WHERE bank_txn_id='paid'"))
                .isEqualTo("PAYOUT_PAID:" + payout);
        assertThat(
                        text(
                                "SELECT from_status||':'||to_status||':'||evidence_type||':'||evidence_id FROM core.payout_status_history WHERE evidence_type='correction'"))
                .isEqualTo("FAILED:PAID:correction:" + id);
        assertThat(f.jdbc.sql("SELECT paid_at FROM core.payout")
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(Instant.parse("2026-11-03T05:00:00Z"));
        assertThat(f.jdbc.sql("SELECT status||':'||resolution_correction_id FROM recon.issue WHERE subject_id IN (?,?)")
                        .params(payout, "paid")
                        .query(String.class)
                        .list())
                .isNotEmpty()
                .containsOnly("RESOLVED:" + id);
        assertThat(f.number("SELECT count(*) FROM ledger.account WHERE balance<>0"))
                .isZero();
        var duplicate = rejected(request("paid"), 409, "PAYOUT_NOT_FAILED");
        assertThat(f.mapper
                        .readTree(duplicate.getContentAsString())
                        .path("correctionId")
                        .asString())
                .isEqualTo(id);
        statement(debit("paid", 9680), row("returned", "CREDIT", 9680, "CAD", "BOOKED", payout));
        assertThat(text("SELECT status FROM core.payout")).isEqualTo("RETURNED");
        assertThat(
                        text(
                                "SELECT entry_type FROM ledger.journal_entry WHERE source_key='bank_txn:returned' AND reverses_entry_id IS NULL"))
                .isEqualTo("PAYOUT_RETURNED");
        assertThat(f.number("SELECT balance FROM ledger.account WHERE code=1103"))
                .isEqualTo(9680);
        assertThat(f.number("SELECT balance FROM ledger.account WHERE code=2102"))
                .isEqualTo(9680);
        f.invariants();
    }

    @Test
    void ac_6_15_missingPayoutAndRequestValidationHaveNoWrites() throws Exception {
        fail();
        statement(debit("paid", 9680));
        rejected(request("paid").put("payoutId", "mpo_missing"), 404, "PAYOUT_NOT_FOUND");
        for (String field : List.of("payoutId", "bankTxnId", "reasonCode", "reasonNote", "requestedBy", "evidence")) {
            var body = request("paid");
            body.remove(field);
            rejected(body, 400, "INVALID_REQUEST");
        }
        rejected(request("paid").put("reasonCode", "SPLIT_DEPOSIT"), 400, "INVALID_REQUEST");
        var body = request("paid");
        body.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", "bank_txn", "id", "bt_wait"))));
        rejected(body, 400, "INVALID_REQUEST");
        rejected(request("missing"), 400, "EVIDENCE_NOT_FOUND");
        body = request("paid");
        body.set("bankTxnIds", f.mapper.valueToTree(List.of("paid")));
        rejected(body, 400, "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED", "PAID", "RETURNED"})
    void ac_6_15_nonFailedStatePrecedesBankValidation(String status) throws Exception {
        f.jdbc.sql(
                        "UPDATE core.payout SET status=?,bank_txn_id='fixture',return_bank_txn_id='fixture_return' WHERE id=?")
                .params(status, payout)
                .update();
        rejected(request("bt_wait"), 409, "PAYOUT_NOT_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CREDIT", "USD", "REFERENCE", "AMOUNT", "MATCHED"})
    void ac_6_15_everyBankPreconditionHasNoWrites(String variant) throws Exception {
        fail();
        statement(row(
                "bad",
                variant.equals("CREDIT") ? "CREDIT" : "DEBIT",
                variant.equals("AMOUNT") ? 9679 : 9680,
                variant.equals("USD") ? "USD" : "CAD",
                variant.equals("PENDING") ? "PENDING" : "BOOKED",
                variant.equals("REFERENCE") ? "mpo_other" : payout));
        if (variant.equals("MATCHED"))
            f.jdbc.sql(
                            "UPDATE recon.bank_line SET match_status='PAYOUT_PAID',matched_payout_id=? WHERE bank_txn_id='bad'")
                    .param(payout)
                    .update();
        rejected(
                request("bad"),
                409,
                variant.equals("AMOUNT") ? "APPLICATION_AMOUNT_MISMATCH" : "BANK_LINE_NOT_PAYOUT_DEBIT");
    }

    @Test
    void ac_6_15_latestBookedVersionIsUsed() throws Exception {
        fail();
        statement(row("paid", "DEBIT", 9680, "CAD", "PENDING", payout));
        rejected(request("paid"), 409, "BANK_LINE_NOT_PAYOUT_DEBIT");
        statement(debit("paid", 9680));
        success(request("paid"));
        assertThat(f.jdbc.sql("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='paid' ORDER BY version")
                        .query(String.class)
                        .list())
                .containsExactly("IGNORED_PENDING", "PAYOUT_PAID");
    }

    @Test
    void ac_6_15_negativeCashRollsBackReversalAndAllAuditRows() throws Exception {
        fail();
        String second = payouts().reserve(9680, "second", "fixture").payoutId();
        statement(row("second_paid", "DEBIT", 9680, "CAD", "BOOKED", second), debit("paid", 9680));
        rejected(request("paid"), 409, "CASH_WOULD_GO_NEGATIVE");
        f.invariants();
    }

    @Test
    void ac_6_15_failureAfterLedgerWritesRollsBackEverything() {
        fail();
        statement(debit("paid", 9680));
        mvc = controller(() -> {
            throw new IllegalStateException("T07 injected failure");
        });
        var before = snapshot();
        assertThatThrownBy(() -> submit(request("paid"))).hasRootCauseMessage("T07 injected failure");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_15_debtIsVisibleAndCorrectionDoesNotReleaseRefundQueue() throws Exception {
        fail();
        payouts().reserve(9000, "reserved_again", "fixture");
        f.accept(payment, 1000);
        statement(debit("paid", 9680));
        long commands = f.count("io.outbound_command");
        success(request("paid").put("reasonCode", "OTHER"));
        dev.ledgerlab.investigation.internal.PayoutCorrectionPageAssertions.assertDebt(
                f.db.readonly(), f.clock, f.mapper, 10000);
        assertThat(f.count("io.outbound_command")).isEqualTo(commands);
        assertThat(text("SELECT status FROM core.refund")).isEqualTo("AWAITING_FUNDS");
        f.invariants();
    }

    @Test
    void ac_6_15_databaseCashConstraintIsTranslatedAndRollsBackEverything() throws Exception {
        fail();
        statement(debit("paid", 9680));
        mvc = controller(() -> f.jdbc.sql("UPDATE ledger.account SET balance=-1 WHERE code=1103")
                .update());
        rejected(request("paid"), 409, "CASH_WOULD_GO_NEGATIVE");
    }

    @Test
    void ac_6_15_alreadyReversedPreconditionIncludesOriginalCorrection() throws Exception {
        fail();
        statement(debit("paid", 9680));
        String entry = text("SELECT id FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'");
        // Ledger-only fixture reaches the final precondition without the earlier PAID-state guard.
        String prior = f.tx.execute(tx -> {
            f.ledger.lockFunds();
            return f.ledger
                    .recordCorrection(new CorrectionRequest(
                            CorrectionType.CONFIRM_FAILED_PAYOUT_PAID,
                            CorrectionReason.OTHER,
                            "fixture",
                            "[{\"type\":\"bank_txn\",\"id\":\"paid\"}]",
                            "{}",
                            SubjectType.PAYOUT,
                            payout,
                            "operator",
                            List.of(entry),
                            List.of(),
                            List.of(),
                            f.clock.instant()))
                    .correctionId();
        });
        var response = rejected(request("paid"), 409, "ALREADY_REVERSED");
        assertThat(f.mapper
                        .readTree(response.getContentAsString())
                        .path("correctionId")
                        .asString())
                .isEqualTo(prior);
    }

    @Test
    void ac_6_15_concurrentDuplicateCommitsOnlyOnce() throws Exception {
        fail();
        statement(debit("paid", 9680));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<MockHttpServletResponse> task = () -> {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return submit(request("paid"));
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            var responses = List.of(
                    first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
            String id = text("SELECT id FROM ledger.correction");
            var conflict = responses.stream()
                    .filter(r -> r.getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat(f.mapper
                            .readTree(conflict.getContentAsString())
                            .path("correctionId")
                            .asString())
                    .isEqualTo(id);
        }
        assertThat(f.count("ledger.correction_target")).isEqualTo(1);
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE correction_id IS NOT NULL"))
                .isEqualTo(2);
        f.invariants();
    }

    private void openIssue(String category, String subjectType, String subjectId) {
        f.tx.executeWithoutResult(tx -> f.issues.open(category, subjectType, subjectId, "{}", "{}", "[]"));
    }

    private String issueId(String category, String subjectId) {
        return f.jdbc.sql("SELECT id FROM recon.issue WHERE category=? AND subject_id=?")
                .params(category, subjectId)
                .query(String.class)
                .single();
    }

    @Test
    void ac_6_15_issueClosureHonorsCategoriesExplicitEvidenceAndClosedStates() throws Exception {
        fail();
        statement(debit("paid", 9680));
        for (String subject : List.of("payout", "bank_txn"))
            for (String category : List.of("PAYOUT_STATE_CONFLICT", "UNMATCHED_BANK_LINE"))
                openIssue(category, subject, subject.equals("payout") ? payout : "paid");
        openIssue("PAYOUT_UNRESOLVED", "payout", payout);
        openIssue("UNMATCHED_BANK_LINE", "bank_txn", "unrelated");
        openIssue("BATCH_STATE_CONFLICT", "batch", "po_b1");
        String accepted = issueId("UNMATCHED_BANK_LINE", payout);
        new IssueStore(f.jdbc, f.ids, f.clock, f.manager).accept(accepted, "Already reviewed");
        String unrelated = issueId("UNMATCHED_BANK_LINE", "unrelated");
        String batch = issueId("BATCH_STATE_CONFLICT", "po_b1");
        var body = request("paid");
        body.set(
                "evidence",
                f.mapper.valueToTree(List.of(
                        Map.of("type", "bank_txn", "id", "paid"),
                        Map.of("type", "issue", "id", unrelated),
                        Map.of("type", "issue", "id", batch))));
        String id = success(body);
        assertThat(f.number("SELECT count(*) FROM recon.issue WHERE status='RESOLVED' AND resolution_correction_id='"
                        + id + "'"))
                .isEqualTo(4);
        assertThat(text("SELECT status FROM recon.issue WHERE id='" + accepted + "'"))
                .isEqualTo("ACCEPTED");
        assertThat(text("SELECT status FROM recon.issue WHERE id='" + batch + "'"))
                .isEqualTo("OPEN");
        assertThat(text("SELECT status FROM recon.issue WHERE category='PAYOUT_UNRESOLVED'"))
                .isEqualTo("OPEN");
        assertThat(f.number(
                        "SELECT count(*) FROM recon.issue_transition WHERE to_status='RESOLVED' AND reason='OPERATOR'"))
                .isEqualTo(4);
    }

    @Test
    void ac_6_15_issueDispositionFailureRollsBackLedgerStateMatchAndHistory() {
        fail();
        statement(debit("paid", 9680));
        var delegate = new IssueStore(f.jdbc, f.ids, f.clock, f.manager);
        mvc = controller(() -> {}, new dev.ledgerlab.reconciliation.IssueDisposition() {
            public void accept(String id, String reason) {
                delegate.accept(id, reason);
            }

            public void resolve(String id, String note) {
                delegate.resolve(id, note);
            }

            public void resolveByCorrection(String id, String correctionId, String note) {
                delegate.resolveByCorrection(id, correctionId, note);
                assertThat(text("SELECT status FROM core.payout")).isEqualTo("PAID");
                assertThat(text("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='paid'"))
                        .isEqualTo("PAYOUT_PAID");
                throw new IllegalStateException("T07 issue failure");
            }
        });
        var before = snapshot();
        assertThatThrownBy(() -> submit(request("paid"))).hasRootCauseMessage("T07 issue failure");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void ac_6_15_otherCorrectionTypesCannotReversePayoutFailure() throws Exception {
        fail();
        statement(debit("paid", 9680));
        String entry = text("SELECT id FROM ledger.journal_entry WHERE entry_type='PAYOUT_FAILED'");
        for (var type : List.of("VOID_CAPTURE", "RESTATE_CAPTURE_AMOUNT")) {
            var body = request("paid");
            body.remove("bankTxnId");
            body.remove("payoutId");
            body.put("type", type)
                    .put("originalEntryId", entry)
                    .put("reasonCode", "OTHER")
                    .put("newAmount", 9000);
            body.set("evidence", f.mapper.valueToTree(List.of(Map.of("type", "inbox_event", "id", "evt_ch_p1"))));
            // Use an actual stored evidence identity from the capture fixture.
            ((ObjectNode) body.path("evidence").get(0))
                    .put("id", text("SELECT event_id FROM io.inbox_event ORDER BY event_id LIMIT 1"));
            rejected(body, 409, "ENTRY_NOT_CORRECTABLE");
        }
        for (var type : List.of(
                CorrectionType.VOID_CAPTURE,
                CorrectionType.RESTATE_CAPTURE_AMOUNT,
                CorrectionType.APPLY_UNAPPLIED_CASH,
                CorrectionType.RESTORE_FAILED_PAYOUT)) {
            var before = snapshot();
            assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> {
                        f.ledger.lockFunds();
                        f.ledger.recordCorrection(new CorrectionRequest(
                                type,
                                CorrectionReason.OTHER,
                                "fixture",
                                "[{\"type\":\"bank_txn\",\"id\":\"paid\"}]",
                                "{}",
                                SubjectType.PAYOUT,
                                payout,
                                "operator",
                                List.of(entry),
                                List.of(),
                                List.of(),
                                f.clock.instant()));
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ENTRY_NOT_CORRECTABLE");
            assertThat(snapshot()).isEqualTo(before);
        }
    }
}
