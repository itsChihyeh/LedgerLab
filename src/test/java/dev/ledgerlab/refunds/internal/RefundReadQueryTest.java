package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.ledgerlab.investigation.internal.RefundQueryHarness;
import dev.ledgerlab.testsupport.Sql;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

class RefundReadQueryTest {
    @Test
    void ac_3_15_refundsQueueDebtAndRemainingAreReadFromReadonlyViews() throws Exception {
        try (var f = new RefundFixture()) {
            String payment = f.s3();
            var first = f.accept(payment, 5000);
            var second = f.accept(payment, 5000);
            String other = f.payment("ch_other", 1000);
            var third = f.accept(other, 100);
            MockMvc mvc = RefundQueryHarness.mvc(f.db.readonly(), f.clock);
            var p = read(f, mvc, "/api/payments/" + payment);
            assertThat(p.path("refundable_remaining").asLong()).isZero();
            assertThat(p.path("refunds").size()).isEqualTo(2);
            assertThat(p.path("refunds").get(0).path("queue_position").isNull()).isTrue();
            assertThat(p.path("refunds").get(0).path("command_status").asString())
                    .isEqualTo("PENDING");
            assertThat(p.path("refunds").get(1).path("queue_position").asLong()).isEqualTo(1);
            assertThat(p.path("refunds").get(1).path("queue_seq").asLong()).isEqualTo(second.queueSeq());
            assertThat(p.path("postings").size()).isEqualTo(13);
            var detail = read(f, mvc, "/api/refunds/" + third.refundId());
            assertThat(detail.path("refund").path("queue_position").asLong()).isEqualTo(2);
            assertThat(detail.path("history").get(0).path("evidence_source").asString())
                    .isEqualTo("API");
            assertThat(detail.path("entries").get(0).path("entryType").asString())
                    .isEqualTo("REFUND_ACCEPTED");
            assertThat(detail.path("postings").size()).isEqualTo(2);
            assertThat(detail.path("commands").isEmpty()).isTrue();
            f.tx.executeWithoutResult(ignored -> f.issues.open(
                    "REFUND_STATE_CONFLICT",
                    "refund",
                    third.refundId(),
                    "{}",
                    "{}",
                    "[{\"table\":\"core.refund\",\"id\":\"" + third.refundId() + "\"}]"));
            var issueId = f.jdbc.sql("SELECT id FROM recon.issue WHERE subject_id=?")
                    .param(third.refundId())
                    .query(String.class)
                    .single();
            var issue = read(f, mvc, "/api/recon/issues/" + issueId);
            assertThat(issue.path("references").path("refundIds").get(0).asString())
                    .isEqualTo(third.refundId());
            assertThat(issue.path("references").path("entryIds").toString())
                    .contains(detail.path("entries").get(0).path("id").asString());
            var overview = read(f, mvc, "/api/overview");
            assertThat(overview.path("merchant_debt").asLong()).isEqualTo(420);
            assertThat(overview.path("refund_payable").asLong()).isEqualTo(10100);
            assertThat(overview.path("awaiting_funds_count").asLong()).isEqualTo(2);
            assertThat(overview.path("awaiting_funds_amount").asLong()).isEqualTo(5100);
            f.refunds.cancel(second.refundId(), "customer request");
            assertThat(read(f, mvc, "/api/payments/" + payment)
                            .path("refundable_remaining")
                            .asLong())
                    .isEqualTo(5000);
            var released = read(f, mvc, "/api/refunds/" + third.refundId());
            assertThat(released.path("refund").path("status").asString()).isEqualTo("READY");
            assertThat(released.path("refund").path("queue_position").isNull()).isTrue();
            assertThat(read(f, mvc, "/api/refunds/" + first.refundId())
                            .path("asOf")
                            .path("at")
                            .asString())
                    .isEqualTo(f.clock.instant().toString());
            f.invariants();
        }
    }

    @Test
    void ac_4_12_attemptSummaryNeverExposesRawEvidenceOrGrantsIoAccess() throws Exception {
        try (var f = new RefundFixture()) {
            var refund = f.accept(f.s3(), 2000);
            // Audit fixture contains deliberately sensitive-looking text; only safe scalar fields may leave the view.
            f.jdbc.sql("""
                    INSERT INTO io.outbound_attempt(command_id, attempt_no, lease_epoch, sent_at, finished_at,
                        request_summary, response_status, response_body, error, recorded_at)
                    SELECT id, 1, 1, created_at, created_at, '{"private":"hidden-request"}',
                        200, 'hidden-response', 'hidden-error', recorded_at FROM io.outbound_command
                    """).update();
            var mvc = RefundQueryHarness.mvc(f.db.readonly(), f.clock);
            var detail = read(f, mvc, "/api/refunds/" + refund.refundId());
            assertThat(detail.path("attempts").size()).isEqualTo(1);
            var attempt = detail.path("attempts").get(0);
            assertThat(attempt.path("attempt_no").asInt()).isEqualTo(1);
            assertThat(attempt.path("response_status").asInt()).isEqualTo(200);
            assertThat(attempt.path("result_code").asString()).isEqualTo("HTTP_200");
            assertThat(detail.toString()).doesNotContain("hidden-", "request_summary", "response_body", "\"error\"");
            f.jdbc.sql("""
                    INSERT INTO io.outbound_attempt(command_id, attempt_no, lease_epoch, sent_at, finished_at,
                        request_summary, error, recorded_at)
                    SELECT id, 3, 2, created_at, created_at, '{}', 'hidden-timeout', recorded_at FROM io.outbound_command
                    """).update();
            var next = read(f, mvc, "/api/refunds/" + refund.refundId());
            assertThat(next.path("attempts").get(1).path("attempt_no").asInt()).isEqualTo(3);
            assertThat(next.path("attempts").get(1).path("result_code").asString())
                    .isEqualTo("NO_RESPONSE");
            assertThat(next.toString()).doesNotContain("hidden-");
            var readonly = f.db.readonly();
            for (String sql : List.of("SELECT * FROM io.outbound_attempt", "SELECT * FROM io.outbound_command"))
                Sql.assertRejected("42501", readonly, sql);
            // PostgreSQL rejects joined-view writes during rewrite, before checking write privileges.
            for (String sql : List.of(
                    "UPDATE core.v_refund_attempt_summary SET response_status=201",
                    "DELETE FROM core.v_refund_attempt_summary")) Sql.assertRejected("55000", readonly, sql);
            for (String privilege : List.of("INSERT", "UPDATE", "DELETE", "TRUNCATE")) {
                assertThat(JdbcClient.create(readonly)
                                .sql("SELECT has_table_privilege(current_user, 'core.v_refund_attempt_summary', ?)")
                                .param(privilege)
                                .query(Boolean.class)
                                .single())
                        .isFalse();
            }
            Sql.assertRejected("42501", f.source, "SELECT * FROM core.v_refund_attempt_summary");
            assertThat(JdbcClient.create(f.db.migrator())
                            .sql(
                                    "SELECT viewowner FROM pg_views WHERE schemaname='core' AND viewname='v_refund_attempt_summary'")
                            .query(String.class)
                            .single())
                    .isEqualTo("migrator");
            f.invariants();
        }
    }

    private static JsonNode read(RefundFixture f, MockMvc mvc, String path) throws Exception {
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return f.mapper.readTree(response.getContentAsString());
    }
}
