package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.internal.JdbcCorrections;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.payments.PaymentCorrections;
import dev.ledgerlab.refunds.internal.CorrectionFixture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.settlement.SettlementCorrections;
import dev.ledgerlab.shared.BusinessCalendar;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankCorrectionReconTest {
    private static final Instant EARLY = Instant.parse("2026-10-24T04:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-10-29T04:00:00Z");
    private static final Instant BEFORE = Instant.parse("2026-10-29T06:00:00Z");
    private static final Instant CORRECTED = Instant.parse("2026-10-29T12:00:00Z");
    private CorrectionFixture f;
    private BankStatements banks;
    private ReconRuns runs;
    private BankEvidence evidence;

    @BeforeEach
    void setup() {
        f = new CorrectionFixture();
        f.s1();
        f.batch("po_b1", "ch_p1", 10000, 320);
        var calendar = new BusinessCalendar();
        var policy = new AgingPolicy(2, 2, 2, Duration.ofHours(1), 2, 5, 3);
        var json = new ReconJson(f.mapper);
        evidence = new BankEvidence(f.jdbc);
        runs = new ReconRuns(
                f.jdbc,
                f.manager,
                f.clock,
                f.ids,
                new ReconProperties(Duration.ofSeconds(60), "p4-v2"),
                List.of(
                        new BankRule(evidence, calendar, policy, json, f.jdbc),
                        new AgingRule(f.jdbc, calendar, policy, evidence, json, f.mapper)),
                json,
                f.mapper,
                new IssueStore(f.jdbc, f.ids, f.clock, f.manager));
        banks = new BankStatements(f.jdbc, f.ledger, f.issues, f.clock, f.ids, calendar, f.mapper, f.manager, 2);
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    private String statement(int version, long opening, long closing, String... rows) {
        long credits = 0, debits = 0;
        for (String row : rows) {
            var fields = row.split(",");
            if (fields[2].equals("CREDIT")) credits = Math.addExact(credits, Long.parseLong(fields[3]));
            else debits = Math.addExact(debits, Long.parseLong(fields[3]));
        }
        String csv = "H,BANK_STATEMENT,SIM-CAD-001,2026-10-23,2026-10-23," + version + ",CAD\r\n"
                + (rows.length == 0 ? "" : String.join("\r\n", rows) + "\r\n") + "T," + rows.length + "," + credits
                + "," + debits
                + "," + opening + "," + closing + "\r\n";
        var result = banks.importBankStatement("fixture", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo("ACCEPTED");
        return result.fileId();
    }

    private String row(String id, String direction, long amount, String reference) {
        return "D," + id + "," + direction + "," + amount + ",CAD,BOOKED," + reference
                + ",2026-10-23,2026-10-23,fixture";
    }

    private void prepare(boolean restore) {
        if (restore) {
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
            statement(1, 0, 9680, row("T5", "CREDIT", 9680, "po_b1"));
        } else {
            statement(1, 0, 9680, row("T5", "CREDIT", 5000, "po_b1"), row("T6", "CREDIT", 4680, "po_b1"));
        }
        f.clock.set(CORRECTED);
    }

    private String correct(boolean restore) {
        var service = new JdbcCorrections(
                f.jdbc,
                f.ledger,
                new PaymentCorrections(f.jdbc, f.ledger),
                new IssueStore(f.jdbc, f.ids, f.clock, f.manager),
                f.rechecks,
                new SettlementCorrections(f.jdbc, f.ledger),
                f.clock,
                f.mapper,
                f.manager);
        return Objects.requireNonNull(service.submit(new CorrectionCommand(
                                restore ? CorrectionType.RESTORE_FAILED_PAYOUT : CorrectionType.APPLY_UNAPPLIED_CASH,
                                null,
                                null,
                                "po_b1",
                                restore ? List.of("T5") : List.of("T5", "T6"),
                                restore ? CorrectionReason.PAYOUT_FAILURE_DISPROVED : CorrectionReason.SPLIT_DEPOSIT,
                                "Verified synthetic bank evidence",
                                List.of(new CorrectionCommand.Evidence("bank_txn", "T5")),
                                "operator"))
                        .correction()
                        .get("id"))
                .toString();
    }

    private RunReport run(RunRequest.Kind kind, Instant cutoff, Instant asOf, boolean dry) {
        return runs.run(new RunRequest(kind, cutoff, asOf, "p4-v2", List.of(), dry));
    }

    private List<String> dispositions() {
        return f.jdbc.sql("SELECT id || ':' || status || ':' || reopen_count FROM recon.issue ORDER BY id")
                .query((rs, n) -> rs.getString(1))
                .list();
    }

    private List<String> money() {
        return f.jdbc.sql("SELECT code || ':' || balance FROM ledger.account ORDER BY code")
                .query((rs, n) -> rs.getString(1))
                .list();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_6_10_ac_6_11_correctionExplainsOpenFindingsAndReplaysHistoricalEvidence(boolean restore) {
        prepare(restore);
        var oldBank = run(RunRequest.Kind.BANK, CUTOFF, BEFORE, true);
        var oldAging = run(RunRequest.Kind.AGING, CUTOFF, BEFORE, true);
        assertThat(oldBank.summary()).containsEntry("UNAPPLIED_CASH_OPEN", restore ? 1L : 2L);
        if (!restore) assertThat(oldAging.summary()).containsEntry("DEPOSIT_NOT_RECEIVED", 1L);
        String correction = correct(restore);
        f.clock.set(CORRECTED.plusSeconds(120));
        // A historical run after the correction creates OPEN rule-owned issues that it did not resolve.
        var historicalBank = run(RunRequest.Kind.BANK, CUTOFF, BEFORE, false);
        var historicalAging = run(RunRequest.Kind.AGING, CUTOFF, BEFORE, false);
        assertThat(historicalBank.findings()).isEqualTo(oldBank.findings());
        assertThat(historicalAging.findings()).isEqualTo(oldAging.findings());
        var open = dispositions();
        var balances = money();
        long runCount =
                f.jdbc.sql("SELECT count(*) FROM recon.run").query(Long.class).single();
        for (var kind : List.of(RunRequest.Kind.BANK, RunRequest.Kind.AGING)) {
            assertThat(run(kind, CUTOFF, CORRECTED.plusSeconds(1), true).findings())
                    .isEmpty();
        }
        assertThat(dispositions()).isEqualTo(open);
        assertThat(f.jdbc.sql("SELECT count(*) FROM recon.run")
                        .query(Long.class)
                        .single())
                .isEqualTo(runCount);
        assertThat(run(RunRequest.Kind.BANK, CUTOFF, CORRECTED, true).findings())
                .isEqualTo(oldBank.findings());
        assertThat(run(RunRequest.Kind.AGING, CUTOFF, CORRECTED, true).findings())
                .isEqualTo(oldAging.findings());
        for (var kind : List.of(RunRequest.Kind.BANK, RunRequest.Kind.AGING)) {
            assertThat(run(kind, CUTOFF, CORRECTED.plusSeconds(1), false).findings())
                    .isEmpty();
        }
        assertThat(f.jdbc.sql("SELECT status FROM recon.issue WHERE detected_by <> 'INGESTION'")
                        .query(String.class)
                        .list())
                .isNotEmpty()
                .containsOnly("EXPLAINED");
        assertThat(f.jdbc.sql("SELECT evidence_refs::text FROM recon.issue_transition WHERE to_status='EXPLAINED'")
                        .query(String.class)
                        .list())
                .isNotEmpty()
                .allMatch(refs ->
                        refs.contains(correction) && refs.contains("target_entry_id") && refs.contains("APPLIED"));
        assertThat(runs.get(Objects.requireNonNull(historicalBank.id()))).contains(historicalBank);
        assertThat(runs.get(Objects.requireNonNull(historicalAging.id()))).contains(historicalAging);
        assertThat(run(RunRequest.Kind.BANK, CUTOFF, BEFORE, true).findings()).isEqualTo(oldBank.findings());
        assertThat(money()).isEqualTo(balances);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_6_10_ac_6_11_resolvedIssuesStayClosedAndCashIsUnchanged(boolean restore) {
        prepare(restore);
        run(RunRequest.Kind.BANK, EARLY, BEFORE, false);
        run(RunRequest.Kind.AGING, CUTOFF, BEFORE, false);
        long cash = f.jdbc.sql("SELECT balance FROM ledger.account WHERE code=1103")
                .query(Long.class)
                .single();
        correct(restore);
        var resolved = dispositions();
        f.clock.set(CORRECTED.plusSeconds(120));
        assertThat(run(RunRequest.Kind.BANK, EARLY, CORRECTED.plusSeconds(1), false)
                        .findings())
                .isEmpty();
        assertThat(run(RunRequest.Kind.AGING, CUTOFF, CORRECTED.plusSeconds(1), false)
                        .findings())
                .isEmpty();
        assertThat(dispositions())
                .isEqualTo(resolved)
                .anyMatch(s -> s.contains(":RESOLVED:0"))
                .noneMatch(s -> s.contains(":OPEN:"));
        assertThat(f.jdbc.sql("SELECT balance FROM ledger.account WHERE code=1103")
                        .query(Long.class)
                        .single())
                .isEqualTo(cash);
        assertThat(f.jdbc.sql("""
                SELECT count(*) FROM ledger.posting p JOIN ledger.journal_entry e ON e.id=p.entry_id
                WHERE e.correction_id IS NOT NULL AND p.account_code=1103
                """).query(Long.class).single()).isZero();
        // INV-11 still detects a real one-cent statement discrepancy after application.
        statement(2, 9681, 9681);
        f.clock.set(CORRECTED.plusSeconds(240));
        var report = run(RunRequest.Kind.BANK, CUTOFF, CORRECTED.plusSeconds(121), true);
        assertThat(report.findings()).singleElement().satisfies(observed -> {
            assertThat(observed.finding().category()).isEqualTo("BANK_BALANCE_MISMATCH");
            assertThat(observed.finding().actual()).contains("\"cash\":9680", "\"difference\":1");
        });
    }

    @Test
    void ac_6_10_pinnedInputsMustContainAllAppliedTargetsToExplainTheBatch() {
        String first = statement(1, 0, 5000, row("T5", "CREDIT", 5000, "po_b1"));
        String second = statement(2, 5000, 9680, row("T6", "CREDIT", 4680, "po_b1"));
        f.clock.set(CORRECTED);
        String correction = correct(false);
        f.clock.set(CORRECTED.plusSeconds(120));
        var partial =
                new RunRequest(RunRequest.Kind.AGING, CUTOFF, CORRECTED.plusSeconds(1), "p4-v2", List.of(first), true);
        assertThat(runs.run(partial).summary()).containsEntry("DEPOSIT_NOT_RECEIVED", 1L);
        var complete = new RunRequest(
                RunRequest.Kind.AGING, CUTOFF, CORRECTED.plusSeconds(1), "p4-v2", List.of(first, second), true);
        assertThat(runs.run(complete).findings()).isEmpty();
        var snapshot =
                evidence.read(new ReconRule.Context(complete, runs.run(complete).inputHashes()));
        assertThat(snapshot.cash(java.time.LocalDate.parse("2026-10-23"))).isEqualTo(9680);
        assertThat(snapshot.refs(snapshot.batches().getFirst()).toString())
                .contains(correction, first, second, "T5", "T6", "target_entry_id", "APPLIED");
    }

    @Test
    void ac_6_9_ac_6_10_appliedAndReturnedLinesRemainIndependent() {
        prepare(false);
        statement(2, 9680, 10180, row("extra", "CREDIT", 500, "po_b1"));
        correct(false);
        f.clock.set(CORRECTED.plusSeconds(120));
        var beforeReturn = run(RunRequest.Kind.BANK, CUTOFF, CORRECTED.plusSeconds(1), false);
        assertThat(beforeReturn.findings())
                .hasSize(2)
                .allMatch(o -> o.finding().subjectId().equals("extra"));
        statement(3, 10180, 9680, row("return", "DEBIT", 500, "extra"));
        f.clock.set(CORRECTED.plusSeconds(240));
        for (var kind : List.of(RunRequest.Kind.BANK, RunRequest.Kind.AGING)) {
            assertThat(run(kind, CUTOFF, CORRECTED.plusSeconds(121), false).findings())
                    .isEmpty();
        }
        assertThat(run(RunRequest.Kind.BANK, CUTOFF, CORRECTED.plusSeconds(1), true)
                        .findings())
                .isEqualTo(beforeReturn.findings());
    }
}
