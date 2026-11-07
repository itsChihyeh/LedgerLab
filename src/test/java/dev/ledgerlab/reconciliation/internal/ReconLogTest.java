package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.LogEvents;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** AC-12.2 and AC-12.3 for reconciliation: run, issue and batch ids on the lines, and no free text. */
class ReconLogTest {
    private static final Instant NOW = Instant.parse("2026-11-13T14:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-11-13T05:00:00Z");
    private static final Instant EVIDENCE = Instant.parse("2026-11-13T13:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BATCH = "po_LOGTESTBATCH1";
    private static final String NOTE = "operator-note-canary-91d3";

    private TestDatabase.Handle db;
    private JdbcClient jdbc;
    private IssueStore issues;
    private ReconRuns runs;
    private ReconRunsTest.FakeRule rule;
    private LogEvents events;

    @BeforeEach
    void setUp() {
        db = TestDatabase.freshMigrated();
        var source = db.app();
        jdbc = JdbcClient.create(source);
        var manager = new DataSourceTransactionManager(source);
        var ids = IdGenerator.seeded(CLOCK, 9);
        var mapper = JsonMapper.builder().build();
        issues = new IssueStore(jdbc, ids, CLOCK, manager);
        rule = new ReconRunsTest.FakeRule();
        setBatchFinding("{\"total\":100}", "{\"total\":90}");
        runs = new ReconRuns(
                jdbc,
                manager,
                CLOCK,
                ids,
                new ReconProperties(Duration.ofSeconds(60), "v1"),
                List.of(rule),
                new ReconJson(mapper),
                mapper,
                issues,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        events = LogEvents.attach();
    }

    @AfterEach
    void tearDown() {
        events.close();
        db.drop();
    }

    private void setBatchFinding(String expected, String actual) {
        var finding = new Finding("BATCH_TOTAL_MISMATCH", "batch", BATCH, expected, actual, "[\"txn_log\"]", null);
        rule.findings = List.of(finding);
        rule.scope.clear();
        rule.scope.put(finding.key(), new ReconRule.Explanation("Matched later evidence", "[\"txn_later\"]"));
    }

    private RunRequest request(boolean dry) {
        return new RunRequest(rule.kind, CUTOFF, EVIDENCE, "v1", List.of(), dry);
    }

    private String issueId() {
        return jdbc.sql("SELECT id FROM recon.issue WHERE subject_id = :batch")
                .param("batch", BATCH)
                .query(String.class)
                .single();
    }

    @Test
    void ac_12_2_openedIssueLineCarriesIssueRunAndBatchIds() {
        String runId = Objects.requireNonNull(runs.run(request(false)).id());
        String issueId = issueId();

        List<ILoggingEvent> opened = events.action("recon.issue.opened");
        assertThat(opened).hasSize(1);
        assertThat(opened.getFirst().getMDCPropertyMap())
                .containsEntry("issueId", issueId)
                .containsEntry("runId", runId)
                .containsEntry("batchId", BATCH);
        assertThat(LogEvents.keyValues(opened.getFirst()))
                .containsEntry("category", "BATCH_TOTAL_MISMATCH")
                .containsEntry("subjectType", "batch")
                .containsEntry("subjectId", BATCH)
                .containsEntry("toStatus", "OPEN");

        List<ILoggingEvent> finished = events.action("recon.run.finished");
        assertThat(finished).hasSize(1);
        assertThat(finished.getFirst().getMDCPropertyMap()).containsEntry("runId", runId);
        assertThat(LogEvents.keyValues(finished.getFirst()))
                .containsEntry("kind", "PROCESSOR")
                .containsEntry("opened", 1)
                .containsEntry("reopened", 0)
                .containsEntry("explained", 0);
        // The run's ids stop at the end of the run.
        assertThat(MDC.get("runId")).isNull();
        assertThat(MDC.get("issueId")).isNull();
        assertThat(MDC.get("batchId")).isNull();
    }

    @Test
    void ac_12_2_dryRunWritesNothingToTheLog() {
        runs.run(request(true));
        assertThat(events.action("recon.run.finished")).isEmpty();
        assertThat(events.action("recon.issue.opened")).isEmpty();
    }

    @Test
    void ac_12_2_operatorAcceptanceThenChangedEvidenceReopensWithBothIds() {
        runs.run(request(false));
        String issueId = issueId();
        issues.accept(issueId, NOTE);
        setBatchFinding("{\"total\":100}", "{\"total\":80}");
        String secondRun = Objects.requireNonNull(runs.run(request(false)).id());

        List<ILoggingEvent> transitions = events.action("recon.issue.transition");
        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst().getMDCPropertyMap())
                .containsEntry("issueId", issueId)
                .doesNotContainKey("runId");
        assertThat(LogEvents.keyValues(transitions.getFirst()))
                .containsEntry("fromStatus", "OPEN")
                .containsEntry("toStatus", "ACCEPTED");

        List<ILoggingEvent> reopened = events.action("recon.issue.reopened");
        assertThat(reopened).hasSize(1);
        assertThat(reopened.getFirst().getMDCPropertyMap())
                .containsEntry("issueId", issueId)
                .containsEntry("runId", secondRun)
                .containsEntry("batchId", BATCH);
        assertThat(LogEvents.keyValues(reopened.getFirst()))
                .containsEntry("fromStatus", "ACCEPTED")
                .containsEntry("toStatus", "OPEN");
        assertThat(LogEvents.keyValues(events.action("recon.run.finished").get(1)))
                .containsEntry("opened", 0)
                .containsEntry("reopened", 1);
    }

    @Test
    void ac_12_2_findingGoneMovesTheIssueToExplainedAndCountsIt() {
        runs.run(request(false));
        String issueId = issueId();
        rule.findings = List.of();
        String secondRun = Objects.requireNonNull(runs.run(request(false)).id());

        List<ILoggingEvent> transitions = events.action("recon.issue.transition");
        assertThat(transitions).hasSize(1);
        assertThat(transitions.getFirst().getMDCPropertyMap())
                .containsEntry("issueId", issueId)
                .containsEntry("runId", secondRun);
        assertThat(LogEvents.keyValues(transitions.getFirst()))
                .containsEntry("fromStatus", "OPEN")
                .containsEntry("toStatus", "EXPLAINED");
        assertThat(LogEvents.keyValues(events.action("recon.run.finished").get(1)))
                .containsEntry("explained", 1);
    }

    @Test
    void ac_12_3_operatorNotesAndEvidenceStayOutOfTheLog() {
        runs.run(request(false));
        issues.resolve(issueId(), NOTE);
        String text = events.allText();
        assertThat(text).contains("recon.issue.opened").contains("recon.issue.transition");
        assertThat(text).doesNotContain(NOTE).doesNotContain("txn_log").doesNotContain("Detected by run");
    }
}
