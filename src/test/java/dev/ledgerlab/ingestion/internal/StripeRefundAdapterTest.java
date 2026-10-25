package dev.ledgerlab.ingestion.internal;

import static dev.ledgerlab.ingestion.internal.RefundHttpServer.page;
import static dev.ledgerlab.ingestion.internal.RefundHttpServer.refund;
import static dev.ledgerlab.ingestion.internal.RefundHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.reconciliation.internal.JdbcIngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import dev.ledgerlab.refunds.ProcessorRefundGateway.Found;
import dev.ledgerlab.refunds.ProcessorRefundGateway.Incomplete;
import dev.ledgerlab.refunds.ProcessorRefundGateway.NotFound;
import dev.ledgerlab.refunds.ProcessorRefundGateway.Rejected;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.TestDatabase;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class StripeRefundAdapterTest {
    private TestDatabase.Handle db;
    private JdbcClient jdbc;
    private JdbcTransactionManager manager;
    private RefundHttpServer server;
    private ProcessorRefundGateway gateway;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T12:00:00Z"));
    private final JsonMapper mapper = new JsonMapper();
    private JdbcIngestionIssues issues;

    @BeforeEach
    void setup() throws Exception {
        db = TestDatabase.freshMigrated();
        jdbc = JdbcClient.create(db.app());
        manager = new JdbcTransactionManager(db.app());
        server = new RefundHttpServer();
        issues = new JdbcIngestionIssues(jdbc, IdGenerator.seeded(clock, 508), clock);
        gateway = server.gateway(jdbc, manager, mapper, clock, issues);
    }

    @AfterEach
    void close() {
        server.close();
        db.drop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "succeeded", "failed", "canceled"})
    void ac_3_17_retrieveParsesAllStatesAndArchivesOutsideCallerTransaction(String state) {
        String body = refund("re_known", "ch_one", "rfd_local", 3000, state)
                .replace("\"created\":1", "\"failure_reason\":\"lost_or_stolen_card\",\"created\":1");
        server.handler = e -> respond(e, 200, body);
        var response = new TransactionTemplate(manager).execute(tx -> {
            var result = gateway.retrieve("re_known");
            tx.setRollbackOnly();
            return result;
        });
        assertThat(response.value()).isInstanceOf(Found.class);
        var found = (Found) response.value();
        assertThat(found.status().name()).isEqualTo(state);
        assertThat(found.failureReason()).isEqualTo("lost_or_stolen_card");
        assertThat(java.util.Objects.requireNonNull(found.details()).amount()).isEqualTo(3000);
        assertThat(java.util.Objects.requireNonNull(found.details()).chargeRef())
                .isEqualTo("ch_one");
        assertThat(java.util.Objects.requireNonNull(found.details()).refundId()).isEqualTo("rfd_local");
        assertThat(java.util.Objects.requireNonNull(found.details()).receivedAt())
                .isEqualTo(clock.instant());
        assertArchive(body, true);
    }

    @Test
    void ac_8_7_matchingFirstPageStillRequiresFinalPageAndUsesMatchingResponseTime() {
        Instant matched = clock.instant();
        String first = page(List.of(refund("re_match", "ch_one", "rfd_local", 3000, "pending")), true);
        String second = page(List.of(refund("re_other", "ch_one", "rfd_other", 2, "succeeded")), false);
        var calls = new AtomicInteger();
        server.handler = e -> {
            if (calls.getAndIncrement() == 0) respond(e, 200, first);
            else {
                clock.set(matched.plusSeconds(10));
                respond(e, 200, second);
            }
        };
        var result = gateway.findByRefundId("ch_one", "rfd_local");
        assertThat(result.value()).isInstanceOf(Found.class);
        assertThat(java.util.Objects.requireNonNull(((Found) result.value()).details())
                        .receivedAt())
                .isEqualTo(matched);
        assertThat(calls).hasValue(2);
        assertArchive(mapper.writeValueAsString(List.of(first, second)), true);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "duplicate",
                "empty_more",
                "repeated_cursor",
                "missing_more",
                "not_array",
                "trailing",
                "broken",
                "http",
                "transport"
            })
    void ac_8_7_incompleteSecondPageNeverReturnsFoundOrNotFound(String mode) {
        String object = refund("re_first", "ch_one", "rfd_local", 3000, "pending");
        String first = page(List.of(object), true);
        var calls = new AtomicInteger();
        server.handler = e -> {
            if (calls.getAndIncrement() == 0) respond(e, 200, first);
            else
                switch (mode) {
                    case "duplicate" ->
                        respond(
                                e,
                                200,
                                page(List.of(refund("re_second", "ch_one", "rfd_local", 3000, "pending")), false));
                    case "empty_more" -> respond(e, 200, page(List.of(), true));
                    case "repeated_cursor" -> respond(e, 200, first);
                    case "missing_more" -> respond(e, 200, "{\"data\":[]}");
                    case "not_array" -> respond(e, 200, "{\"data\":{},\"has_more\":false}");
                    case "trailing" -> respond(e, 200, page(List.of(), false) + " {}");
                    case "broken" -> respond(e, 200, "{broken");
                    case "http" -> respond(e, 503, "{}");
                    default -> server.stall(e);
                }
        };
        var result = gateway.findByRefundId("ch_one", "rfd_local");
        assertThat(result.value()).isInstanceOf(Incomplete.class);
        assertThat(server.requests).hasSize(2);
        assertThat(count("SELECT count(*) FROM io.processor_fetch WHERE NOT complete"))
                .isEqualTo(1);
        if (mode.equals("duplicate")) {
            assertThat(
                            count(
                                    "SELECT count(*) FROM recon.issue WHERE category='REFUND_STATE_CONFLICT' AND subject_id='rfd_local'"))
                    .isEqualTo(1);
            assertThat(jdbc.sql("SELECT actual::text FROM recon.issue")
                            .query(String.class)
                            .single())
                    .contains("DUPLICATE_PROCESSOR_REFUNDS");
        } else {
            assertThat(count("SELECT count(*) FROM recon.issue")).isZero();
        }
    }

    @Test
    void ac_8_7_metadataMatchesExactlyAndAbsentMetadataIsNotAMatch() {
        String object = refund("re_other", "ch_one", "rfd_local_suffix", 3000, "pending");
        String untagged = refund("re_untagged", "ch_one", "rfd_ignored", 20, "pending")
                .replace("\"ledgerlab_refund_id\":\"rfd_ignored\"", "");
        server.handler = e -> respond(e, 200, page(List.of(object, untagged), false));
        assertThat(gateway.findByRefundId("ch_one", "rfd_local").value()).isInstanceOf(NotFound.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3000.0", "\"3000\"", "null", "true", "9223372036854775808", "0", "-1"})
    void ac_3_17_malformedAmountIsUnknown(String amount) {
        String body = refund("re_one", "ch_one", "rfd_local", 3000, "succeeded")
                .replace("\"amount\":3000", "\"amount\":" + amount);
        server.handler = e -> respond(e, 200, body);
        assertThat(gateway.create("rfd_local", "ch_one", 3000, "rfd_local").value())
                .isInstanceOf(Incomplete.class);
        assertArchive(body, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "charge", "status", "metadata", "failure_reason", "broken", "live"})
    void ac_3_17_invalidObjectCannotBecomeProcessorEvidence(String field) {
        String body = refund("re_one", "ch_one", "rfd_local", 3000, "succeeded");
        body = switch (field) {
            case "id" -> body.replace("re_one", "bad");
            case "charge" -> body.replace("ch_one", "bad");
            case "status" -> body.replace("succeeded", "unexpected");
            case "metadata" -> body.replace("{\"ledgerlab_refund_id\":\"rfd_local\"}", "null");
            case "failure_reason" -> body.replace("\"created\":1", "\"created\":1,\"failure_reason\":12");
            case "live" -> body.replace("\"created\":1", "\"created\":1,\"livemode\":true");
            default -> "{broken";
        };
        String responseBody = body;
        server.handler = e -> respond(e, 200, responseBody);
        assertThat(gateway.create("rfd_local", "ch_one", 3000, "rfd_local").value())
                .isInstanceOf(Incomplete.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 401, 404, 409, 429, 500, 503})
    void ac_3_10_queryErrorsAndUnstructuredCreateErrorsRemainUnknown(int status) {
        server.handler = e -> {
            e.getResponseHeaders().set("Location", server.url() + "/redirected");
            respond(e, status, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"resource_missing\"}}");
        };
        assertThat(gateway.retrieve("re_missing").value()).isInstanceOf(Incomplete.class);
        server.handler = e -> respond(e, status, "<html>" + RefundHttpServer.KEY + "</html>");
        var result = gateway.create("rfd_local", "ch_one", 3000, "rfd_local");
        assertThat(result.value()).isInstanceOf(Incomplete.class);
        assertThat(result.error()).doesNotContain(RefundHttpServer.KEY);
        assertThat(server.requests).hasSize(2);
    }

    @Test
    void ac_8_2_errorEchoNeverLeaksCredentialToRequestOutcomeOrLogs() {
        var logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            server.handler = e -> respond(
                    e,
                    400,
                    "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"" + RefundHttpServer.KEY + "\"}}");
            var result = gateway.create("rfd_local", "ch_one", 3000, "rfd_local");
            assertThat(result.value()).isInstanceOf(Rejected.class);
            assertThat(((Rejected) result.value()).errorCode()).isEqualTo("PROCESSOR_ERROR");
            assertThat(result.error()).doesNotContain(RefundHttpServer.KEY);
            assertThat(jdbc.sql("SELECT request::text FROM io.processor_fetch")
                            .query(String.class)
                            .single())
                    .doesNotContain(RefundHttpServer.KEY, "Authorization");
            assertThat(logs.list)
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(RefundHttpServer.KEY));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void ac_8_7_invalidInputAndMissingKeyAreArchivedWithoutHttp() {
        assertThat(gateway.retrieve("re_../bad").value()).isInstanceOf(Incomplete.class);
        assertThat(gateway.findByRefundId("bad", "rfd_local").value()).isInstanceOf(Incomplete.class);
        assertThat(gateway.create("bad", "ch_one", 1, "rfd_local").value()).isInstanceOf(Incomplete.class);
        assertThat(gateway.create("rfd_local", "ch_one", 0, "rfd_local").value())
                .isInstanceOf(Incomplete.class);
        var missing = server.gateway(jdbc, manager, mapper, clock, issues, "");
        assertThat(missing.create("rfd_local", "ch_one", 1, "rfd_local").value())
                .isInstanceOf(Incomplete.class);
        assertThat(server.requests).isEmpty();
        assertThat(count("SELECT count(*) FROM io.processor_fetch WHERE NOT complete"))
                .isEqualTo(5);
    }

    @Test
    void ac_8_7_newPurposeConstraintRejectsUnknownAndPermissionsStayNarrow() {
        String insert = """
                INSERT INTO io.processor_fetch (purpose, request, response_body, response_sha256, complete, fetched_at, recorded_at)
                VALUES ('INVALID', '{}', '', encode(sha256(''::bytea),'hex'), false, '2026-10-24T12:00:00Z', '2026-10-24T12:00:00Z')
                """;
        assertThatThrownBy(() -> jdbc.sql(insert).update()).hasStackTraceContaining("processor_fetch_purpose_check");
        gateway.findByRefundId("ch_one", "rfd_local");
        assertThatThrownBy(() ->
                        jdbc.sql("UPDATE io.processor_fetch SET complete=true").update())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> JdbcClient.create(db.readonly())
                        .sql("SELECT count(*) FROM io.processor_fetch")
                        .query(Long.class)
                        .single())
                .hasStackTraceContaining("permission denied");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_request_error", "card_error", "authentication_error"})
    void ac_3_8_structuredStripe4xxIsExplicitRejection(String type) {
        server.handler = e -> respond(e, 400, "{\"error\":{\"type\":\"" + type + "\",\"code\":\"amount_too_large\"}}");
        var result = gateway.create("rfd_local", "ch_one", 3000, "rfd_local");
        assertThat(result.value()).isInstanceOf(Rejected.class);
        assertThat(((Rejected) result.value()).errorCode()).isEqualTo("amount_too_large");
        assertThat(((Rejected) result.value()).receivedAt()).isEqualTo(clock.instant());
    }

    @Test
    void ac_8_7_duplicateProcessorRefundsOpenOnlyOneIssueAcrossRetries() {
        String body = page(
                List.of(
                        refund("re_a", "ch_one", "rfd_local", 3000, "pending"),
                        refund("re_b", "ch_one", "rfd_local", 3000, "pending")),
                false);
        server.handler = e -> respond(e, 200, body);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(gateway.findByRefundId("ch_one", "rfd_local").value()).isInstanceOf(Incomplete.class);
            tx.setRollbackOnly();
        });
        assertThat(gateway.findByRefundId("ch_one", "rfd_local").value()).isInstanceOf(Incomplete.class);
        assertThat(count("SELECT count(*) FROM recon.issue")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM io.processor_fetch")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT actual::text FROM recon.issue")
                        .query(String.class)
                        .single())
                .contains("re_a", "re_b");
    }

    @Test
    void ac_8_7_wrongRetrieveIdentityAndWrongListChargeAreIncomplete() {
        String object = refund("re_other", "ch_wrong", "rfd_local", 3000, "succeeded");
        server.handler = e -> respond(e, 200, object);
        assertThat(gateway.retrieve("re_expected").value()).isInstanceOf(Incomplete.class);
        server.handler = e -> respond(e, 200, page(List.of(object), false));
        assertThat(gateway.findByRefundId("ch_one", "rfd_local").value()).isInstanceOf(Incomplete.class);
    }

    @Test
    void ac_3_17_postArchiveSurvivesCallerRollback() {
        String body = refund("re_one", "ch_one", "rfd_local", 3000, "succeeded");
        server.handler = e -> respond(e, 200, body);
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            assertThat(gateway.create("rfd_local", "ch_one", 3000, "rfd_local").value())
                    .isInstanceOf(Found.class);
            tx.setRollbackOnly();
        });
        assertArchive(body, true);
    }

    private void assertArchive(String body, boolean complete) {
        assertThat(jdbc.sql("SELECT response_body FROM io.processor_fetch")
                        .query(String.class)
                        .single())
                .isEqualTo(body);
        assertThat(jdbc.sql("SELECT complete FROM io.processor_fetch")
                        .query(Boolean.class)
                        .single())
                .isEqualTo(complete);
        assertThat(
                        count(
                                "SELECT count(*) FROM io.processor_fetch WHERE response_sha256=encode(sha256(convert_to(response_body,'UTF8')),'hex')"))
                .isEqualTo(1);
    }
}
