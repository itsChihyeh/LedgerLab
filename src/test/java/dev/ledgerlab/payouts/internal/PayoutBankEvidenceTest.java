package dev.ledgerlab.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.refunds.internal.RefundFixture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.shared.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayoutBankEvidenceTest {
    private RefundFixture f;
    private BankStatements bank;
    private String payout;
    private String payment;

    @BeforeEach
    void setup() {
        f = new RefundFixture();
        payment = f.s3();
        f.clock.set(Instant.parse("2026-11-03T16:00:00Z"));
        payout = new JdbcPayouts(f.jdbc, f.ledger, f.clock, f.ids, f.mapper, f.manager)
                .reserve(9680, "MP-bank", "fixture")
                .payoutId();
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
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private String row(String id, String direction, long amount, String ref, String status, String memo) {
        return "D," + id + "," + direction + "," + amount + ",CAD," + status + "," + ref + ",2026-11-03,2026-11-03,"
                + memo;
    }

    private String debit(String id, long amount) {
        return row(id, "DEBIT", amount, payout, "BOOKED", "Payout");
    }

    private String credit(String id, long amount) {
        return row(id, "CREDIT", amount, payout, "BOOKED", "RETURN ACCOUNT_CLOSED");
    }

    private String statement(int version, String... rows) {
        long credit = 0;
        long debit = 0;
        for (String row : rows) {
            String[] parts = row.split(",");
            if (!parts[5].equals("BOOKED")) continue;
            if (parts[2].equals("CREDIT")) credit = Math.addExact(credit, Long.parseLong(parts[3]));
            else debit = Math.addExact(debit, Long.parseLong(parts[3]));
        }
        String csv = "H,BANK_STATEMENT,SIM-CAD-001,2026-11-03,2026-11-03," + version + ",CAD\r\n"
                + String.join("\r\n", rows) + "\r\nT," + rows.length + "," + credit + "," + debit
                + ",100000," + Math.subtractExact(Math.addExact(100000, credit), debit) + "\r\n";
        var result = bank.importBankStatement("payout.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo("ACCEPTED");
        return result.fileId();
    }

    private String state() {
        return text("SELECT status FROM core.payout WHERE id='" + payout + "'");
    }

    private String text(String sql) {
        return f.jdbc.sql(sql).query(String.class).single();
    }

    private String bankState(String id) {
        return text("SELECT match_status FROM recon.bank_line WHERE bank_txn_id='" + id
                + "' ORDER BY version DESC LIMIT 1");
    }

    private void statusFixture(String status) {
        f.tx.executeWithoutResult(tx -> {
            f.ledger.lockFunds();
            f.jdbc.sql(
                            "UPDATE core.payout SET status=?, failure_reason=CASE WHEN ?='FAILED' THEN 'REJECTED' END WHERE id=?")
                    .params(status, status, payout)
                    .update();
            if (status.equals("FAILED"))
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

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED"})
    void ac_7_1_debitPaysEachInFlightStateAndRecordsBankHistory(String status) {
        statusFixture(status);
        statement(1, debit("paid", 9680));
        assertThat(state()).isEqualTo("PAID");
        assertThat(bankState("paid")).isEqualTo("PAYOUT_PAID");
        assertThat(f.balance(1103)).isZero();
        assertThat(f.balance(2102)).isZero();
        assertThat(f.balance(2104)).isZero();
        assertThat(f.number("SELECT count(*) FROM ledger.account WHERE balance <> 0"))
                .isZero();
        assertThat(text("SELECT matched_payout_id FROM recon.bank_line WHERE bank_txn_id='paid'"))
                .isEqualTo(payout);
        assertThat(
                        text(
                                "SELECT from_status || ':' || to_status || ':' || evidence_type || ':' || evidence_id FROM core.payout_status_history WHERE evidence_type='bank_txn'"))
                .isEqualTo(status + ":PAID:bank_txn:paid");
        assertThat(f.jdbc.sql("SELECT paid_at FROM core.payout")
                        .query(java.time.OffsetDateTime.class)
                        .single()
                        .toInstant())
                .isEqualTo(Instant.parse("2026-11-03T05:00:00Z"));
        assertThat(
                        text(
                                "SELECT entry_type FROM ledger.journal_entry WHERE source_key='bank_txn:paid' AND reverses_entry_id IS NULL"))
                .isEqualTo("PAYOUT_PAID");
        f.invariants();
    }

    @Test
    void ac_7_10_returnRestoresCashAndAvailableWithoutReversal() {
        statement(1, debit("paid", 9680), credit("returned", 9680));
        assertThat(state()).isEqualTo("RETURNED");
        assertThat(bankState("returned")).isEqualTo("PAYOUT_RETURNED");
        assertThat(f.balance(1103)).isEqualTo(9680);
        assertThat(f.balance(2102)).isEqualTo(9680);
        assertThat(f.balance(2104)).isZero();
        assertThat(text("SELECT return_reason FROM core.payout")).isEqualTo("ACCOUNT_CLOSED");
        assertThat(
                        text(
                                "SELECT entry_type FROM ledger.journal_entry WHERE source_key='bank_txn:returned' AND reverses_entry_id IS NULL"))
                .isEqualTo("PAYOUT_RETURNED");
        f.invariants();
    }

    @Test
    void ac_7_11_failedDebitCreatesPayoutConflictWithoutPosting() {
        statusFixture("FAILED");
        long entries = f.count("ledger.journal_entry");
        statement(1, debit("conflict", 9680));
        assertThat(state()).isEqualTo("FAILED");
        assertThat(bankState("conflict")).isEqualTo("UNMATCHED_NO_POSTING");
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(
                        text(
                                "SELECT subject_type || ':' || subject_id FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT'"))
                .isEqualTo("payout:" + payout);
        assertThat(text("SELECT evidence_refs::text FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT'"))
                .contains("conflict", payout);
        assertThat(text("SELECT matched_payout_id FROM recon.bank_line WHERE bank_txn_id='conflict'"))
                .isEqualTo(payout);
    }

    @Test
    void ac_7_12_ac_7_24_unknownWrongAmountAndSecondDebitNeverPost() {
        long entries = f.count("ledger.journal_entry");
        statement(1, debit("wrong", 9679), debit("unknown", 9680).replace(payout, "mpo_unknown"));
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(state()).isEqualTo("RESERVED");
        statement(2, debit("paid", 9680), debit("second", 9680));
        statement(3, credit("returned", 9680), debit("third", 9680));
        for (String id : List.of("wrong", "unknown", "second", "third")) {
            assertThat(bankState(id)).isEqualTo("UNMATCHED_NO_POSTING");
            assertThat(text("SELECT category FROM recon.issue WHERE subject_id='" + id + "'"))
                    .isEqualTo("UNMATCHED_BANK_LINE");
            String refs = text("SELECT evidence_refs::text FROM recon.issue WHERE subject_id='" + id + "'");
            if (id.equals("unknown")) assertThat(refs).doesNotContain("core.payout");
            else assertThat(refs).contains("core.payout", payout);
        }
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries + 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "FAILED", "PAID", "RETURNED", "ABSENT"})
    void ac_7_25_ineligibleCreditBecomesUnappliedWithCandidate(String status) {
        if (status.equals("PAID") || status.equals("RETURNED")) {
            statement(1, debit("paid", 9680));
            if (status.equals("RETURNED")) statement(2, credit("returned", 9680));
        } else if (!status.equals("ABSENT")) statusFixture(status);
        String ref = status.equals("ABSENT") ? "mpo_missing" : payout;
        long cash = f.balance(1103);
        String before = state();
        statement(3, row("bad-credit", "CREDIT", status.equals("PAID") ? 9679 : 9680, ref, "BOOKED", "RETURN"));
        assertThat(state()).isEqualTo(before);
        assertThat(bankState("bad-credit")).isEqualTo("UNAPPLIED");
        long amount = status.equals("PAID") ? 9679 : 9680;
        assertThat(f.balance(2105)).isEqualTo(amount);
        assertThat(f.balance(1103)).isEqualTo(cash + amount);
        assertThat(text("SELECT category FROM recon.issue"))
                .isEqualTo(status.equals("FAILED") ? "PAYOUT_STATE_CONFLICT" : "UNMATCHED_BANK_LINE");
        if (!status.equals("ABSENT"))
            assertThat(text("SELECT evidence_refs::text FROM recon.issue")).contains(payout);
        f.invariants();
    }

    @Test
    void ac_7_13_pendingAndTwoFileVersionsAndRepeatedImportsPostOnce() {
        statement(1, debit("paid", 9680).replace("BOOKED", "PENDING"));
        assertThat(state()).isEqualTo("RESERVED");
        assertThat(bankState("paid")).isEqualTo("IGNORED_PENDING");
        String file = statement(2, debit("paid", 9680));
        assertThat(statement(2, debit("paid", 9680))).isEqualTo(file);
        statement(3, debit("paid", 9680));
        statement(4, credit("returned", 9680));
        statement(5, credit("returned", 9680));
        assertThat(
                        f.number(
                                "SELECT count(*) FROM ledger.journal_entry WHERE entry_type IN ('PAYOUT_PAID','PAYOUT_RETURNED')"))
                .isEqualTo(2);
        assertThat(f.number("SELECT count(*) FROM core.payout_status_history WHERE evidence_type='bank_txn'"))
                .isEqualTo(2);
        assertThat(f.number("SELECT count(*) FROM recon.bank_line WHERE bank_txn_id='paid'"))
                .isEqualTo(2);
        f.invariants();
    }

    @Test
    void ac_7_13_concurrentSameFileImportsCommitOnePayment() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return statement(1, debit("paid", 9680));
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return statement(1, debit("paid", 9680));
            });
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type='PAYOUT_PAID'"))
                .isEqualTo(1);
        assertThat(state()).isEqualTo("PAID");
        f.invariants();
    }

    @Test
    void ac_7_1_paidResolvesOnlyOpenUnresolvedIssuesAndRollsBackAtomically() {
        f.tx.executeWithoutResult(tx -> {
            f.issues.open("PAYOUT_UNRESOLVED", "payout", payout, "{}", "{}", "[]");
            f.issues.open("PAYOUT_STATE_CONFLICT", "payout", payout, "{}", "{}", "[]");
        });
        long files = f.count("io.imported_file");
        assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> {
                    statement(1, debit("paid", 9680));
                    assertThat(
                                    text(
                                            "SELECT status FROM recon.issue WHERE category='PAYOUT_UNRESOLVED' AND detected_by='INGESTION'"))
                            .isEqualTo("RESOLVED");
                    throw new IllegalStateException("rollback paid");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback paid");
        assertThat(state()).isEqualTo("RESERVED");
        assertThat(f.count("io.imported_file")).isEqualTo(files);
        assertThat(f.count("recon.issue_transition")).isZero();
        assertThat(f.balance(2104)).isEqualTo(9680);
        String file = statement(1, debit("paid", 9680));
        assertThat(
                        text(
                                "SELECT status FROM recon.issue WHERE category='PAYOUT_UNRESOLVED' AND detected_by='INGESTION'"))
                .isEqualTo("RESOLVED");
        assertThat(text("SELECT status FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT'"))
                .isEqualTo("OPEN");
        assertThat(text("SELECT evidence_refs::text FROM recon.issue_transition"))
                .contains(file, "paid", "version");
        statement(2, debit("paid", 9680));
        assertThat(f.count("recon.issue_transition")).isEqualTo(1);
    }

    @Test
    void ac_7_13_detailReadsAllRelatedVersionsAndConflictCandidatesThroughReadonlyViews() throws Exception {
        statement(1, debit("paid", 9680).replace("BOOKED", "PENDING"));
        statement(2, debit("paid", 9680), debit("wrong", 9000), credit("returned", 9680));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new PayoutQueryController(new PayoutQueries(f.db.readonly(), f.clock)))
                .build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/payouts/" + payout))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var detail = f.mapper.readTree(response.getContentAsString());
        assertThat(detail.path("bankLines").size()).isEqualTo(4);
        assertThat(detail.path("bankLines").path(0).path("matchStatus").asString())
                .isEqualTo("IGNORED_PENDING");
        assertThat(detail.path("bankLines").path(1).path("version").asInt()).isEqualTo(2);
        assertThat(detail.path("entries").size()).isEqualTo(3);
        assertThat(detail.path("postings").size()).isEqualTo(6);
        assertThat(response.getContentAsString())
                .contains("returned", "wrong")
                .doesNotContain("requestFingerprint", "responseBody");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "EXPLAINED", "RESOLVED"})
    void ac_7_1_paidPreservesPreviouslyDisposedUnresolvedIssue(String status) {
        f.tx.executeWithoutResult(tx -> f.issues.open("PAYOUT_UNRESOLVED", "payout", payout, "{}", "{}", "[]"));
        f.jdbc.sql("UPDATE recon.issue SET status=?, explanation='previous disposition' WHERE subject_id=?")
                .params(status, payout)
                .update();
        statement(1, debit("paid", 9680));
        assertThat(text("SELECT status FROM recon.issue")).isEqualTo(status);
        assertThat(text("SELECT explanation FROM recon.issue")).isEqualTo("previous disposition");
        assertThat(f.count("recon.issue_transition")).isZero();
    }

    @Test
    void ac_7_12_prefixRoutingKeepsBatchAndUnappliedReturnsSeparateAndExcludesForeignCurrency() {
        statement(
                1,
                row("batch-second", "CREDIT", 9680, "po_b1", "BOOKED", "batch"),
                row("loose", "CREDIT", 100, "unrecognized", "BOOKED", "loose"));
        statement(
                2,
                row("loose-return", "DEBIT", 100, "loose", "BOOKED", "return"),
                debit("foreign", 9680).replace("CAD", "USD"),
                debit("paid", 9680));
        assertThat(bankState("batch-second")).isEqualTo("UNAPPLIED");
        assertThat(bankState("loose")).isEqualTo("RETURNED");
        assertThat(bankState("loose-return")).isEqualTo("RETURN_POSTED");
        assertThat(bankState("foreign")).isEqualTo("UNMATCHED_NO_POSTING");
        assertThat(bankState("paid")).isEqualTo("PAYOUT_PAID");
        assertThat(f.balance(1103)).isEqualTo(9680);
        assertThat(f.balance(2105)).isEqualTo(9680);
        f.invariants();
    }

    @ParameterizedTest
    @ValueSource(strings = {"memo", "RETURN", "RETURN account_closed", "RETURN ACCOUNT_CLOSED"})
    void ac_7_10_returnReasonFallsBackToUnknown(String memo) {
        statement(1, debit("paid", 9680), row("returned", "CREDIT", 9680, payout, "BOOKED", memo));
        assertThat(text("SELECT return_reason FROM core.payout"))
                .isEqualTo(memo.startsWith("RETURN ") ? memo.substring(7) : "UNKNOWN");
    }

    @Test
    void ac_7_1_bankEvidenceRequiresCallerTransactionAndFundsLock() {
        var evidence = new JdbcPayoutBankEvidence(f.jdbc, f.ledger, f.queue, new BusinessCalendar(), f.clock);
        var line = new dev.ledgerlab.payouts.PayoutBankEvidence.Line(
                "paid", payout, 9680, java.time.LocalDate.of(2026, 11, 3), "Payout");
        assertThatThrownBy(() -> evidence.applyDebit(line)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> evidence.applyCredit(line)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state()).isEqualTo("RESERVED");
    }

    @Test
    void ac_7_26_returnReleasesRefundAndRollbackIncludesCommandAndEvidence() {
        statement(1, debit("paid", 9680));
        String refund = f.accept(payment, 800).refundId();
        assertThat(f.status(refund)).isEqualTo("AWAITING_FUNDS");
        long files = f.count("io.imported_file");
        long history = f.count("core.payout_status_history");
        long entries = f.count("ledger.journal_entry");
        assertThatThrownBy(() -> f.tx.executeWithoutResult(tx -> {
                    statement(2, credit("returned", 9680));
                    assertThat(f.status(refund)).isEqualTo("READY");
                    assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND'"))
                            .isEqualTo(1);
                    throw new IllegalStateException("rollback return");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback return");
        assertThat(f.count("io.imported_file")).isEqualTo(files);
        assertThat(f.count("core.payout_status_history")).isEqualTo(history);
        assertThat(f.count("ledger.journal_entry")).isEqualTo(entries);
        assertThat(state()).isEqualTo("PAID");
        assertThat(f.status(refund)).isEqualTo("AWAITING_FUNDS");
        assertThat(f.number("SELECT count(*) FROM io.outbound_command WHERE kind='SUBMIT_REFUND'"))
                .isZero();
        statement(2, credit("returned", 9680));
        assertThat(f.status(refund)).isEqualTo("READY");
        assertThat(f.balance(2102)).isEqualTo(8880);
        f.invariants();
    }
}
