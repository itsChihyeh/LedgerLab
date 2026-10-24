package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class IssueQueryTest {
    @Test
    void ac_5_15_heldIngestionIssueExposesNestedAmountsAndNonblankNavigation() {
        var db = TestDatabase.freshMigrated();
        try {
            String id = "iss_" + "0".repeat(25) + "1";
            JdbcClient.create(db.app()).sql("""
                    INSERT INTO recon.issue(id, category, subject_type, subject_id, detected_by, status,
                        expected, actual, evidence_refs, recorded_at, updated_at)
                    VALUES (?, 'AMOUNT_MISMATCH', 'event', 'evt_held', 'INGESTION', 'OPEN',
                        '{"gross":10000}', '{"reason":"AMOUNT_MISMATCH","line":{"gross":9999}}',
                        '[{"batch_id":"","event_id":"evt_held"},{"table":"io.inbox_event","id":"evt_original"}]',
                        '2026-11-10T16:00:00Z', '2026-11-10T16:00:00Z')
                    """).param(id).update();
            var queries = new WorkbenchQueries(
                    new QuerySnapshot(
                            db.readonly(), Clock.fixed(Instant.parse("2026-11-11T16:00:00Z"), ZoneOffset.UTC)),
                    JsonMapper.builder().build());
            var detail = queries.issueDetail(id);
            assertAll(
                    () -> assertThat(detail.amounts())
                            .containsExactly(new IssueAmounts.Comparison("gross", null, 10000, 9999, 1)),
                    () -> assertThat(detail.references().batchIds()).isEmpty());
            assertThat(detail.references().eventIds()).containsExactly("evt_held", "evt_original");
            assertThat(detail.findings()).isEmpty();
            assertThat(detail.history()).isEmpty();
        } finally {
            db.drop();
        }
    }
}
