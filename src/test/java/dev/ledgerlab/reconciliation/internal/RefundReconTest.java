package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefundReconTest {
    final ProcessorRuleTest f = new ProcessorRuleTest();
    final BusinessCalendar calendar = new BusinessCalendar();
    IdGenerator ids;
    String payment;
    static final Instant START = ProcessorRuleTest.CAPTURED;

    @BeforeEach
    void setup() {
        f.setup();
        ids = IdGenerator.seeded(f.clock, 311);
        var json = new ReconJson(f.mapper);
        var policy = new AgingPolicy(2, 2, 2, Duration.ofHours(1), 2, 5, 3);
        var bank = new BankEvidence(f.jdbc);
        f.runs = new ReconRuns(
                f.jdbc,
                f.manager,
                f.clock,
                ids,
                new ReconProperties(Duration.ofSeconds(60), "p3-v2"),
                List.of(
                        new ProcessorRule(f.jdbc, calendar, json, f.mapper, policy),
                        new BankRule(bank, calendar, policy, json, f.jdbc),
                        new AgingRule(f.jdbc, calendar, policy, bank, json, f.mapper)),
                json,
                f.mapper,
                new IssueStore(f.jdbc, ids, f.clock, f.manager));
        f.payment("ch_refunds", 10000, START);
        payment = f.paymentId("ch_refunds");
        f.batch("po_original", START, List.of(f.localLine("txn_payment", "ch_refunds", 10000, 320)));
        snapshot("");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    String refund(@Nullable String ref, String status, long amount, Instant accepted) {
        String id = ids.next("rfd");
        f.jdbc.sql("""
                INSERT INTO core.refund(id,payment_id,merchant_refund_ref,amount,status,
                    processor_refund_ref,initial_result,request_fingerprint,accepted_at,recorded_at,resolved_at)
                VALUES (:id,:payment,:id,:amount,:status,:ref,'{}','fixture',:accepted,:now,:resolved)
                """)
                .param("id", id)
                .param("payment", payment)
                .param("amount", amount)
                .param("status", status)
                .param("ref", ref)
                .param("accepted", accepted.atOffset(ZoneOffset.UTC))
                .param("now", f.clock.instant().atOffset(ZoneOffset.UTC))
                .param(
                        "resolved",
                        List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)
                                ? f.clock.instant().atOffset(ZoneOffset.UTC)
                                : null)
                .update();
        transition(id, null, status, accepted);
        return id;
    }

    void transition(String id, @Nullable String from, String to, Instant occurred) {
        f.jdbc.sql("""
                INSERT INTO core.refund_transition(subject_id,from_status,to_status,evidence_type,evidence_id,
                    evidence_source,evidence_ref,occurred_at,recorded_at)
                VALUES (:id,:from,:to,'fixture','fixture','COMMAND_RESPONSE','fixture',:occurred,:now)
                """)
                .param("id", id)
                .param("from", from)
                .param("to", to)
                .param("occurred", occurred.atOffset(ZoneOffset.UTC))
                .param("now", f.clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    void sent(String id, Instant at) {
        f.jdbc.sql("""
                INSERT INTO io.outbound_command(id,kind,subject_id,request_id,status,next_attempt_at,
                    first_sent_at,created_at,recorded_at)
                VALUES (?,'SUBMIT_REFUND',?,?,'UNKNOWN',?,?,?,?)
                """)
                .params(
                        ids.next("cmd"),
                        id,
                        id,
                        at.atOffset(ZoneOffset.UTC),
                        at.atOffset(ZoneOffset.UTC),
                        f.clock.instant().atOffset(ZoneOffset.UTC),
                        f.clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    String row(String line, String ref, long amount) {
        return f.line(line, ref, -amount, 0).replace("\"charge\"", "\"refund\"");
    }

    void snapshot(String refunds) {
        f.snapshot(
                refunds.isEmpty() ? "po_original" : "po_refunds",
                LocalDate.parse("2026-11-10"),
                refunds.isEmpty() ? f.line("txn_payment", "ch_refunds", 10000, 320) : refunds);
    }

    RunReport run(RunRequest.Kind kind, Instant cutoff, Instant asOf, boolean dry) {
        f.clock.set(asOf.plusSeconds(61));
        return f.runs.run(new RunRequest(
                kind, cutoff, asOf, kind == RunRequest.Kind.PROCESSOR ? "processor-v2" : "p3-v2", List.of(), dry));
    }

    List<Finding> refunds(RunReport report) {
        return report.findings().stream()
                .map(RunReport.ObservedFinding::finding)
                .filter(v -> v.subjectType().equals("refund"))
                .toList();
    }

    String status(String category, String id) {
        return f.jdbc.sql("SELECT status FROM recon.issue WHERE category=? AND subject_id=?")
                .params(category, id)
                .query(String.class)
                .single();
    }

    @Test
    void ac_5_14_partialRefundsMatchIndividuallyAndUnknownAndWrongAmountsAreReported() {
        String first = refund("re_first", "SUCCEEDED", 1000, START);
        refund("re_second", "SUCCEEDED", 2000, START);
        f.clock.set(START.plusSeconds(1));
        snapshot(row("txn_r1", "re_first", 1000) + "," + row("txn_r2", "re_second", 2000));
        var before = f.money();
        var clean = run(RunRequest.Kind.PROCESSOR, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true);
        assertThat(refunds(clean)).isEmpty();
        assertThat(clean.findings()).isEmpty();
        assertThat(clean.summary()).doesNotContainKey("DUPLICATE_STATEMENT_LINE");
        snapshot(row("txn_wrong", "re_first", 999) + "," + row("txn_unknown", "re_unknown", 500));
        var bad = run(
                RunRequest.Kind.PROCESSOR,
                ProcessorRuleTest.CUTOFF,
                f.clock.instant().plusSeconds(1),
                true);
        assertThat(refunds(bad))
                .anySatisfy(v -> {
                    assertThat(v.category()).isEqualTo("AMOUNT_MISMATCH");
                    assertThat(v.subjectId()).isEqualTo(first);
                })
                .anySatisfy(v -> {
                    assertThat(v.category()).isEqualTo("MISSING_IN_LEDGER");
                    assertThat(v.subjectId()).isEqualTo("re_unknown");
                });
        assertThat(f.money()).isEqualTo(before);
    }

    @Test
    void ac_5_14_missingExecutedRefundEscalatesAtExactDueAndLaterEvidenceExplainsIt() {
        String id = refund("re_missing", "SUCCEEDED", 2000, START);
        var early = run(RunRequest.Kind.PROCESSOR, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, false);
        assertThat(refunds(early)).singleElement().satisfies(v -> {
            assertThat(v.category()).isEqualTo("TIMING_DIFFERENCE");
            assertThat(v.dueAt()).isEqualTo(Instant.parse("2026-11-14T05:00:00Z"));
        });
        Instant due = Instant.parse("2026-11-14T05:00:00Z");
        var aged = run(RunRequest.Kind.PROCESSOR, due, due, false);
        assertThat(refunds(aged))
                .singleElement()
                .satisfies(v -> assertThat(v.category()).isEqualTo("REFUND_NOT_IN_STATEMENT"));
        assertThat(status("TIMING_DIFFERENCE", id)).isEqualTo("EXPLAINED");
        assertThat(status("REFUND_NOT_IN_STATEMENT", id)).isEqualTo("OPEN");
        assertThat(f.jdbc.sql("SELECT explanation FROM recon.issue WHERE category='TIMING_DIFFERENCE' AND subject_id=?")
                        .param(id)
                        .query(String.class)
                        .single())
                .contains("已升级为 REFUND_NOT_IN_STATEMENT");
        snapshot(row("txn_late", "re_missing", 2000));
        Instant later = f.clock.instant().plusSeconds(1);
        assertThat(refunds(run(RunRequest.Kind.PROCESSOR, due, later, false))).isEmpty();
        assertThat(status("REFUND_NOT_IN_STATEMENT", id)).isEqualTo("EXPLAINED");
        assertThat(run(RunRequest.Kind.PROCESSOR, due, due, true).findings()).isEqualTo(aged.findings());
        assertThat(f.runs.get(java.util.Objects.requireNonNull(early.id()))
                        .orElseThrow()
                        .findings())
                .isEqualTo(early.findings());
    }

    @Test
    void ac_3_10_unknownAndReadySentAgeFromFirstSendWithoutChangingBusinessState() {
        String unknown = refund(null, "UNKNOWN", 2000, START);
        String ready = refund(null, "READY", 1000, START);
        refund(null, "READY", 500, START);
        sent(unknown, START);
        sent(ready, START);
        var before = f.money();
        Instant asOf = START.plus(Duration.ofDays(7));
        var report = run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, asOf, false);
        assertThat(refunds(report)).hasSize(2).allSatisfy(v -> {
            assertThat(v.category()).isEqualTo("REFUND_NOT_EXECUTED");
            assertThat(v.expected())
                    .contains(START.toString(), "2026-11-17T05:00:00Z", "threshold_business_days", "status");
        });
        assertThat(f.jdbc.sql("SELECT status FROM core.refund WHERE id=?")
                        .param(unknown)
                        .query(String.class)
                        .single())
                .isEqualTo("UNKNOWN");
        assertThat(f.money()).isEqualTo(before);
        f.clock.set(asOf.plusSeconds(120));
        transition(unknown, "UNKNOWN", "SUCCEEDED", START.plusSeconds(60));
        f.jdbc.sql("UPDATE core.refund SET status='SUCCEEDED',processor_refund_ref='re_late',resolved_at=? WHERE id=?")
                .params(f.clock.instant().atOffset(ZoneOffset.UTC), unknown)
                .update();
        assertThat(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, asOf, true)
                        .findings())
                .isEqualTo(report.findings());
        run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, asOf.plusSeconds(121), false);
        assertThat(status("REFUND_NOT_EXECUTED", unknown)).isEqualTo("EXPLAINED");
    }

    @Test
    void ac_5_9_waitingFundsUsesHistoricalStatusAndEvidenceTimeWithExactBoundary() {
        String waiting = refund(null, "AWAITING_FUNDS", 2000, START);
        String released = refund(null, "AWAITING_FUNDS", 1000, START);
        Instant due = Instant.parse("2026-11-19T05:00:00Z");
        f.clock.set(START.plusSeconds(60));
        transition(released, "AWAITING_FUNDS", "READY", f.clock.instant());
        f.jdbc.sql("UPDATE core.refund SET status='READY' WHERE id=?")
                .param(released)
                .update();
        assertThat(refunds(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, due.minusNanos(1000), true)))
                .isEmpty();
        var atDue = run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, due, false);
        assertThat(refunds(atDue)).singleElement().satisfies(v -> {
            assertThat(v.category()).isEqualTo("REFUND_AWAITING_FUNDS_AGED");
            assertThat(v.subjectId()).isEqualTo(waiting);
            assertThat(v.expected()).contains("AWAITING_FUNDS", START.toString(), due.toString());
        });
        f.clock.set(due);
        transition(waiting, "AWAITING_FUNDS", "READY", due);
        f.jdbc.sql("UPDATE core.refund SET status='READY' WHERE id=?")
                .param(waiting)
                .update();
        assertThat(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, due, true)
                        .findings())
                .isEqualTo(atDue.findings());
        assertThat(refunds(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, due.plusSeconds(1), false)))
                .isEmpty();
        assertThat(status("REFUND_AWAITING_FUNDS_AGED", waiting)).isEqualTo("EXPLAINED");
    }

    @Test
    void ac_5_9_laterFirstSendAndSuccessDoNotLeakIntoHistoricalRuns() {
        String id = refund(null, "READY", 1000, START);
        snapshot(row("txn_future_identity", "re_later", 1000));
        Instant oldAsOf = Instant.parse("2026-11-19T05:00:00Z");
        var old = run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, oldAsOf, true);
        var processor = run(RunRequest.Kind.PROCESSOR, ProcessorRuleTest.CUTOFF, oldAsOf, true);
        sent(id, oldAsOf);
        transition(id, "READY", "SUCCEEDED", START);
        f.jdbc.sql(
                        "UPDATE core.refund SET status='SUCCEEDED', processor_refund_ref='re_later', submitted_at=?, resolved_at=? WHERE id=?")
                .params(oldAsOf.atOffset(ZoneOffset.UTC), oldAsOf.atOffset(ZoneOffset.UTC), id)
                .update();
        assertThat(run(RunRequest.Kind.AGING, ProcessorRuleTest.CUTOFF, oldAsOf, true)
                        .findings())
                .isEqualTo(old.findings());
        assertThat(run(RunRequest.Kind.PROCESSOR, ProcessorRuleTest.CUTOFF, oldAsOf, true)
                        .findings())
                .isEqualTo(processor.findings());
    }

    @Test
    void ac_5_14_executionTimeAndEvidenceBoundariesExcludeLaterSuccess() {
        String id = refund("re_boundary", "READY", 500, START);
        Instant boundary = ProcessorRuleTest.CUTOFF;
        f.clock.set(ProcessorRuleTest.AS_OF);
        transition(id, "READY", "SUCCEEDED", boundary);
        f.jdbc.sql("UPDATE core.refund SET status='SUCCEEDED',resolved_at=? WHERE id=?")
                .params(ProcessorRuleTest.AS_OF.atOffset(ZoneOffset.UTC), id)
                .update();
        assertThat(refunds(run(RunRequest.Kind.PROCESSOR, boundary, ProcessorRuleTest.AS_OF, true)))
                .isEmpty();
        assertThat(refunds(run(RunRequest.Kind.PROCESSOR, boundary, ProcessorRuleTest.AS_OF.plusSeconds(1), true)))
                .isEmpty();
        Instant nextCutoff = Instant.parse("2026-11-12T05:00:00Z");
        assertThat(refunds(run(RunRequest.Kind.PROCESSOR, nextCutoff, ProcessorRuleTest.AS_OF.plusSeconds(1), true)))
                .singleElement()
                .satisfies(v -> assertThat(v.expected()).contains(boundary.toString()));
    }

    @Test
    void ac_5_14_duplicateRefundLineIsDetectedByLineIdentity() {
        refund("re_duplicate", "SUCCEEDED", 500, START);
        String row = row("txn_duplicate", "re_duplicate", 500);
        f.clock.set(START.plusSeconds(1));
        snapshot(row + "," + row);
        var report = run(RunRequest.Kind.PROCESSOR, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true);
        assertThat(report.findings()).singleElement().satisfies(v -> {
            assertThat(v.finding().category()).isEqualTo("DUPLICATE_STATEMENT_LINE");
            assertThat(v.finding().subjectId()).isEqualTo("txn_duplicate");
        });
    }

    @Test
    void ac_5_14_mixedBatchIncludesRefundNetAndBothPartialRefundsMatchWithoutDifferences() {
        var ledger = new dev.ledgerlab.ledger.internal.JdbcLedger(f.source, f.clock, ids);
        var history = new dev.ledgerlab.refunds.internal.RefundHistory(f.jdbc);
        var queue = new dev.ledgerlab.refunds.internal.JdbcRefundQueue(f.jdbc, ledger, f.clock, ids, history);
        var payments = new dev.ledgerlab.payments.PaymentRefunds(f.jdbc, ledger);
        var issues = new JdbcIngestionIssues(f.jdbc, ids, f.clock);
        var service = new dev.ledgerlab.refunds.internal.JdbcRefunds(
                f.jdbc, ledger, payments, queue, history, f.clock, ids, f.mapper, f.manager);
        var evidence = new dev.ledgerlab.refunds.internal.JdbcRefundEvidence(
                f.jdbc, ledger, payments, queue, history, issues, f.mapper, f.clock);
        String first = service.accept(payment, "partial_first", 1000, "first").refundId();
        String second =
                service.accept(payment, "partial_second", 2000, "second").refundId();
        f.tx.executeWithoutResult(ignored -> {
            evidence.apply(new dev.ledgerlab.refunds.ProcessorRefundFact(
                    first,
                    "re_first",
                    dev.ledgerlab.refunds.ProcessorRefundFact.Status.succeeded,
                    null,
                    dev.ledgerlab.refunds.ProcessorRefundFact.Source.COMMAND_RESPONSE,
                    "first",
                    START));
            evidence.apply(new dev.ledgerlab.refunds.ProcessorRefundFact(
                    second,
                    "re_second",
                    dev.ledgerlab.refunds.ProcessorRefundFact.Status.succeeded,
                    null,
                    dev.ledgerlab.refunds.ProcessorRefundFact.Source.COMMAND_RESPONSE,
                    "second",
                    START));
        });
        f.payment("ch_next", 10000, START);
        f.batch(
                "po_mixed",
                START,
                List.of(
                        f.localLine("txn_next", "ch_next", 10000, 320),
                        new dev.ledgerlab.settlement.PayoutEvidence.Line(
                                "txn_first", "refund", "re_first", -1000, 0, -1000, "cad", START),
                        new dev.ledgerlab.settlement.PayoutEvidence.Line(
                                "txn_second", "refund", "re_second", -2000, 0, -2000, "cad", START)));
        f.snapshot(
                "po_mixed",
                LocalDate.parse("2026-11-10"),
                f.line("txn_next", "ch_next", 10000, 320) + "," + row("txn_first", "re_first", 1000) + ","
                        + row("txn_second", "re_second", 2000));
        var before = f.money();
        var report = run(RunRequest.Kind.PROCESSOR, ProcessorRuleTest.CUTOFF, ProcessorRuleTest.AS_OF, true);
        assertThat(report.findings()).isEmpty();
        assertThat(f.money()).isEqualTo(before);
        f.snapshot(
                "po_mixed",
                LocalDate.parse("2026-11-10"),
                f.line("txn_next", "ch_next", 10000, 320) + "," + row("txn_first", "re_first", 1000) + ","
                        + row("txn_second", "re_second", 2001));
        var missing = run(
                RunRequest.Kind.PROCESSOR,
                ProcessorRuleTest.CUTOFF,
                f.clock.instant().plusSeconds(1),
                true);
        assertThat(f.finding(missing, "BATCH_TOTAL_MISMATCH").expected()).contains("6680");
        assertThat(f.finding(missing, "BATCH_TOTAL_MISMATCH").actual()).contains("6679");
    }

    @Test
    void ac_3_10_submissionFallbackAndConfiguredThresholdsUseHistoricalEvidence() {
        refund("re_submitted", "SUBMITTED", 500, START);
        refund(null, "AWAITING_FUNDS", 500, START);
        refund("re_success", "SUCCEEDED", 500, START);
        var policy = new AgingPolicy(2, 2, 2, Duration.ofHours(1), 4, 1, 1);
        var json = new ReconJson(f.mapper);
        var context = new ReconRule.Context(
                new RunRequest(
                        RunRequest.Kind.AGING,
                        ProcessorRuleTest.CUTOFF,
                        Instant.parse("2026-11-13T05:00:00Z"),
                        "p3-v2",
                        List.of(),
                        true),
                java.util.Map.of());
        var aging = new AgingRule(f.jdbc, calendar, policy, new BankEvidence(f.jdbc), json, f.mapper);
        assertThat(aging.evaluate(context).stream().filter(v -> v.subjectType().equals("refund")))
                .hasSize(2)
                .allSatisfy(v -> assertThat(v.expected()).contains("\"threshold_business_days\":1", START.toString()));
        var processor = new ProcessorRule(f.jdbc, calendar, json, f.mapper, policy);
        assertThat(processor.evaluate(context).stream()
                        .filter(v -> v.subjectType().equals("refund")))
                .singleElement()
                .satisfies(v -> assertThat(v.dueAt()).isEqualTo(Instant.parse("2026-11-18T05:00:00Z")));
        for (int[] days : List.of(new int[] {0, 5, 3}, new int[] {2, 0, 3}, new int[] {2, 5, 0}))
            org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AgingPolicy(2, 2, 2, Duration.ofHours(1), days[0], days[1], days[2]));
    }

    @Test
    void ac_5_14_allIncludesRefundStatementAndAgingRules() {
        refund("re_executed", "SUCCEEDED", 500, START);
        refund(null, "AWAITING_FUNDS", 500, START);
        String pending = refund("re_pending", "SUBMITTED", 500, START);
        sent(pending, START);
        Instant due = Instant.parse("2026-11-19T05:00:00Z");
        f.clock.set(due.plusSeconds(61));
        var reports = f.runs.runAll(due, due, true);
        assertThat(reports.stream().flatMap(r -> refunds(r).stream()).map(Finding::category))
                .containsExactlyInAnyOrder(
                        "REFUND_NOT_IN_STATEMENT", "REFUND_AWAITING_FUNDS_AGED", "REFUND_NOT_EXECUTED");
    }
}
