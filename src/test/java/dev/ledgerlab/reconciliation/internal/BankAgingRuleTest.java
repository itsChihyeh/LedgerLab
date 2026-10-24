package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class BankAgingRuleTest {
    final ProcessorRuleTest f = new ProcessorRuleTest();
    BankStatements bank;

    @BeforeEach
    void setup() {
        f.setup();
        var ids = IdGenerator.seeded(f.clock, 1011);
        var manager = f.manager;
        var json = new ReconJson(f.mapper);
        var calendar = new BusinessCalendar();
        var policy = new AgingPolicy(2, 2, 2, java.time.Duration.ofHours(1));
        var evidence = new BankEvidence(f.jdbc);
        f.runs = new ReconRuns(
                f.jdbc,
                manager,
                f.clock,
                ids,
                new ReconProperties(java.time.Duration.ofSeconds(60), "p3-v1"),
                List.of(
                        new ProcessorRule(f.jdbc, calendar, json, f.mapper, policy),
                        new BankRule(evidence, calendar, policy, json, f.jdbc),
                        new AgingRule(f.jdbc, calendar, policy, evidence, json, f.mapper)),
                json,
                f.mapper,
                new IssueStore(f.jdbc, ids, f.clock, manager));
        bank = new BankStatements(
                f.jdbc,
                new JdbcLedger(f.source, f.clock, ids),
                new JdbcIngestionIssues(f.jdbc, ids, f.clock),
                f.clock,
                ids,
                new BusinessCalendar(),
                f.mapper,
                manager,
                2);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    RunReport run(RunRequest.Kind kind, Instant cutoff, Instant evidence, boolean dry) {
        return f.runs.run(new RunRequest(kind, cutoff, evidence, "p3-v1", List.of(), dry));
    }

    String statement(String date, int version, long opening, long closing, String... rows) {
        long credits = 0;
        long debits = 0;
        for (String row : rows) {
            String[] fields = row.split(",");
            if (fields[5].equals("BOOKED")) {
                if (fields[2].equals("CREDIT")) credits = Math.addExact(credits, Long.parseLong(fields[3]));
                else debits = Math.addExact(debits, Long.parseLong(fields[3]));
            }
        }
        String csv = "H,BANK_STATEMENT,SIM-CAD-001," + date + "," + date + "," + version + ",CAD\r\n"
                + (rows.length == 0 ? "" : String.join("\r\n", rows) + "\r\n")
                + "T," + rows.length + "," + credits + "," + debits + "," + opening + "," + closing + "\r\n";
        var result = bank.importBankStatement("fixture", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(result.status()).isEqualTo("ACCEPTED");
        return result.fileId();
    }

    String credit(String id, long amount, String ref, String date) {
        return "D," + id + ",CREDIT," + amount + ",CAD,BOOKED," + ref + "," + date + "," + date + ",fixture";
    }

    @Test
    void ac_4_10_backfilledHistoryStartsStrictlyAfterItsTimestampAndThresholdIsConfigurable() {
        f.inbox("evt_backfilled");
        Instant known = ProcessorRuleTest.CAPTURED.plusSeconds(7200);
        f.jdbc.sql("""
                INSERT INTO io.inbox_status_history(event_id,to_status,parked_on,lease_epoch,recorded_at,backfilled)
                VALUES ('evt_backfilled','PARKED','batch:po_absent',1,?,true)
                """).param(known.atOffset(java.time.ZoneOffset.UTC)).update();
        var json = new ReconJson(f.mapper);
        var policy = new AgingPolicy(2, 2, 2, java.time.Duration.ofHours(3));
        var rule = new AgingRule(f.jdbc, new BusinessCalendar(), policy, new BankEvidence(f.jdbc), json, f.mapper);
        java.util.function.Function<Instant, List<Finding>> inspect = asOf -> rule.evaluate(new ReconRule.Context(
                new RunRequest(
                        RunRequest.Kind.AGING, Instant.parse("2026-11-10T05:00:00Z"), asOf, "p3-v1", List.of(), true),
                java.util.Map.of()));
        assertThat(inspect.apply(known)).isEmpty();
        assertThat(inspect.apply(known.plusSeconds(1))).isEmpty();
        f.clock.set(known.plusSeconds(120));
        Instant cutoff = Instant.parse("2026-11-10T05:00:00Z");
        // With the default one-hour threshold it is overdue, but the backfill cannot leak backwards.
        assertThat(run(RunRequest.Kind.AGING, cutoff, known, true).findings()).isEmpty();
        assertThat(run(RunRequest.Kind.AGING, cutoff, known.plusSeconds(1), true)
                        .findings())
                .hasSize(1);
        assertThat(inspect.apply(ProcessorRuleTest.CAPTURED.plusSeconds(10800)))
                .singleElement()
                .satisfies(finding -> assertThat(finding.expected()).contains("PT3H"));
        // At identical timestamps, the identity column orders the state changes.
        f.jdbc.sql("""
                INSERT INTO io.inbox_status_history(event_id,from_status,to_status,lease_epoch,recorded_at)
                VALUES ('evt_backfilled','PARKED','RECEIVED',1,?)
                """).param(known.atOffset(java.time.ZoneOffset.UTC)).update();
        assertThat(inspect.apply(ProcessorRuleTest.CAPTURED.plusSeconds(10800))).isEmpty();
        for (var invalid : List.of(java.time.Duration.ZERO, java.time.Duration.ofSeconds(-1)))
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AgingPolicy(2, 2, 2, invalid));
    }

    @Test
    void ac_5_11_agingFindsOldPaymentIndependentlyOfPendingTotal() {
        f.payment("ch_old", 6000, Instant.parse("2026-11-05T16:00:00Z"));
        f.payment("ch_recent", 3000, ProcessorRuleTest.CAPTURED);
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var report = run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(report.findings()).hasSize(1);
        var finding = f.finding(report, "PAYMENT_NOT_SETTLED");
        assertThat(finding.subjectId()).isEqualTo(f.paymentId("ch_old"));
        assertThat(finding.expected()).contains("已过 3 个工作日", "2026-11-10T05:00:00Z");
        assertThat(report.summary().toString()).contains("老化只能发现有开始记录、没有结束记录的义务");
    }

    @Test
    void ac_5_12_balancedFileStillExposesLedgerCashDifference() {
        f.clock.set(Instant.parse("2026-11-10T18:00:00Z"));
        statement("2026-11-10", 1, 100, 100);
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var report = run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        var finding = f.finding(report, "BANK_BALANCE_MISMATCH");
        assertThat(finding.expected()).contains("100");
        assertThat(finding.actual()).contains("\"difference\":100", "\"cash\":0");
    }

    @Test
    void ac_5_7_allFindsExactlySevenWithNoBankFindingsAndReplays() {
        f.standard();
        f.clock.set(Instant.parse("2026-11-12T12:00:00Z"));
        statement("2026-11-12", 1, 0, 25096, credit("bt_b1", 25096, "po_b1", "2026-11-12"));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var money = f.money();
        var reports = f.runs.runAll(ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(reports)
                .extracting(RunReport::kind)
                .containsExactly(RunRequest.Kind.PROCESSOR, RunRequest.Kind.BANK, RunRequest.Kind.AGING);
        assertThat(reports).extracting(r -> r.findings().size()).containsExactly(6, 0, 1);
        var second = f.runs.runAll(ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        for (int i = 0; i < 3; i++) {
            assertThat(second.get(i).findings()).isEqualTo(reports.get(i).findings());
            assertThat(f.runs.get(
                            java.util.Objects.requireNonNull(reports.get(i).id())))
                    .contains(reports.get(i));
        }
        assertThat(f.jdbc.sql("SELECT count(*) FROM recon.issue")
                        .query(Long.class)
                        .single())
                .isEqualTo(7);
        assertThat(f.money()).isEqualTo(money);
        f.clock.set(Instant.parse("2026-11-12T16:00:00Z"));
        f.batch("po_b2", f.clock.instant(), List.of(f.localLine("txn_p4", "ch_p4", 3000, 117)));
        f.clock.set(Instant.parse("2026-11-13T08:00:00Z"));
        f.snapshot("po_b2", java.time.LocalDate.parse("2026-11-12"), f.line("txn_p4", "ch_p4", 3000, 117));
        f.clock.set(Instant.parse("2026-11-13T14:00:00Z"));
        f.runs.runAll(Instant.parse("2026-11-13T05:00:00Z"), Instant.parse("2026-11-13T13:00:00Z"), false);
        assertThat(status("TIMING_DIFFERENCE", f.paymentId("ch_p4"))).isEqualTo("EXPLAINED");
        assertThat(f.runs.get(
                        java.util.Objects.requireNonNull(reports.getFirst().id())))
                .contains(reports.getFirst());
    }

    String status(String category, String subject) {
        return f.jdbc.sql("SELECT status FROM recon.issue WHERE category=? AND subject_id=?")
                .params(category, subject)
                .query(String.class)
                .single();
    }

    @Test
    void ac_5_9_agingEscalatesProcessorAndBankTimingAtExactDueTime() {
        f.payment("ch_unsettled", 3000, ProcessorRuleTest.CAPTURED);
        f.payment("ch_settled", 10000, ProcessorRuleTest.CAPTURED);
        f.batch("po_b1", ProcessorRuleTest.CAPTURED, List.of(f.localLine("txn_l1", "ch_settled", 10000, 320)));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        f.runs.runAll(ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(status("TIMING_DIFFERENCE", "po_b1")).isEqualTo("OPEN");
        Instant due = Instant.parse("2026-11-14T05:00:00Z");
        f.clock.set(due.plusSeconds(120));
        var money = f.money();
        var dry = f.runs.runAll(due, due, true);
        assertThat(status("TIMING_DIFFERENCE", "po_b1")).isEqualTo("OPEN");
        var reports = f.runs.runAll(due, due, false);
        assertThat(reports.get(1).findings()).isEmpty();
        assertThat(reports.get(2).findings()).hasSize(2).isEqualTo(dry.get(2).findings());
        assertThat(status("TIMING_DIFFERENCE", f.paymentId("ch_unsettled"))).isEqualTo("EXPLAINED");
        assertThat(status("TIMING_DIFFERENCE", "po_b1")).isEqualTo("EXPLAINED");
        assertThat(status("PAYMENT_NOT_SETTLED", f.paymentId("ch_unsettled"))).isEqualTo("OPEN");
        assertThat(status("DEPOSIT_NOT_RECEIVED", "po_b1")).isEqualTo("OPEN");
        assertThat(f.jdbc.sql("SELECT explanation FROM recon.issue WHERE category='TIMING_DIFFERENCE'")
                        .query(String.class)
                        .list())
                .allMatch(s -> s.contains("已升级为"));
        assertThat(f.money()).isEqualTo(money);
        f.clock.set(due.plusSeconds(240));
        statement("2026-11-14", 1, 0, 9680, credit("bt_late", 9680, "po_b1", "2026-11-14"));
        f.clock.set(due.plusSeconds(360));
        run(RunRequest.Kind.AGING, due, due.plusSeconds(241), false);
        assertThat(status("DEPOSIT_NOT_RECEIVED", "po_b1")).isEqualTo("EXPLAINED");
        assertThat(run(RunRequest.Kind.AGING, due, due, true).findings())
                .isEqualTo(reports.get(2).findings());
    }

    @Test
    void ac_5_20_waitingBalanceExplainsAfterConfirmationAndOldEvidenceReplays() {
        f.payment("ch_p1", 10000, ProcessorRuleTest.CAPTURED);
        statement("2026-11-10", 1, 0, 9680, credit("bt_early", 9680, "po_b1", "2026-11-10"));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var first = run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(first.findings()).hasSize(1);
        assertThat(f.finding(first, "TIMING_DIFFERENCE").dueAt()).isEqualTo(Instant.parse("2026-11-14T05:00:00Z"));
        assertThat(first.summary()).doesNotContainKey("BANK_BALANCE_MISMATCH");
        f.batch("po_b1", ProcessorRuleTest.CAPTURED, List.of(f.localLine("txn_early", "ch_p1", 10000, 320)));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(240));
        assertThat(run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true)
                        .findings())
                .isEqualTo(first.findings());
        assertThat(run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF.plusSeconds(62), false)
                        .findings())
                .isEmpty();
        assertThat(status("TIMING_DIFFERENCE", "SIM-CAD-001:2026-11-10:2026-11-10"))
                .isEqualTo("EXPLAINED");
    }

    @Test
    void ac_5_9_ingestionIdentityIsReusedAndDispositionIsNeverChanged() {
        f.payment("ch_p1", 10000, ProcessorRuleTest.CAPTURED);
        f.batch("po_b1", ProcessorRuleTest.CAPTURED, List.of(f.localLine("txn_l1", "ch_p1", 10000, 320)));
        statement("2026-11-10", 1, 0, 9679, credit("bt_wrong", 9679, "po_b1", "2026-11-10"));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var report = run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(report.summary())
                .containsEntry("DEPOSIT_AMOUNT_MISMATCH", 1L)
                .containsEntry("UNAPPLIED_CASH_OPEN", 1L);
        String issue = f.jdbc.sql("SELECT id FROM recon.issue WHERE category='DEPOSIT_AMOUNT_MISMATCH'")
                .query(String.class)
                .single();
        var manager = new DataSourceTransactionManager(f.db.app());
        new IssueStore(f.jdbc, IdGenerator.seeded(f.clock, 9999), f.clock, manager).accept(issue, "reviewed");
        assertThat(run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false)
                        .findings())
                .isEqualTo(report.findings());
        assertThat(status("DEPOSIT_AMOUNT_MISMATCH", "bt_wrong")).isEqualTo("ACCEPTED");
        assertThat(f.jdbc.sql("SELECT DISTINCT issue_id FROM recon.finding WHERE category='DEPOSIT_AMOUNT_MISMATCH'")
                        .query(String.class)
                        .list())
                .containsExactly(issue);
        assertThat(f.jdbc.sql("SELECT detected_by FROM recon.issue WHERE id=?")
                        .param(issue)
                        .query(String.class)
                        .single())
                .isEqualTo("INGESTION");
    }

    @Test
    void ac_5_12_latestStatementAndCumulativeCashUseBookingDateNotEntryTime() {
        statement("2026-11-09", 1, 0, 500, credit("bt_a", 500, "unknown", "2026-11-09"));
        statement("2026-11-10", 1, 500, 800, credit("bt_b", 300, "unknown", "2026-11-10"));
        statement("2026-11-10", 2, 501, 801, credit("bt_b", 300, "unknown", "2026-11-10"));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var report = run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(report.summary())
                .containsEntry("BANK_BALANCE_MISMATCH", 1L)
                .containsEntry("UNAPPLIED_CASH_OPEN", 2L)
                .containsEntry("UNMATCHED_BANK_LINE", 2L);
        assertThat(f.finding(report, "BANK_BALANCE_MISMATCH").actual()).contains("\"cash\":800", "\"difference\":1");
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(120));
        statement("2026-11-10", 3, 500, 800, credit("bt_b", 300, "unknown", "2026-11-10"));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(240));
        run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF.plusSeconds(121), false);
        assertThat(status("BANK_BALANCE_MISMATCH", "SIM-CAD-001:2026-11-10:2026-11-10"))
                .isEqualTo("EXPLAINED");
    }

    @Test
    void ac_5_9_heldUnknownPaymentsArePerReferenceAndUseHistoricalReasonAndFetch() {
        Instant old = Instant.parse("2026-11-05T16:00:00Z");
        f.batch(
                "po_held",
                old,
                List.of(
                        f.localLine("txn_a", "ch_absent_a", 10000, 320),
                        f.localLine("txn_b", "ch_absent_b", 5000, 175)));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var first = run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(first.findings())
                .hasSize(2)
                .allMatch(o -> o.finding().category().equals("MISSING_IN_LEDGER"));
        assertThat(first.inputHashes().keySet()).anyMatch(k -> k.startsWith("processor_fetch:"));
        f.payment("ch_absent_a", 10000, old);
        // A different later hold reason must not leak into an older snapshot.
        f.jdbc.sql("""
                UPDATE core.settlement_batch SET hold_reason='AMOUNT_MISMATCH' WHERE batch_id='po_held'
                """).update();
        f.jdbc.sql("""
                INSERT INTO core.batch_transition(subject_id,from_status,to_status,hold_reason,evidence_type,evidence_id,occurred_at,recorded_at)
                VALUES ('po_held','HELD','HELD','AMOUNT_MISMATCH','fixture','later',?,?)
                """)
                .params(
                        f.clock.instant().atOffset(java.time.ZoneOffset.UTC),
                        f.clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .update();
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(240));
        assertThat(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true)
                        .findings())
                .isEqualTo(first.findings());
        var later =
                run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF.plusSeconds(62), false);
        assertThat(later.findings()).noneMatch(o -> o.finding().category().equals("MISSING_IN_LEDGER"));
        assertThat(status("MISSING_IN_LEDGER", "ch_absent_a")).isEqualTo("EXPLAINED");
        assertThat(status("MISSING_IN_LEDGER", "ch_absent_b")).isEqualTo("EXPLAINED");
    }

    @Test
    void ac_1_13_historyRequiresHeldReasonAndKeepsRoleBoundaries() {
        f.batch("po_held", ProcessorRuleTest.CAPTURED, List.of(f.localLine("txn_a", "ch_absent", 10000, 320)));
        var readonly = org.springframework.jdbc.core.simple.JdbcClient.create(f.db.readonly());
        assertThat(readonly.sql("SELECT hold_reason FROM core.v_batch_transition WHERE to_status='HELD'")
                        .query(String.class)
                        .single())
                .isEqualTo("UNKNOWN_PAYMENT");
        assertThat(readonly.sql("SELECT lines_fetch_id FROM core.v_batch_transition WHERE to_status='HELD'")
                        .query(Long.class)
                        .single())
                .isPositive();
        String insert = """
                INSERT INTO core.batch_transition(subject_id,from_status,to_status,evidence_type,evidence_id,occurred_at,recorded_at)
                VALUES ('po_held','RECEIVED','HELD','fixture','bad','2026-11-10T16:00Z','2026-11-10T16:00Z')
                """;
        dev.ledgerlab.testsupport.Sql.assertRejected("23514", f.db.app(), insert);
        dev.ledgerlab.testsupport.Sql.assertRejected("23503", f.db.app(), """
                INSERT INTO core.batch_transition(subject_id,to_status,evidence_type,evidence_id,occurred_at,recorded_at,lines_fetch_id)
                VALUES ('po_held','RECEIVED','fixture','bad','2026-11-10T16:00Z','2026-11-10T16:00Z',99999)
                """);
        dev.ledgerlab.testsupport.Sql.assertRejected(
                "42501", f.db.app(), "UPDATE core.batch_transition SET hold_reason='x'");
        dev.ledgerlab.testsupport.Sql.assertRejected(
                "42501", f.db.readonly(), "SELECT hold_reason FROM core.batch_transition");
        dev.ledgerlab.testsupport.Sql.assertRejected(
                "42501", f.db.readonly(), "UPDATE core.v_batch_transition SET hold_reason='x'");
        dev.ledgerlab.testsupport.Sql.assertRejected(
                "LL004", f.db.migrator(), "UPDATE core.batch_transition SET hold_reason='x'");
    }

    @Test
    void ac_5_9_pendingAndExactEvidenceBoundaryDoNotBorrowLaterBookedVersion() {
        String row = credit("bt_pending", 500, "unknown", "2026-11-10");
        String pending = statement("2026-11-10", 1, 0, 0, row.replace("BOOKED", "PENDING"));
        f.clock.set(ProcessorRuleTest.AS_OF);
        statement("2026-11-10", 2, 0, 500, row);
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(120));
        assertThat(run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true)
                        .findings())
                .isEmpty();
        var booked = run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF.plusSeconds(1), true);
        assertThat(booked.summary())
                .containsEntry("UNAPPLIED_CASH_OPEN", 1L)
                .doesNotContainKey("BANK_BALANCE_MISMATCH");
        var pinned = f.runs.run(new RunRequest(
                RunRequest.Kind.BANK,
                ProcessorRuleTest.CUTOFF,
                ProcessorRuleTest.AS_OF.plusSeconds(1),
                "p3-v1",
                List.of(pending),
                true));
        assertThat(pinned.summary())
                .containsEntry("BANK_BALANCE_MISMATCH", 1L)
                .doesNotContainKey("UNAPPLIED_CASH_OPEN");
    }

    @Test
    void ac_5_12_foreignDebitAndWaitingOnlyExplainAnExactPositiveDifference() {
        statement(
                "2026-11-10",
                1,
                0,
                9750,
                credit("bt_wait", 9680, "po_missing", "2026-11-10"),
                credit("bt_foreign", 100, "unknown", "2026-11-10").replace(",CAD,", ",USD,"),
                credit("bt_debit", 30, "unknown", "2026-11-10").replace("CREDIT", "DEBIT"));
        f.clock.set(ProcessorRuleTest.AS_OF.plusSeconds(61));
        var report = run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true);
        assertThat(report.summary())
                .containsEntry("BANK_BALANCE_MISMATCH", 1L)
                .containsEntry("UNMATCHED_BANK_LINE", 2L)
                .doesNotContainKey("TIMING_DIFFERENCE");
        assertThat(f.finding(report, "BANK_BALANCE_MISMATCH").actual()).contains("9750", "9680");
    }

    @Test
    void ac_5_11_failedPayoutHasNoDepositObligationAndCutoffExcludesNewPayment() {
        f.payment("ch_p1", 10000, ProcessorRuleTest.CAPTURED);
        f.payment("ch_boundary", 1000, ProcessorRuleTest.CUTOFF);
        f.batch("po_b1", ProcessorRuleTest.CAPTURED, List.of(f.localLine("txn_l1", "ch_p1", 10000, 320)));
        f.inbox("evt_failure");
        f.tx.executeWithoutResult(ignored -> f.settlement.process(new dev.ledgerlab.settlement.PayoutEvidence(
                "evt_failure",
                "fixture",
                "evt_failure",
                "payout.failed",
                "po_b1",
                9680,
                "cad",
                true,
                "standard",
                "completed",
                ProcessorRuleTest.CAPTURED,
                ProcessorRuleTest.CAPTURED,
                null)));
        f.clock.set(Instant.parse("2026-11-14T06:00:00Z"));
        assertThat(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, Instant.parse("2026-11-14T05:00:00Z"), true)
                        .findings())
                .isEmpty();
        assertThat(run(RunRequest.Kind.BANK, ProcessorRuleTest.CUTOFF, Instant.parse("2026-11-14T05:00:00Z"), true)
                        .findings())
                .isEmpty();
    }

    @Test
    void ac_5_11_configuredThresholdAlsoControlsProcessorTiming() {
        var calendar = new BusinessCalendar();
        var json = new ReconJson(f.mapper);
        var policy = new AgingPolicy(3, 4, 5, java.time.Duration.ofHours(1));
        f.payment("ch_config", 10000, ProcessorRuleTest.CAPTURED);
        var processor = new ProcessorRule(f.jdbc, calendar, json, f.mapper, policy);
        var context = new ReconRule.Context(
                new RunRequest(
                        RunRequest.Kind.PROCESSOR,
                        Instant.parse("2026-11-14T05:00:00Z"),
                        Instant.parse("2026-11-14T13:00:00Z"),
                        "processor-v1",
                        List.of(),
                        true),
                java.util.Map.of());
        assertThat(processor.evaluate(context))
                .singleElement()
                .satisfies(v -> assertThat(v.dueAt()).isEqualTo(Instant.parse("2026-11-17T05:00:00Z")));
        for (int[] values : List.of(new int[] {0, 2, 2}, new int[] {2, 0, 2}, new int[] {2, 2, 0}))
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AgingPolicy(values[0], values[1], values[2], java.time.Duration.ofHours(1)));
        org.assertj.core.api.Assertions.assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> BankEvidence.bounded(9007199254740992L));
        org.assertj.core.api.Assertions.assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> BankEvidence.bounded(-9007199254740992L));
    }
}
