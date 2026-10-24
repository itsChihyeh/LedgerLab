package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.payments.PaymentSettlement;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.settlement.BatchSettlement;
import dev.ledgerlab.settlement.PayoutEvidence;
import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ProcessorRuleTest {
    static final Instant CAPTURED = Instant.parse("2026-11-10T16:00:00Z");
    static final Instant CUTOFF = Instant.parse("2026-11-11T05:00:00Z");
    static final Instant AS_OF = Instant.parse("2026-11-12T13:00:00Z");
    final MutableClock clock = new MutableClock(CAPTURED);
    final JsonMapper mapper = JsonMapper.builder().build();
    TestDatabase.Handle db;
    javax.sql.DataSource source;
    DataSourceTransactionManager manager;
    JdbcClient jdbc;
    TransactionTemplate tx;
    PaymentCapture capture;
    BatchSettlement settlement;
    ProcessorSnapshots snapshots;
    ReconRuns runs;

    @BeforeEach
    void setup() {
        db = TestDatabase.freshMigrated();
        source = db.app();
        jdbc = JdbcClient.create(source);
        manager = new DataSourceTransactionManager(source);
        tx = new TransactionTemplate(manager);
        var ids = IdGenerator.seeded(clock, 1007);
        var ledger = new JdbcLedger(source, clock, ids);
        var issues = new JdbcIngestionIssues(jdbc, ids, clock);
        var json = new ReconJson(mapper);
        var calendar = new BusinessCalendar();
        capture = new PaymentCapture(ledger, jdbc, issues, ids, clock, mapper);
        settlement = new BatchSettlement(
                jdbc,
                ledger,
                new PaymentSettlement(jdbc),
                issues,
                clock,
                mapper,
                new BankStatements(jdbc, ledger, issues, clock, ids, calendar, mapper, manager, 2),
                new dev.ledgerlab.refunds.internal.JdbcRefundQueue(
                        jdbc, ledger, clock, ids, new dev.ledgerlab.refunds.internal.RefundHistory(jdbc)));
        snapshots = new ProcessorSnapshots(jdbc, manager, clock, ids, calendar, mapper, json, issues);
        runs = new ReconRuns(
                jdbc,
                manager,
                clock,
                ids,
                new ReconProperties(Duration.ofSeconds(60), "processor-v1"),
                List.of(new ProcessorRule(
                        jdbc, calendar, json, mapper, new AgingPolicy(2, 2, 2, java.time.Duration.ofHours(1)))),
                json,
                mapper,
                new IssueStore(jdbc, ids, clock, manager));
    }

    @AfterEach
    void cleanup() {
        db.drop();
    }

    void inbox(String id) {
        jdbc.sql("""
                INSERT INTO io.inbox_event (event_id, event_type, api_version, livemode, payload, payload_sha256,
                    signed_at, occurred_at, received_at, recorded_at, status, next_attempt_at)
                VALUES (?, 'fixture', '2026-09-30.endive', false, '{}'::bytea, encode(sha256('{}'::bytea),'hex'),
                    ?, ?, ?, ?, 'RECEIVED', ?)
                """)
                .params(
                        id,
                        clock.instant().atOffset(ZoneOffset.UTC),
                        clock.instant().atOffset(ZoneOffset.UTC),
                        clock.instant().atOffset(ZoneOffset.UTC),
                        clock.instant().atOffset(ZoneOffset.UTC),
                        clock.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    void payment(String ref, long gross, Instant occurred) {
        String event = "evt_" + ref;
        inbox(event);
        tx.executeWithoutResult(ignored -> capture.capture(new ChargeEvidence(
                event, ref, null, gross, gross, "cad", true, "succeeded", false, occurred, clock.instant())));
    }

    PayoutEvidence.Line localLine(String id, String ref, long gross, long fee) {
        return new PayoutEvidence.Line(id, "charge", ref, gross, fee, gross - fee, "cad", CAPTURED);
    }

    void batch(String batch, Instant created, List<PayoutEvidence.Line> lines) {
        String event = "evt_" + batch;
        inbox(event);
        String body = mapper.writeValueAsString(List.of(ProcessorSnapshotsTest.page(
                lines.stream()
                        .map(l -> line(l.id(), Objects.requireNonNull(l.source()), l.gross(), l.fee()))
                        .collect(java.util.stream.Collectors.joining(",")),
                false)));
        long fetch = jdbc.sql("""
                INSERT INTO io.processor_fetch (purpose, request, response_body, response_sha256, complete, fetched_at, recorded_at)
                VALUES ('BATCH_LINES', '{}', ?, encode(sha256(convert_to(?,'UTF8')),'hex'), true, ?, ?) RETURNING id
                """)
                .params(
                        body,
                        body,
                        clock.instant().atOffset(ZoneOffset.UTC),
                        clock.instant().atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        long net = lines.stream().mapToLong(PayoutEvidence.Line::net).reduce(0, Math::addExact);
        tx.executeWithoutResult(ignored -> settlement.process(new PayoutEvidence(
                event,
                "fixture",
                event,
                "payout.reconciliation_completed",
                batch,
                net,
                "cad",
                true,
                "standard",
                "completed",
                created,
                created,
                new PayoutEvidence.Lines(fetch, lines))));
    }

    String line(String id, String ref, long gross, long fee) {
        return mapper.writeValueAsString(Map.of(
                "id",
                id,
                "type",
                "charge",
                "source",
                ref,
                "amount",
                gross,
                "fee",
                fee,
                "net",
                gross - fee,
                "currency",
                "cad",
                "created",
                CAPTURED.getEpochSecond()));
    }

    ProcessorSnapshots.Result snapshot(String batch, LocalDate date, String lines) {
        String payouts = ProcessorSnapshotsTest.PAYOUTS
                .replace("po_b1", batch)
                .replace("1794333600", Long.toString(date.atTime(18, 0).toEpochSecond(ZoneOffset.UTC)));
        return snapshots.importSnapshot(
                "sample", date, payouts, Map.of(batch, ProcessorSnapshotsTest.page(lines, false)), clock.instant());
    }

    RunReport run(Instant cutoff, Instant evidence, boolean dry) {
        return runs.run(new RunRequest(RunRequest.Kind.PROCESSOR, cutoff, evidence, "processor-v1", List.of(), dry));
    }

    void standard() {
        payment("ch_p1", 10000, CAPTURED);
        payment("ch_p2", 5000, CAPTURED);
        payment("ch_p3", 7000, CAPTURED);
        payment("ch_p6", 4000, CAPTURED);
        payment("ch_p4", 3000, Instant.parse("2026-11-11T04:50:00Z"));
        payment("ch_p5", 6000, Instant.parse("2026-11-05T16:00:00Z"));
        batch(
                "po_b1",
                CAPTURED.plusSeconds(3600),
                List.of(
                        localLine("txn_l1", "ch_p1", 10000, 320),
                        localLine("txn_l2", "ch_p2", 5000, 205),
                        localLine("txn_l3", "ch_p3", 7000, 233),
                        localLine("txn_l6", "ch_p6", 4000, 146)));
        clock.set(Instant.parse("2026-11-11T08:00:00Z"));
        snapshot(
                "po_b1",
                LocalDate.parse("2026-11-10"),
                String.join(
                        ",",
                        line("txn_l1", "ch_p1", 10000, 320),
                        line("txn_l2", "ch_p2", 5000, 205),
                        line("txn_l3", "ch_p3", 7000, 233),
                        line("txn_l3", "ch_p3", 7000, 233),
                        line("txn_l4", "ch_p9", 2500, 103)));
        clock.set(AS_OF.plusSeconds(61));
    }

    Map<String, List<String>> money() {
        return Map.of(
                "entries",
                jdbc.sql("SELECT to_jsonb(t)::text FROM ledger.journal_entry t ORDER BY id")
                        .query(String.class)
                        .list(),
                "postings",
                jdbc.sql("SELECT to_jsonb(t)::text FROM ledger.posting t ORDER BY id")
                        .query(String.class)
                        .list(),
                "accounts",
                jdbc.sql("SELECT to_jsonb(t)::text FROM ledger.account t ORDER BY code")
                        .query(String.class)
                        .list());
    }

    @Test
    void ac_5_7_standardSampleHasExactlySixProcessorFindingsWithoutLedgerWrites() {
        standard();
        var before = money();
        var report = run(CUTOFF, AS_OF, false);
        assertThat(report.summary())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "FEE_MISMATCH",
                        1L,
                        "DUPLICATE_STATEMENT_LINE",
                        1L,
                        "MISSING_IN_LEDGER",
                        1L,
                        "MISSING_IN_STATEMENT",
                        1L,
                        "BATCH_TOTAL_MISMATCH",
                        1L,
                        "TIMING_DIFFERENCE",
                        1L));
        assertThat(report.findings()).hasSize(6);
        var fee = finding(report, "FEE_MISMATCH");
        assertThat(fee.expected()).contains("175");
        assertThat(fee.actual()).contains("205");
        assertThat(fee.evidenceRefs()).contains("po_b1", "txn_l2", "pay_", "je_");
        assertThat(finding(report, "BATCH_TOTAL_MISMATCH").expected()).contains("25096");
        assertThat(finding(report, "BATCH_TOTAL_MISMATCH").actual()).contains("23639", "1457");
        assertThat(finding(report, "TIMING_DIFFERENCE").dueAt()).isEqualTo(Instant.parse("2026-11-14T05:00:00Z"));
        assertThat(finding(report, "DUPLICATE_STATEMENT_LINE").subjectId()).isEqualTo("txn_l3");
        assertThat(finding(report, "MISSING_IN_LEDGER").subjectId()).isEqualTo("ch_p9");
        assertThat(finding(report, "MISSING_IN_STATEMENT").subjectId()).isEqualTo(paymentId("ch_p6"));
        assertThat(run(CUTOFF, AS_OF, true).findings()).isEqualTo(report.findings());
        assertThat(money()).isEqualTo(before);
        assertThat(report.getLimitations()).anyMatch(s -> s.contains("REFUND"));
    }

    String paymentId(String ref) {
        return jdbc.sql("SELECT id FROM core.payment WHERE processor_payment_ref=?")
                .param(ref)
                .query(String.class)
                .single();
    }

    Finding finding(RunReport report, String category) {
        return report.findings().stream()
                .map(RunReport.ObservedFinding::finding)
                .filter(f -> f.category().equals(category))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void ac_5_9_secondSnapshotExplainsTimingAndOldEvidenceStillReplaysExactly() {
        standard();
        var first = run(CUTOFF, AS_OF, false);
        assertThat(run(CUTOFF, AS_OF, false).findings()).isEqualTo(first.findings());
        assertThat(jdbc.sql("SELECT count(*) FROM recon.issue")
                        .query(Long.class)
                        .single())
                .isEqualTo(6);
        clock.set(Instant.parse("2026-11-12T16:00:00Z"));
        batch("po_b2", clock.instant(), List.of(localLine("txn_p4", "ch_p4", 3000, 117)));
        clock.set(Instant.parse("2026-11-13T08:00:00Z"));
        snapshot("po_b2", LocalDate.parse("2026-11-12"), line("txn_p4", "ch_p4", 3000, 117));
        clock.set(Instant.parse("2026-11-13T14:00:00Z"));
        assertThat(run(CUTOFF, AS_OF, false).findings()).isEqualTo(first.findings());
        var next = run(Instant.parse("2026-11-13T05:00:00Z"), Instant.parse("2026-11-13T13:00:00Z"), false);
        assertThat(next.summary()).doesNotContainKey("TIMING_DIFFERENCE");
        assertThat(jdbc.sql("SELECT status FROM recon.issue WHERE category='TIMING_DIFFERENCE'")
                        .query(String.class)
                        .single())
                .isEqualTo("EXPLAINED");
        assertThat(jdbc.sql(
                                "SELECT explanation || evidence_refs::text FROM recon.issue_transition WHERE reason='EXPLAINED'")
                        .query(String.class)
                        .single())
                .contains("po_b2", "txn_p4");
        assertThat(runs.get(Objects.requireNonNull(first.id()))).contains(first);
    }

    @Test
    void ac_5_9_latestIdentityDoesNotCountRepeatedSnapshotsAndRespectsEvidenceBoundary() {
        payment("ch_p1", 10000, CAPTURED);
        batch("po_b1", CAPTURED, List.of(localLine("txn_l1", "ch_p1", 10000, 320)));
        clock.set(Instant.parse("2026-11-11T08:00:00Z"));
        var old = snapshot("po_b1", LocalDate.parse("2026-11-10"), line("txn_l1", "ch_p1", 10000, 320));
        clock.set(AS_OF);
        var changed = snapshot("po_b1", LocalDate.parse("2026-11-10"), line("txn_l1", "ch_p1", 10000, 321));
        clock.set(AS_OF.plusSeconds(120));
        assertThat(run(CUTOFF, AS_OF, true).findings()).isEmpty();
        var latest = run(CUTOFF, AS_OF.plusSeconds(1), true);
        assertThat(latest.summary())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("AMOUNT_MISMATCH", 1L, "FEE_MISMATCH", 1L, "BATCH_TOTAL_MISMATCH", 1L));
        assertThat(finding(latest, "FEE_MISMATCH").evidenceRefs())
                .contains(changed.fileId())
                .doesNotContain(old.fileId());
        var explicit = runs.run(new RunRequest(
                RunRequest.Kind.PROCESSOR, CUTOFF, AS_OF.plusSeconds(1), "processor-v1", List.of(old.fileId()), true));
        assertThat(explicit.findings()).isEmpty();
        clock.set(AS_OF.plusSeconds(121));
        snapshot("po_b1", LocalDate.parse("2026-11-10"), line("txn_l1", "ch_p1", 10001, 320));
        clock.set(AS_OF.plusSeconds(240));
        assertThat(run(CUTOFF, AS_OF.plusSeconds(122), true).summary())
                .containsKey("AMOUNT_MISMATCH")
                .doesNotContainKey("FEE_MISMATCH");
    }

    @Test
    void ac_5_7_emptySnapshotChecksMissingAndZeroTotalWithoutInventingTimingForSettledPayment() {
        payment("ch_p1", 10000, CAPTURED);
        batch("po_b1", CAPTURED, List.of(localLine("txn_l1", "ch_p1", 10000, 320)));
        clock.set(Instant.parse("2026-11-11T08:00:00Z"));
        snapshot("po_b1", LocalDate.parse("2026-11-10"), "");
        clock.set(AS_OF.plusSeconds(61));
        assertThat(run(CUTOFF, AS_OF, false).summary())
                .containsExactlyInAnyOrderEntriesOf(Map.of("MISSING_IN_STATEMENT", 1L, "BATCH_TOTAL_MISMATCH", 1L));
    }

    @Test
    void ac_5_7_cutoffAgingRefundAndDistinctLineIdentitiesHaveSeparateScopes() {
        payment("ch_before", 10000, CAPTURED);
        payment("ch_boundary", 10000, CUTOFF);
        payment("ch_old", 10000, Instant.parse("2026-11-05T16:00:00Z"));
        clock.set(AS_OF.plusSeconds(61));
        var noEvidence = run(CUTOFF, AS_OF, true);
        assertThat(noEvidence.summary()).containsExactlyInAnyOrderEntriesOf(Map.of("TIMING_DIFFERENCE", 1L));
        assertThat(finding(noEvidence, "TIMING_DIFFERENCE").subjectId()).isEqualTo(paymentId("ch_before"));
        // An unsettled payment exactly at its due time belongs to AGING.
        clock.set(Instant.parse("2026-11-14T14:00:00Z"));
        assertThat(run(Instant.parse("2026-11-14T05:00:00Z"), Instant.parse("2026-11-14T13:00:00Z"), true)
                        .summary())
                .doesNotContainKey("TIMING_DIFFERENCE");
        String normal = line("txn_a", "ch_before", 10000, 320);
        String otherIdentity = line("txn_b", "ch_before", 10000, 320);
        String refund = line("txn_r", "re_1", -100, 0).replace("charge", "refund");
        String returned = line("txn_return", "po_failed", 9680, 0).replace("charge", "payout_failure");
        snapshot("po_b1", LocalDate.parse("2026-11-10"), String.join(",", normal, otherIdentity, refund, returned));
        clock.set(clock.instant().plusSeconds(120));
        assertThat(run(CUTOFF, clock.instant().minusSeconds(61), true).findings())
                .isEmpty();
    }
}
