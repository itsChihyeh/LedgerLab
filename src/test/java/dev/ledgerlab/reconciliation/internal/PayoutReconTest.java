package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.internal.JdbcCorrections;
import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.payouts.PayoutCorrections;
import dev.ledgerlab.payouts.internal.PayoutCorrectionFixture;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.SettlementCorrections;
import dev.ledgerlab.shared.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayoutReconTest {
    private static final Instant RESERVED = Instant.parse("2026-11-06T16:00:00Z");
    private static final Instant DUE = Instant.parse("2026-11-10T05:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-11-06T05:00:00Z");
    private CorrectionFixture f;
    private BankStatements bank;
    private BankEvidence evidence;
    private ReconRuns runs;
    private String payout;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        f.s1();
        f.batch("po_b1", "ch_p1", 10000, 320);
        f.bankWaiting("po_b1", 9680);
        f.clock.set(RESERVED);
        payout = PayoutCorrectionFixture.payouts(f)
                .reserve(9680, "T08", "fixture")
                .payoutId();
        var calendar = new BusinessCalendar();
        var policy = new AgingPolicy(2, 2, 2, Duration.ofHours(1), 2, 5, 3);
        var json = new ReconJson(f.mapper);
        evidence = new BankEvidence(f.jdbc);
        runs = new ReconRuns(
                f.jdbc,
                f.manager,
                f.clock,
                f.ids,
                new ReconProperties(Duration.ofSeconds(60), "p5-v1"),
                List.of(
                        new BankRule(evidence, calendar, policy, json, f.jdbc),
                        new AgingRule(f.jdbc, calendar, policy, evidence, json, f.mapper)),
                json,
                f.mapper,
                new IssueStore(f.jdbc, f.ids, f.clock, f.manager));
        bank = new BankStatements(
                f.jdbc,
                f.ledger,
                f.issues,
                f.clock,
                f.ids,
                calendar,
                f.mapper,
                f.manager,
                2,
                new dev.ledgerlab.payouts.internal.JdbcPayoutBankEvidence(
                        f.jdbc, f.ledger, f.queue, calendar, f.clock));
        invariant();
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private void invariant() {
        f.invariants();
        assertThat(f.jdbc.sql("""
                SELECT balance=(SELECT coalesce(sum(amount),0) FROM core.payout
                    WHERE status IN ('RESERVED','SUBMITTED','UNKNOWN','ACCEPTED'))
                FROM ledger.account WHERE code=2104
                """).query(Boolean.class).single())
                .as("INV-9 payout transit")
                .isTrue();
    }

    private void transition(String status, Instant occurred) {
        f.tx.executeWithoutResult(tx -> {
            String from = f.jdbc.sql("SELECT status FROM core.payout WHERE id=?")
                    .param(payout)
                    .query(String.class)
                    .single();
            f.jdbc.sql("UPDATE core.payout SET status=?,failure_reason='fixture' WHERE id=?")
                    .params(status, payout)
                    .update();
            history(from, status, "bank_api", "fixture", occurred);
            if (status.equals("FAILED")) {
                f.ledger.lockFunds();
                f.ledger.post(new EntryRequest(
                        EntryType.PAYOUT_FAILED,
                        "payout:" + payout + ":failed",
                        occurred,
                        SubjectType.PAYOUT,
                        payout,
                        List.of(
                                PostingLine.debit(Account.PAYOUT_IN_TRANSIT, 9680),
                                PostingLine.credit(Account.MERCHANT_AVAILABLE, 9680))));
            }
        });
        invariant();
    }

    private void history(String from, String to, String type, String id, Instant occurred) {
        f.jdbc.sql("""
                INSERT INTO core.payout_status_history(payout_id,from_status,to_status,evidence_type,evidence_id,occurred_at,recorded_at)
                VALUES (?,?,?,?,?,?,?)
                """)
                .params(
                        payout,
                        from,
                        to,
                        type,
                        id,
                        occurred.atOffset(ZoneOffset.UTC),
                        f.clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    private String statement(
            String day,
            String id,
            String direction,
            long amount,
            String currency,
            String status,
            String reference,
            long opening,
            long closing) {
        boolean booked = status.equals("BOOKED");
        String csv = "H,BANK_STATEMENT,SIM-CAD-001," + day + "," + day + ",1,CAD\r\n"
                + "D," + id + "," + direction + "," + amount + "," + currency + "," + status + "," + reference
                + "," + day + "," + day + ",RETURN ACCOUNT_CLOSED\r\n"
                + "T,1," + (booked && direction.equals("CREDIT") ? amount : 0) + ","
                + (booked && direction.equals("DEBIT") ? amount : 0) + "," + opening + "," + closing + "\r\n";
        var imported = bank.importBankStatement("T08.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).isEqualTo("ACCEPTED");
        return imported.fileId();
    }

    private String debit() {
        String file = statement("2026-11-09", "out", "DEBIT", 9680, "CAD", "BOOKED", payout, 9680, 0);
        invariant();
        return file;
    }

    private RunReport run(RunRequest.Kind kind, Instant cutoff, Instant asOf, boolean dry, String... files) {
        return runs.run(new RunRequest(kind, cutoff, asOf, "p5-v1", List.of(files), dry));
    }

    private List<RunReport.ObservedFinding> payoutFindings(RunReport report) {
        return report.findings().stream()
                .filter(o -> o.finding().subjectId().equals(payout))
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVED", "SUBMITTED", "UNKNOWN", "ACCEPTED"})
    void ac_7_7_ac_7_18_unresolvedUsesBusinessDayBoundaryAndClosesOnlyWithEvidence(String status) {
        if (!status.equals("RESERVED")) transition(status, RESERVED);
        f.clock.set(DUE.plusSeconds(120));
        assertThat(payoutFindings(run(RunRequest.Kind.AGING, CUTOFF, DUE.minusMillis(1), true)))
                .isEmpty();
        var old = run(RunRequest.Kind.AGING, CUTOFF, DUE, false);
        assertThat(payoutFindings(old)).singleElement().satisfies(o -> {
            assertThat(o.finding().category()).isEqualTo("PAYOUT_UNRESOLVED");
            assertThat(o.finding().expected()).contains("\"threshold_business_days\":1", DUE.toString());
        });
        assertThat(f.number("SELECT balance FROM ledger.account WHERE code=2104"))
                .isEqualTo(9680);
        var before = f.jdbc.sql("SELECT status FROM core.payout WHERE id=?")
                .param(payout)
                .query(String.class)
                .single();
        assertThat(before).isEqualTo(status);
        debit();
        f.clock.set(DUE.plusSeconds(240));
        assertThat(payoutFindings(run(RunRequest.Kind.AGING, CUTOFF, DUE.plusSeconds(121), false)))
                .isEmpty();
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE category='PAYOUT_UNRESOLVED' AND subject_id=?")
                        .param(payout)
                        .query(String.class)
                        .single())
                .isEqualTo("RESOLVED");
        assertThat(run(RunRequest.Kind.AGING, CUTOFF, DUE, true).findings()).isEqualTo(old.findings());
        assertThat(runs.get(Objects.requireNonNull(old.id()))).contains(old);
        invariant();
    }

    @Test
    void ac_7_18_paidAndReturnCashFollowBookingPeriodsAndReplayOldEvidence() {
        f.clock.set(DUE.plusSeconds(120));
        String file = debit();
        Instant paidAsOf = DUE.plusSeconds(121);
        f.clock.set(DUE.plusSeconds(240));
        var paid = run(RunRequest.Kind.BANK, DUE, paidAsOf, false);
        assertThat(paid.findings()).isEmpty();
        // Missing a selected file must not borrow evidence from the current payout row.
        var pendingFile = statement("2026-11-10", "pending", "DEBIT", 1, "CAD", "PENDING", "unknown", 0, 0);
        f.clock.set(DUE.plusSeconds(360));
        assertThat(payoutFindings(run(RunRequest.Kind.BANK, DUE, DUE.plusSeconds(241), false, pendingFile)))
                .singleElement()
                .satisfies(o -> assertThat(o.finding().category()).isEqualTo("PAYOUT_STATE_CONFLICT"));
        assertThat(payoutFindings(run(RunRequest.Kind.BANK, DUE, paidAsOf, false, file)))
                .isEmpty();
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE category='PAYOUT_STATE_CONFLICT' AND subject_id=?")
                        .param(payout)
                        .query(String.class)
                        .single())
                .isEqualTo("EXPLAINED");
        f.clock.set(Instant.parse("2026-11-13T12:00:00Z"));
        String returnedFile = statement("2026-11-12", "back", "CREDIT", 9680, "CAD", "BOOKED", payout, 0, 9680);
        invariant();
        Instant returnedAsOf = f.clock.instant().plusSeconds(1);
        f.clock.set(returnedAsOf.plusSeconds(120));
        var returned = run(RunRequest.Kind.BANK, Instant.parse("2026-11-13T05:00:00Z"), returnedAsOf, false);
        assertThat(returned.findings()).isEmpty();
        var snapshot = evidence.read(new ReconRule.Context(
                new RunRequest(RunRequest.Kind.BANK, DUE, returnedAsOf, "p5-v1", List.of(), true),
                returned.inputHashes()));
        assertThat(snapshot.cash(LocalDate.parse("2026-11-09"))).isZero();
        assertThat(snapshot.cash(LocalDate.parse("2026-11-12"))).isEqualTo(9680);
        assertThat(payoutFindings(run(
                        RunRequest.Kind.BANK, Instant.parse("2026-11-13T05:00:00Z"), returnedAsOf, true, returnedFile)))
                .singleElement()
                .satisfies(o -> assertThat(o.finding().actual())
                        .contains("\"paid_matched\":false", "\"returned_matched\":true"));
        assertThat(payoutFindings(
                        run(RunRequest.Kind.BANK, Instant.parse("2026-11-13T05:00:00Z"), returnedAsOf, true, file)))
                .singleElement()
                .satisfies(o -> assertThat(o.finding().actual())
                        .contains("\"paid_matched\":true", "\"returned_matched\":false"));
        assertThat(run(RunRequest.Kind.BANK, DUE, paidAsOf, true).findings()).isEqualTo(paid.findings());
        assertThat(runs.get(Objects.requireNonNull(paid.id()))).contains(paid);
    }

    @Test
    void ac_6_15_ac_7_11_correctionExplainsExactCashDifferenceWithoutChangingOldEvidence() {
        transition("FAILED", RESERVED);
        f.clock.set(DUE.plusSeconds(120));
        debit();
        Instant before = f.clock.instant().plusSeconds(1);
        f.clock.set(DUE.plusSeconds(240));
        var old = run(RunRequest.Kind.BANK, DUE, before, false);
        assertThat(old.findings()).singleElement().satisfies(o -> {
            assertThat(o.finding().category()).isEqualTo("BANK_BALANCE_MISMATCH");
            assertThat(o.finding().actual()).contains("\"cash\":9680", "\"difference\":-9680");
        });
        var corrections = new JdbcCorrections(
                f.jdbc,
                f.ledger,
                new PaymentCorrections(f.jdbc, f.ledger),
                new IssueStore(f.jdbc, f.ids, f.clock, f.manager),
                f.rechecks,
                new SettlementCorrections(f.jdbc, f.ledger),
                new PayoutCorrections(f.jdbc, f.ledger, new BusinessCalendar(), f.clock),
                f.clock,
                f.mapper,
                f.manager);
        corrections.submit(new CorrectionCommand(
                CorrectionType.CONFIRM_FAILED_PAYOUT_PAID,
                null,
                null,
                null,
                List.of("out"),
                payout,
                CorrectionReason.PAYOUT_FAILURE_DISPROVED,
                "Synthetic evidence",
                List.of(new CorrectionCommand.Evidence("bank_txn", "out")),
                "operator"));
        invariant();
        Instant corrected = f.clock.instant();
        f.clock.set(corrected.plusSeconds(120));
        assertThat(run(RunRequest.Kind.BANK, DUE, corrected, true).findings()).isEqualTo(old.findings());
        assertThat(run(RunRequest.Kind.BANK, DUE, corrected.plusSeconds(1), false)
                        .findings())
                .isEmpty();
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE category='BANK_BALANCE_MISMATCH'")
                        .query(String.class)
                        .single())
                .isEqualTo("EXPLAINED");
        assertThat(run(RunRequest.Kind.BANK, DUE, before, true).findings()).isEqualTo(old.findings());
        assertThat(runs.get(Objects.requireNonNull(old.id()))).contains(old);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "reference", "direction", "currency", "pending", "identity", "missing"})
    void ac_7_18_paidRequiresExactHistoricalBankIdentityAndAllMatchingFields(String fault) {
        f.clock.set(DUE.plusSeconds(120));
        if (fault.equals("identity")) transition("FAILED", f.clock.instant());
        long amount = fault.equals("amount") ? 9679 : 9680;
        String direction = fault.equals("direction") ? "CREDIT" : "DEBIT";
        String status = fault.equals("pending") ? "PENDING" : "BOOKED";
        if (!fault.equals("missing")) {
            statement(
                    "2026-11-09",
                    fault.equals("identity") ? "different" : "out",
                    direction,
                    amount,
                    fault.equals("currency") ? "USD" : "CAD",
                    status,
                    fault.equals("reference") ? "unknown" : payout,
                    9680,
                    status.equals("PENDING")
                            ? 9680
                            : direction.equals("CREDIT")
                                    ? Math.addExact(9680, amount)
                                    : Math.subtractExact(9680, amount));
        }
        // A deliberately inconsistent immutable history must be checked independently of the mutable row.
        history("RESERVED", "PAID", "bank_txn", "out", DUE);
        f.clock.set(DUE.plusSeconds(240));
        var report = run(RunRequest.Kind.BANK, DUE, DUE.plusSeconds(121), true);
        assertThat(payoutFindings(report)).singleElement().satisfies(o -> {
            assertThat(o.finding().category()).isEqualTo("PAYOUT_STATE_CONFLICT");
            assertThat(o.finding().actual()).contains("\"paid_matched\":false");
        });
        assertThat(payoutFindings(run(RunRequest.Kind.BANK, CUTOFF, DUE.plusSeconds(121), true)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "ACCEPTED"})
    void ac_7_7_ac_7_18_firstSubmissionControlsAgeAcrossHolidayAndRetriesDoNotResetIt(String status) {
        Instant first = Instant.parse("2026-11-10T16:00:00Z");
        f.clock.set(first);
        transition("UNKNOWN", first);
        Instant retry = Instant.parse("2026-11-12T16:00:00Z");
        f.clock.set(retry);
        transition(status, retry);
        Instant due = Instant.parse("2026-11-13T05:00:00Z");
        f.clock.set(due.plusSeconds(120));
        assertThat(payoutFindings(run(RunRequest.Kind.AGING, CUTOFF, due.minusMillis(1), true)))
                .isEmpty();
        assertThat(payoutFindings(run(RunRequest.Kind.AGING, CUTOFF, due, false)))
                .singleElement()
                .satisfies(o -> assertThat(o.finding().expected()).contains(first.toString(), due.toString()));
        Instant late = Instant.parse("2026-11-19T05:00:00Z");
        f.clock.set(late.plusSeconds(120));
        assertThat(payoutFindings(run(RunRequest.Kind.AGING, CUTOFF, late, true)))
                .hasSize(1);
        assertThat(f.jdbc.sql("SELECT status FROM core.payout WHERE id=?")
                        .param(payout)
                        .query(String.class)
                        .single())
                .isEqualTo(status);
        assertThat(f.number("SELECT balance FROM ledger.account WHERE code=2104"))
                .isEqualTo(9680);
        invariant();
    }

    @Test
    void ac_7_7_failureExplainsAgingAndHistoricalDryRunDoesNotReopenIt() {
        transition("UNKNOWN", RESERVED);
        f.clock.set(DUE.plusSeconds(120));
        var old = run(RunRequest.Kind.AGING, CUTOFF, DUE, false);
        assertThat(payoutFindings(old)).hasSize(1);
        transition("FAILED", f.clock.instant());
        Instant failed = f.clock.instant();
        f.clock.set(failed.plusSeconds(120));
        assertThat(payoutFindings(run(RunRequest.Kind.AGING, CUTOFF, failed.plusSeconds(1), false)))
                .isEmpty();
        assertThat(run(RunRequest.Kind.AGING, CUTOFF, DUE, true).findings()).isEqualTo(old.findings());
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE category='PAYOUT_UNRESOLVED' AND subject_id=?")
                        .param(payout)
                        .query(String.class)
                        .single())
                .isEqualTo("EXPLAINED");
        invariant();
    }

    @Test
    void ac_7_18_oldRuleReportsRemainReadableWithoutReevaluation() {
        var oldRule = new ReconRule() {
            @Override
            public RunRequest.Kind kind() {
                return RunRequest.Kind.BANK;
            }

            @Override
            public String version() {
                return "p4-v2";
            }

            @Override
            public List<Finding> evaluate(Context context) {
                return List.of(new Finding(
                        "BANK_BALANCE_MISMATCH",
                        "bank_statement",
                        "old-period",
                        "{\"closing_balance\":0}",
                        "{\"cash\":9680}",
                        "[]",
                        null));
            }

            @Override
            public java.util.Map<Finding.Key, Explanation> checkedScope(Context context) {
                return java.util.Map.of(evaluate(context).getFirst().key(), new Explanation("old rule", "[]"));
            }
        };
        var json = new ReconJson(f.mapper);
        var oldRuns = new ReconRuns(
                f.jdbc,
                f.manager,
                f.clock,
                f.ids,
                new ReconProperties(Duration.ofSeconds(60), "p4-v2"),
                List.of(oldRule),
                json,
                f.mapper,
                new IssueStore(f.jdbc, f.ids, f.clock, f.manager));
        f.clock.set(DUE.plusSeconds(120));
        var old = oldRuns.run(new RunRequest(RunRequest.Kind.BANK, DUE, DUE, "p4-v2", List.of(), false));
        assertThat(runs.get(Objects.requireNonNull(old.id()))).contains(old);
        assertThat(old.ruleVersion()).isEqualTo("p4-v2");
    }

    @Test
    void ac_7_18_configurablePayoutThresholdMustBePositive() {
        var policy = new AgingPolicy(2, 2, 2, Duration.ofHours(1), 2, 5, 3, 2);
        var calendar = new BusinessCalendar();
        var rule = new AgingRule(f.jdbc, calendar, policy, evidence, new ReconJson(f.mapper), f.mapper);
        Instant asOf = Instant.parse("2026-11-11T05:00:00Z");
        var request = new RunRequest(RunRequest.Kind.AGING, CUTOFF, asOf, "p5-v1", List.of(), true);
        assertThat(rule.evaluate(new ReconRule.Context(request, java.util.Map.of())))
                .filteredOn(finding -> finding.subjectId().equals(payout))
                .singleElement()
                .satisfies(finding ->
                        assertThat(finding.expected()).contains("\"threshold_business_days\":2", asOf.toString()));
        for (int invalid : List.of(0, -1))
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AgingPolicy(2, 2, 2, Duration.ofHours(1), 2, 5, 3, invalid));
    }
}
