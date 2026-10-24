package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class ReconRunsTest {
    private static final Instant NOW = Instant.parse("2026-11-13T14:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-11-13T05:00:00Z");
    private static final Instant EVIDENCE = Instant.parse("2026-11-13T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private TestDatabase.Handle db;
    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private IdGenerator ids;
    private IssueStore issues;
    private ReconRuns runs;
    private FakeRule rule;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        db = TestDatabase.freshMigrated();
        var source = db.app();
        jdbc = JdbcClient.create(source);
        manager = new DataSourceTransactionManager(source);
        ids = IdGenerator.seeded(CLOCK, 9);
        issues = new IssueStore(jdbc, ids, CLOCK, manager);
        rule = new FakeRule();
        runs = runner(List.of(rule));
    }

    @AfterEach
    void tearDown() {
        db.drop();
    }

    private ReconRuns runner(List<ReconRule> rules) {
        return new ReconRuns(
                jdbc,
                manager,
                CLOCK,
                ids,
                new ReconProperties(Duration.ofSeconds(60), "v1"),
                rules,
                new ReconJson(mapper),
                mapper,
                issues);
    }

    private RunRequest request(boolean dry) {
        return new RunRequest(rule.kind, CUTOFF, EVIDENCE, "v1", List.of(), dry);
    }

    private static Finding fee(String subject, long amount) {
        return new Finding(
                "FEE_MISMATCH", "payment", subject, "{\"fee\":175}", "{\"fee\":" + amount + "}", "[\"txn_p2\"]", null);
    }

    static final class FakeRule implements ReconRule {
        RunRequest.Kind kind = RunRequest.Kind.PROCESSOR;
        List<Finding> findings = List.of(fee("p2", 205));
        Map<Finding.Key, Explanation> scope = new LinkedHashMap<>();
        Consumer<Context> before = ignored -> {};

        FakeRule() {
            scope.put(findings.getFirst().key(), new Explanation("Matched later evidence", "[\"txn_later\"]"));
        }

        @Override
        public RunRequest.Kind kind() {
            return kind;
        }

        @Override
        public String version() {
            return "v1";
        }

        @Override
        public List<Finding> evaluate(Context context) {
            before.accept(context);
            return findings;
        }

        @Override
        public Map<Finding.Key, Explanation> checkedScope(Context context) {
            return scope;
        }
    }

    private Map<String, List<String>> snapshot(boolean includeRecon) {
        JdbcClient admin = JdbcClient.create(db.superuser());
        var tables = admin.sql("""
                SELECT schemaname || '.' || tablename FROM pg_tables
                WHERE schemaname IN ('core','ledger','io','recon','sim_processor','sim_bank','sim_truth','sim_chaos')
                ORDER BY schemaname, tablename
                """).query(String.class).list();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String name : tables) {
            String table = java.util.Objects.requireNonNull(name);
            if (includeRecon || !table.startsWith("recon.")) {
                result.put(
                        table,
                        admin.sql("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text")
                                .query(String.class)
                                .list());
            }
        }
        return result;
    }

    private String issueId() {
        return jdbc.sql("SELECT id FROM recon.issue WHERE subject_id='p2'")
                .query(String.class)
                .single();
    }

    private String status() {
        return jdbc.sql("SELECT status FROM recon.issue WHERE subject_id='p2'")
                .query(String.class)
                .single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM recon." + table).query(Long.class).single();
    }

    @Test
    void ac_5_8_formalAndDryRunsNeverChangeLedgerOrOtherSchemas() {
        new LedgerFixture(db).s1();
        var allBefore = snapshot(true);
        var dry = runs.run(request(true));
        assertThat(dry.id()).isNull();
        assertThat(dry.dryRun()).isTrue();
        assertThat(snapshot(true)).isEqualTo(allBefore);
        var otherBefore = snapshot(false);
        var formal = runs.run(request(false));
        assertThat(snapshot(false)).isEqualTo(otherBefore);
        assertThat(formal.findings()).isEqualTo(dry.findings());
        assertThat(formal.summary()).containsEntry("FEE_MISMATCH", 1L);
        assertThat(runs.get(java.util.Objects.requireNonNull(formal.id()))).contains(formal);
        assertThat(runs.get("unknown")).isEmpty();
        var after = snapshot(true);
        runs.run(request(true));
        assertThat(snapshot(true)).isEqualTo(after);
    }

    @Test
    void ac_5_9_replayPreservesFindingsAndDoesNotDuplicateIssues() {
        var first = runs.run(request(false));
        var second = runs.run(request(false));
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.findings()).isEqualTo(second.findings());
        assertThat(count("issue")).isEqualTo(1);
        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("issue_transition")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT first_run_id FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo(first.id());
        assertThat(jdbc.sql("SELECT last_run_id FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo(second.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "RESOLVED"})
    void ac_5_21_closedIssueKeepsDispositionUntilFingerprintChanges(String disposition) {
        var original = runs.run(request(false));
        String id = issueId();
        if (disposition.equals("ACCEPTED")) issues.accept(id, "Contract exception");
        else issues.resolve(id, "Correction recorded");
        var same = runs.run(request(false));
        assertThat(status()).isEqualTo(disposition);
        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("issue_transition")).isEqualTo(2);
        assertThat(same.findings()).isEqualTo(original.findings());
        rule.findings = List.of(new Finding(
                "FEE_MISMATCH", "payment", "p2", "{\"fee\":176}", "{\"fee\":206}", "[\"txn_updated\"]", null));
        runs.run(request(false));
        assertThat(status()).isEqualTo("OPEN");
        assertThat(issueId()).isEqualTo(id);
        assertThat(count("issue_transition")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT reopen_count FROM recon.issue")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT reason FROM recon.issue_transition WHERE from_status=?")
                        .param(disposition)
                        .query(String.class)
                        .single())
                .isEqualTo("REOPENED");
        assertThat(runs.get(java.util.Objects.requireNonNull(original.id()))).contains(original);
        assertThat(jdbc.sql("SELECT actual::text FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("{\"fee\": 206}");
        assertThat(jdbc.sql("SELECT expected::text FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("{\"fee\": 176}");
        assertThat(jdbc.sql("SELECT evidence_refs::text FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("[\"txn_updated\"]");
        assertThat(jdbc.sql("SELECT actual::text FROM recon.finding WHERE run_id=?")
                        .param(original.id())
                        .query(String.class)
                        .single())
                .isEqualTo("{\"fee\": 205}");
    }

    @Test
    void ac_5_21_explainedIssueReopensWithSameFingerprint() {
        var original = runs.run(request(false));
        rule.findings = List.of();
        var explanation = runs.run(request(false));
        assertThat(status()).isEqualTo("EXPLAINED");
        assertThat(jdbc.sql("SELECT explanation FROM recon.issue")
                        .query(String.class)
                        .single())
                .contains(explanation.id(), "Matched later evidence");
        assertThat(jdbc.sql("SELECT evidence_refs::text FROM recon.issue_transition WHERE reason='EXPLAINED'")
                        .query(String.class)
                        .single())
                .isEqualTo("[\"txn_later\"]");
        runs.run(request(false));
        assertThat(count("issue_transition")).isEqualTo(2);
        rule.findings = List.of(fee("p2", 205));
        runs.run(request(false));
        assertThat(status()).isEqualTo("OPEN");
        assertThat(count("issue")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT reopen_count FROM recon.issue")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(runs.get(java.util.Objects.requireNonNull(original.id()))).contains(original);
    }

    @Test
    void ac_5_21_outOfScopeOtherKindAndIngestionRemainUntouched() {
        runs.run(request(false));
        var originalIssue = jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i")
                .query(String.class)
                .single();
        rule.findings = List.of();
        rule.scope.clear();
        runs.run(request(false));
        assertThat(jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i")
                        .query(String.class)
                        .single())
                .isEqualTo(originalIssue);
        rule.scope.put(fee("p2", 205).key(), new ReconRule.Explanation("bank check", "[]"));
        rule.kind = RunRequest.Kind.BANK;
        runs.run(request(false));
        assertThat(jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i")
                        .query(String.class)
                        .single())
                .isEqualTo(originalIssue);
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(s ->
                new JdbcIngestionIssues(jdbc, ids, CLOCK).open("FEE_MISMATCH", "payment", "p3", "175", "205", "[]"));
        String ingestion = jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i WHERE subject_id='p3'")
                .query(String.class)
                .single();
        rule.kind = RunRequest.Kind.PROCESSOR;
        rule.scope.clear();
        rule.scope.put(fee("p3", 205).key(), new ReconRule.Explanation("no automatic disposition", "[]"));
        runs.run(request(false));
        rule.findings = List.of(fee("p3", 207));
        runs.run(request(false));
        assertThat(jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i WHERE subject_id='p3'")
                        .query(String.class)
                        .single())
                .isEqualTo(ingestion);
        assertThat(count("issue_transition")).isEqualTo(1);
        String ingestionId = jdbc.sql("SELECT id FROM recon.issue WHERE subject_id='p3'")
                .query(String.class)
                .single();
        issues.resolve(ingestionId, "Manually reviewed");
        assertThat(count("issue_transition")).isEqualTo(2);
    }

    @Test
    void ac_5_21_agingAndTimingUpgradeRetainBothReports() {
        rule.kind = RunRequest.Kind.AGING;
        var timing = new Finding("TIMING_DIFFERENCE", "payment", "p2", "175", "null", "[]", CUTOFF);
        rule.findings = List.of(timing);
        rule.scope.clear();
        rule.scope.put(timing.key(), new ReconRule.Explanation("已升级为 PAYMENT_NOT_SETTLED", "[\"pay_p2\"]"));
        var first = runs.run(request(false));
        assertThat(runs.get(java.util.Objects.requireNonNull(first.id()))).contains(first);
        var aged = new Finding("PAYMENT_NOT_SETTLED", "payment", "p2", "175", "null", "[]", null);
        rule.findings = List.of(aged);
        rule.scope.put(aged.key(), new ReconRule.Explanation("no batch", "[]"));
        runs.run(request(false));
        assertThat(jdbc.sql("SELECT status FROM recon.issue WHERE category='TIMING_DIFFERENCE'")
                        .query(String.class)
                        .single())
                .isEqualTo("EXPLAINED");
        assertThat(jdbc.sql("SELECT detected_by FROM recon.issue WHERE category='PAYMENT_NOT_SETTLED'")
                        .query(String.class)
                        .single())
                .isEqualTo("AGING");
        assertThat(runs.get(java.util.Objects.requireNonNull(first.id()))).contains(first);
    }

    @Test
    void ac_5_21_operatorRequiresOpenIssueAndNonblankReason() {
        runs.run(request(false));
        assertThatThrownBy(() -> issues.accept(issueId(), " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issues.resolve("", "note")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issues.resolve("unknown", "note")).isInstanceOf(IllegalArgumentException.class);
        issues.accept(issueId(), "reviewed");
        assertThatThrownBy(() -> issues.resolve(issueId(), "again")).isInstanceOf(IllegalStateException.class);
        rule.findings = List.of();
        runs.run(request(false));
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(count("issue_transition")).isEqualTo(2);
    }

    @Test
    void ac_5_8_lateWriteFailureRollsBackEntireRunAndOperatorHistory() throws Exception {
        var before = snapshot(true);
        Finding invalid = new Finding("ZZ_INVALID_CATEGORY", "payment", "p3", "1", "2", "[]", null);
        rule.findings = List.of(fee("p2", 205), invalid);
        rule.scope.put(invalid.key(), new ReconRule.Explanation("invalid", "[]"));
        assertThatThrownBy(() -> runs.run(request(false)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(snapshot(true)).isEqualTo(before);
        rule.findings = List.of(fee("p2", 205));
        runs.run(request(false));
        Sql.execute(
                db.migrator(),
                """
                CREATE FUNCTION recon.fail_transition() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'test failure'; END; $$
                """,
                "CREATE TRIGGER test_failure BEFORE INSERT ON recon.issue_transition FOR EACH ROW EXECUTE FUNCTION recon.fail_transition()");
        var established = snapshot(true);
        assertThatThrownBy(() -> issues.accept(issueId(), "reviewed"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(snapshot(true)).isEqualTo(established);
    }

    @Test
    void ac_5_8_dryRunRejectsAnAccidentalRuleWriteAtDatabaseBoundary() {
        rule.before = context -> jdbc.sql("UPDATE ledger.account SET balance=balance WHERE code=1101")
                .update();
        var before = snapshot(true);
        Sql.assertSqlState("25006", org.assertj.core.api.Assertions.catchThrowable(() -> runs.run(request(true))));
        assertThat(snapshot(true)).isEqualTo(before);
    }

    @Test
    void ac_5_9_repeatableReadKeepsSnapshotAcrossConcurrentCommit() throws Exception {
        var read = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        rule.before = context -> {
            assertThat(jdbc.sql("SHOW transaction_isolation")
                            .query(String.class)
                            .single())
                    .isEqualTo("repeatable read");
            assertThat(jdbc.sql("SELECT count(*) FROM ledger.posting")
                            .query(Long.class)
                            .single())
                    .isZero();
            read.countDown();
            await(committed);
            assertThat(jdbc.sql("SELECT count(*) FROM ledger.posting")
                            .query(Long.class)
                            .single())
                    .isZero();
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> runs.run(request(false)));
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                new LedgerFixture(db).s1();
            } finally {
                committed.countDown();
            }
            assertThat(running.get(10, TimeUnit.SECONDS).findings()).hasSize(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ledger.posting")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
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
    void ac_5_21_anotherRunKindCannotChangeExistingDisposition() {
        runs.run(request(false));
        issues.accept(issueId(), "Processor exception");
        String before = jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i")
                .query(String.class)
                .single();
        rule.kind = RunRequest.Kind.BANK;
        rule.findings = List.of(fee("p2", 206));
        runs.run(request(false));
        assertThat(jdbc.sql("SELECT to_jsonb(i)::text FROM recon.issue i")
                        .query(String.class)
                        .single())
                .isEqualTo(before);
        assertThat(count("finding")).isEqualTo(2);
        assertThat(count("issue_transition")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "RESOLVED"})
    void ac_5_21_sameFingerprintRefreshesEvidenceWithoutChangingDisposition(String disposition) {
        var original = runs.run(request(false));
        if (disposition.equals("ACCEPTED")) issues.accept(issueId(), "Reviewed once");
        else issues.resolve(issueId(), "Correction recorded");
        String before = jdbc.sql("SELECT (to_jsonb(i) - 'last_run_id' - 'evidence_refs')::text FROM recon.issue i")
                .query(String.class)
                .single();
        var finding = rule.findings.getFirst();
        rule.findings = List.of(new Finding(
                finding.category(),
                finding.subjectType(),
                finding.subjectId(),
                finding.expected(),
                finding.actual(),
                "[\"txn_updated\"]",
                finding.dueAt()));
        var later = new ReconRuns(
                jdbc,
                manager,
                Clock.offset(CLOCK, Duration.ofHours(1)),
                ids,
                new ReconProperties(Duration.ofSeconds(60), "v1"),
                List.of(rule),
                new ReconJson(mapper),
                mapper,
                issues);
        var next = later.run(request(false));
        assertThat(jdbc.sql("SELECT (to_jsonb(i) - 'last_run_id' - 'evidence_refs')::text FROM recon.issue i")
                        .query(String.class)
                        .single())
                .isEqualTo(before);
        assertThat(jdbc.sql("SELECT last_run_id FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo(next.id());
        assertThat(jdbc.sql("SELECT evidence_refs::text FROM recon.issue")
                        .query(String.class)
                        .single())
                .isEqualTo("[\"txn_updated\"]");
        assertThat(runs.get(java.util.Objects.requireNonNull(original.id()))).contains(original);
    }

    @Test
    void ac_5_21_concurrentOperatorDispositionCannotBeOverwrittenByOldSnapshot() throws Exception {
        runs.run(request(false));
        String id = issueId();
        long runsBefore = count("run");
        var snapshot = new CountDownLatch(1);
        var accepted = new CountDownLatch(1);
        rule.before = context -> {
            snapshot.countDown();
            await(accepted);
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> runs.run(request(false)));
            try {
                assertThat(snapshot.await(10, TimeUnit.SECONDS)).isTrue();
                issues.accept(id, "Concurrent review");
            } finally {
                accepted.countDown();
            }
            Sql.assertSqlState(
                    "40001", org.assertj.core.api.Assertions.catchThrowable(() -> running.get(10, TimeUnit.SECONDS)));
        }
        assertThat(status()).isEqualTo("ACCEPTED");
        assertThat(count("run")).isEqualTo(runsBefore);
        assertThat(count("finding")).isEqualTo(1);
        rule.before = context -> {};
        runs.run(request(false));
        assertThat(status()).isEqualTo("ACCEPTED");
    }

    @Test
    void ac_5_9_inputsAreFrozenFilteredAndValidated() {
        for (int i = 0; i < 4; i++) {
            jdbc.sql("""
                    INSERT INTO io.imported_file (id, kind, source_name, raw, sha256, statement_date, version,
                        status, reject_reason, received_at, recorded_at)
                    VALUES (?, 'PROCESSOR_STATEMENT', 'fixture', convert_to(?, 'UTF8'),
                        encode(sha256(convert_to(?, 'UTF8')), 'hex'), '2026-11-12', 1, ?, NULLIF(?, ''), ?, ?)
                    """)
                    .params(
                            "file_0000000000000000000000000" + (i + 1),
                            "snapshot" + i,
                            "snapshot" + i,
                            i == 1 ? "REJECTED" : "ACCEPTED",
                            i == 1 ? "FILE_INCOMPLETE" : "",
                            (i == 2 ? EVIDENCE : CUTOFF).atOffset(ZoneOffset.UTC),
                            (i == 3 ? EVIDENCE : CUTOFF).atOffset(ZoneOffset.UTC))
                    .update();
        }
        var report = runs.run(request(false));
        assertThat(report.inputHashes()).containsOnlyKeys("file_00000000000000000000000001");
        assertThat(runs.get(java.util.Objects.requireNonNull(report.id()))).contains(report);
        var explicit =
                new RunRequest(rule.kind, CUTOFF, EVIDENCE, "v1", List.of("file_00000000000000000000000001"), false);
        assertThat(runs.run(explicit).findings()).isEqualTo(report.findings());
        for (String invalid : List.of(
                "file_00000000000000000000000002",
                "file_00000000000000000000000003",
                "file_00000000000000000000000004",
                "fetch:99")) {
            assertThatThrownBy(
                            () -> runs.run(new RunRequest(rule.kind, CUTOFF, EVIDENCE, "v1", List.of(invalid), false)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        rule.kind = RunRequest.Kind.BANK;
        assertThat(runs.run(request(true)).inputHashes()).isEmpty();
        rule.kind = RunRequest.Kind.AGING;
        assertThat(runs.run(request(true)).inputHashes()).containsOnlyKeys("file_00000000000000000000000001");
    }

    @Test
    void ac_5_9_timeBoundariesRejectInvalidRequestsAndAcceptDstMidnights() {
        for (RunRequest invalid : List.of(
                new RunRequest(rule.kind, CUTOFF.plusSeconds(1), EVIDENCE, "v1", List.of(), false),
                new RunRequest(rule.kind, CUTOFF, NOW.minusSeconds(59), "v1", List.of(), false),
                new RunRequest(rule.kind, CUTOFF, CUTOFF.minusSeconds(1), "v1", List.of(), false))) {
            assertThatThrownBy(() -> runs.run(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count("run")).isZero();
        for (Instant cutoff : List.of(
                Instant.parse("2026-03-08T05:00:00Z"),
                Instant.parse("2026-03-09T04:00:00Z"),
                Instant.parse("2026-11-01T04:00:00Z"),
                Instant.parse("2026-11-02T05:00:00Z"))) {
            runs.run(new RunRequest(rule.kind, cutoff, cutoff, "v1", List.of(), true));
        }
        runs.run(new RunRequest(rule.kind, CUTOFF, NOW.minusSeconds(60), "v1", List.of(), true));
    }

    @Test
    void ac_5_9_ruleContractViolationsAreRejectedWithoutWrites() {
        var before = snapshot(true);
        assertThatThrownBy(() -> runs.run(new RunRequest(rule.kind, CUTOFF, EVIDENCE, "v2", List.of(), false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner(List.of(rule, rule)).run(request(false)))
                .isInstanceOf(IllegalArgumentException.class);
        rule.findings = List.of(fee("p2", 205), fee("p2", 205));
        assertThatThrownBy(() -> runs.run(request(false))).isInstanceOf(IllegalArgumentException.class);
        rule.scope.clear();
        assertThatThrownBy(() -> runs.run(request(false))).isInstanceOf(IllegalArgumentException.class);
        assertThat(snapshot(true)).isEqualTo(before);
        rule.kind = RunRequest.Kind.BANK;
        assertThat(runs.run(new RunRequest(RunRequest.Kind.PROCESSOR, CUTOFF, EVIDENCE, "v1", List.of(), true))
                        .findings())
                .isEmpty();
    }
}
