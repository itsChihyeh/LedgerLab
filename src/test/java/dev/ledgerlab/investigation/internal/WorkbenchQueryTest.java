package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.LedgerLabApplication;
import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.ScenarioHttpReplay;
import dev.ledgerlab.testsupport.ScenarioReplay;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkbenchQueryTest {
    @TestConfiguration(proxyBeanMethods = false)
    @Import(PaymentQueryTest.Controls.class)
    static class Config {
        @Bean
        @Primary
        MutableClock queryClock() {
            return new MutableClock(Instant.parse("2026-11-05T16:00:00Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sample-a-1-3", "ac-5-3"})
    void ac_1_11_ac_2_16_ac_5_15_everyQueryUsesOneReadonlySnapshotAndShowsExternalEvidence(String scenario)
            throws Exception {
        var db = TestDatabase.freshBlank();
        Path directory = Path.of("src/test/resources/scenarios", scenario);
        try (var processor = new ScenarioHttpReplay(directory);
                var http = HttpClient.newHttpClient();
                var app = new SpringApplication(LedgerLabApplication.class, Config.class)
                        .run(
                                "--server.port=0",
                                "--spring.datasource.url=" + db.url(),
                                "--spring.datasource.password=" + TestDatabase.APP_PASSWORD,
                                "--spring.flyway.password=" + TestDatabase.MIGRATOR_PASSWORD,
                                "--ledgerlab.readonly-datasource.password=" + TestDatabase.READONLY_PASSWORD,
                                "--ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET,
                                "--ledgerlab.stripe.api-key=sk_test_scenario_synthetic_only",
                                "--ledgerlab.stripe.api-base-url=" + processor.baseUrl(),
                                "--ledgerlab.inbox.scheduling-enabled=false",
                                "--ledgerlab.bank.scheduling-enabled=false")) {
            int port = Objects.requireNonNull(((WebServerApplicationContext) app).getWebServer())
                    .getPort();
            String base = "http://127.0.0.1:" + port;
            var jdbc = app.getBean(JdbcClient.class);
            var clock = app.getBean(MutableClock.class);
            var json = app.getBean(ObjectMapper.class);
            var api = new Api(http, base, json, clock);
            var readonly = app.getBean("investigationDataSource", DataSource.class);
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .isEqualTo("app");
            for (String sql : List.of(
                    "SELECT * FROM core.payment",
                    "SELECT * FROM io.inbox_event",
                    "SELECT * FROM recon.issue",
                    "UPDATE recon.v_issue SET status='RESOLVED'",
                    "DELETE FROM recon.v_bank_line",
                    "UPDATE ledger.v_account SET balance=0")) Sql.assertRejected("42501", readonly, sql);
            assertThat(api.ok("/api/overview").path("accounts").size()).isEqualTo(8);
            assertThat(api.ok("/api/batches").path("batches").isEmpty()).isTrue();
            assertThat(api.ok("/api/recon/issues").path("issues").isEmpty()).isTrue();
            assertThat(api.ok("/api/recon/runs").path("runs").isEmpty()).isTrue();
            new ScenarioReplay(directory, processor, http, base, clock, app.getBean(InboxWorker.class), jdbc).run();
            JsonNode aliases = json.readTree(Files.readAllBytes(directory.resolve("truth.json")))
                    .path("aliases");
            String batchId = aliases.path("B1").asString();
            String paymentRef = aliases.path("P1").asString();
            JsonNode payment = api.ok("/api/payments/" + paymentRef);
            assertThat(payment.path("actualFee").asLong()).isEqualTo(320);
            assertThat(payment.path("net").asLong()).isEqualTo(9680);
            assertThat(payment.path("postings").size()).isEqualTo(9);
            assertThat(payment.path("batch").path("batchId").asString()).isEqualTo(batchId);
            assertThat(payment.path("batch").path("status").asString()).isEqualTo("BANKED");
            assertThat(payment.path("batch").path("settlementDate").asString()).isEqualTo("2026-11-10");
            assertThat(payment.path("batch").path("arrivalDate").isTextual()).isTrue();
            String bankId = payment.path("bankDeposit").path("bankTxnId").asString();
            JsonNode bank = api.ok("/api/bank-lines/" + bankId);
            assertThat(bank.path("bankLine").path("matchedBatchId").asString()).isEqualTo(batchId);
            assertThat(bank.path("bankLine").path("amount"))
                    .isEqualTo(payment.path("bankDeposit").path("amount"));
            assertThat(bank.path("bankLine").path("bookingDate"))
                    .isEqualTo(payment.path("bankDeposit").path("bookingDate"));
            assertThat(bank.path("postings").size()).isEqualTo(2);
            assertThat(bank.path("versions").size()).isEqualTo(1);
            JsonNode batch = api.ok("/api/batches/" + batchId);
            assertThat(batch.path("postings").size()).isEqualTo(7);
            assertThat(batch.path("entries").size()).isEqualTo(2);
            assertThat(batch.path("history").size()).isGreaterThanOrEqualTo(3);
            assertThat(batch.path("bankLines").get(0).path("bankTxnId").asString())
                    .isEqualTo(bankId);
            assertThat(batch.path("lines").get(0).path("balanceTxnId").asString())
                    .startsWith("txn_");
            assertThat(api.ok("/api/batches?limit=1").path("batches").size()).isEqualTo(1);
            assertThat(api.ok("/api/recon/runs?limit=1").path("runs").size()).isEqualTo(1);
            JsonNode overview = api.ok("/api/overview");
            assertThat(overview.path("accounts").size()).isEqualTo(8);
            if (scenario.equals("sample-a-1-3")) {
                verifyBatchReplay(api, jdbc, processor, directory, app.getBean(InboxWorker.class), paymentRef, batchId);
                assertThat(overview.path("inTransit").asLong()).isZero();
                assertThat(overview.path("bankCash").asLong()).isEqualTo(9680);
                assertThat(overview.path("merchantAvailable").asLong()).isEqualTo(9680);
                assertThat(overview.path("merchantOwes").asLong()).isZero();
                assertThat(overview.path("unappliedCash").asLong()).isZero();
                assertThat(overview.path("openIssuesByCategory").isEmpty()).isTrue();
                assertThat(overview.path("batchesByStatus").path("BANKED").asLong())
                        .isEqualTo(1);
            } else {
                verifyIssues(api, jdbc, batchId, bankId);
                // Existing account privilege permits a cache fixture; no business refund action is introduced in P2.
                jdbc.sql("UPDATE ledger.account SET balance=-320 WHERE code=2102")
                        .update();
                assertThat(api.ok("/api/overview").path("merchantOwes").asLong())
                        .isEqualTo(320);
                assertThat(api.ok("/api/overview").path("merchantAvailable").asLong())
                        .isEqualTo(-320);
            }
            for (String path : List.of("/api/batches", "/api/recon/issues", "/api/recon/runs")) {
                for (String limit : List.of("0", "-1", "101", "2147483648", "abc", "1.5"))
                    assertThat(api.get(path + "?limit=" + limit).statusCode()).isEqualTo(400);
                assertThat(api.ok(path + "?limit=100").has("asOf")).isTrue();
            }
            for (String path : List.of(
                    "/api/batches/missing",
                    "/api/recon/issues/missing",
                    "/api/bank-lines/missing",
                    "/api/statement-lines/missing/1"))
                assertThat(api.get(path).statusCode()).isEqualTo(404);
            assertThat(api.get("/api/statement-lines/missing/0").statusCode()).isEqualTo(400);
            assertThat(api.get("/api/recon/issues?status=INVALID").statusCode()).isEqualTo(400);
            assertThat(api.ok("/api/recon/issues?category=UNKNOWN")
                            .path("issues")
                            .isEmpty())
                    .isTrue();
        } finally {
            db.drop();
        }
    }

    private void verifyIssues(Api api, JdbcClient jdbc, String batchId, String bankId) throws Exception {
        JsonNode list = api.ok("/api/recon/issues?status=OPEN&category=BATCH_TOTAL_MISMATCH&limit=1")
                .path("issues");
        assertThat(list.size()).isEqualTo(1);
        String id = list.get(0).path("id").asString();
        JsonNode detail = api.ok("/api/recon/issues/" + id);
        JsonNode amount = detail.path("amounts").get(0);
        assertThat(amount.path("expectedAmount").asLong()).isEqualTo(25096);
        assertThat(amount.path("actualAmount").asLong()).isEqualTo(23639);
        assertThat(amount.path("difference").asLong()).isEqualTo(1457);
        assertThat(detail.path("findings").size())
                .isEqualTo(jdbc.sql("SELECT count(*) FROM recon.finding WHERE issue_id=?")
                        .param(id)
                        .query(Integer.class)
                        .single());
        assertThat(detail.path("findings").size()).isGreaterThan(1);
        JsonNode refs = detail.path("references");
        assertThat(refs.path("batchIds").toString()).contains(batchId);
        assertThat(refs.path("bankTxnIds").toString()).contains(bankId);
        assertThat(refs.path("eventIds").size()).isGreaterThan(1);
        assertThat(refs.path("entryIds").size()).isGreaterThan(2);
        assertThat(refs.path("statementLines").size()).isGreaterThan(3);
        for (JsonNode line : refs.path("statementLines")) {
            JsonNode response =
                    api.ok("/api/statement-lines/" + line.path("fileId").asString() + "/"
                            + line.path("lineSeq").asInt());
            assertThat(response.path("statementLine").path("statementLineId")).isEqualTo(line.path("statementLineId"));
        }
        for (JsonNode issue : api.ok("/api/recon/issues?limit=100").path("issues")) {
            var response = api.ok("/api/recon/issues/" + issue.path("id").asString());
            if (issue.path("category").asString().equals("FEE_MISMATCH")) {
                assertThat(response.path("amounts")
                                .get(0)
                                .path("expectedAmount")
                                .asLong())
                        .isEqualTo(175);
                assertThat(response.path("amounts").get(0).path("actualAmount").asLong())
                        .isEqualTo(205);
            }
        }
        var ledger = jdbc.sql("SELECT row_to_json(e)::text FROM ledger.journal_entry e ORDER BY seq")
                .query(String.class)
                .list();
        for (String invalid : List.of("{}", "{\"reason\":null}", "{\"reason\":\" \"}"))
            assertThat(api.post("/api/recon/issues/" + id + "/accept", invalid).statusCode())
                    .isEqualTo(400);
        assertThat(api.post("/api/recon/issues/missing/accept", "{\"reason\":\"test\"}")
                        .statusCode())
                .isEqualTo(404);
        assertThat(api.post("/api/recon/issues/" + id + "/accept", "{\"reason\":\"Reviewed synthetic evidence\"}")
                        .statusCode())
                .isEqualTo(204);
        JsonNode accepted = api.ok("/api/recon/issues/" + id);
        assertThat(accepted.path("issue").path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("findings")).isEqualTo(detail.path("findings"));
        assertThat(accepted.path("history").size())
                .isEqualTo(detail.path("history").size() + 1);
        assertThat(api.post("/api/recon/issues/" + id + "/resolve", "{\"note\":\"Already closed\"}")
                        .statusCode())
                .isEqualTo(409);
        String other = api.ok("/api/recon/issues?status=OPEN&category=FEE_MISMATCH")
                .path("issues")
                .get(0)
                .path("id")
                .asString();
        assertThat(api.post("/api/recon/issues/" + other + "/resolve", "{}").statusCode())
                .isEqualTo(400);
        assertThat(api.post("/api/recon/issues/" + other + "/resolve", "{\"note\":\"Handled outside this simulation\"}")
                        .statusCode())
                .isEqualTo(204);
        assertThat(api.ok("/api/recon/issues/" + other)
                        .path("issue")
                        .path("status")
                        .asString())
                .isEqualTo("RESOLVED");
        assertThat(jdbc.sql("SELECT row_to_json(e)::text FROM ledger.journal_entry e ORDER BY seq")
                        .query(String.class)
                        .list())
                .isEqualTo(ledger);
        assertThat(api.ok("/api/overview").path("openIssuesByCategory").has("FEE_MISMATCH"))
                .isFalse();
    }

    private void verifyBatchReplay(
            Api api,
            JdbcClient jdbc,
            ScenarioHttpReplay processor,
            Path directory,
            InboxWorker worker,
            String paymentRef,
            String batchId)
            throws Exception {
        JsonNode before = api.ok("/api/payments/" + paymentRef);
        JsonNode batchBefore = api.ok("/api/batches/" + batchId);
        JsonNode accounts = api.ok("/api/overview").path("accounts");
        var postings = jdbc.sql("SELECT row_to_json(p)::text FROM ledger.posting p ORDER BY entry_id, line_no")
                .query(String.class)
                .list();
        String original = Files.readString(directory.resolve("events/003.json"));
        String eventId = "evt_batch_replay";
        String body = original.replace(api.json.readTree(original).path("id").asString(), eventId);
        JsonNode responses = api.json
                .readTree(Files.readAllBytes(directory.resolve("manifest.json")))
                .path("steps")
                .get(2)
                .path("responses");
        processor.expect(responses);
        byte[] raw = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(api.http
                        .send(
                                HttpRequest.newBuilder(URI.create(api.base + "/webhooks/stripe"))
                                        .header("Content-Type", "application/json")
                                        .header(
                                                "Stripe-Signature",
                                                StripeSignatures.sign(
                                                        raw, api.clock.instant().getEpochSecond()))
                                        .POST(HttpRequest.BodyPublishers.ofByteArray(raw))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
        worker.processAvailable();
        processor.assertConsumed();
        JsonNode after = api.ok("/api/payments/" + paymentRef);
        JsonNode batchAfter = api.ok("/api/batches/" + batchId);
        assertThat(after.path("deliveries").size())
                .isEqualTo(before.path("deliveries").size() + 1);
        assertThat(batchAfter.path("deliveries").size())
                .isEqualTo(batchBefore.path("deliveries").size() + 1);
        JsonNode added = after.path("deliveries").get(after.path("deliveries").size() - 1);
        assertThat(added.path("subject").asString()).isEqualTo("batch");
        assertThat(added.path("eventId").asString()).isEqualTo(eventId);
        assertThat(added.path("eventType").asString()).isEqualTo("payout.reconciliation_completed");
        assertThat(added.path("outcome").asString()).isEqualTo("NEW");
        assertThat(added.path("result").asString()).isEqualTo("DUPLICATE_FACT");
        assertThat(added.path("status").asString()).isEqualTo("PROCESSED");
        assertThat(batchAfter
                        .path("deliveries")
                        .get(batchAfter.path("deliveries").size() - 1))
                .isEqualTo(added);
        var expectedIds = jdbc.sql("SELECT id FROM io.webhook_delivery ORDER BY received_at, id")
                .query(Long.class)
                .list();
        assertThat(after.path("deliveries")
                        .valueStream()
                        .map(d -> d.path("id").asLong())
                        .toList())
                .isEqualTo(expectedIds);
        assertThat(after.path("deliveries").get(0).path("subject").asString()).isEqualTo("payment");
        for (JsonNode delivery : batchAfter.path("deliveries"))
            assertThat(delivery.path("subject").asString()).isEqualTo("batch");
        JsonNode event = api.ok("/api/events/" + eventId);
        assertThat(event.path("subject").asString()).isEqualTo("batch");
        assertThat(event.path("subjectId").asString()).isEqualTo(batchId);
        assertThat(event.path("result").asString()).isEqualTo("DUPLICATE_FACT");
        assertThat(event.path("deliveries").size()).isEqualTo(1);
        assertThat(event.path("deliveries").get(0)).isEqualTo(added);
        assertThat(event.toString()).doesNotContain("payload", "signature", "sha256", "signedAt", "signed_at");
        assertThat(after.path("postings")).isEqualTo(before.path("postings"));
        assertThat(api.ok("/api/overview").path("accounts")).isEqualTo(accounts);
        assertThat(jdbc.sql("SELECT row_to_json(p)::text FROM ledger.posting p ORDER BY entry_id, line_no")
                        .query(String.class)
                        .list())
                .isEqualTo(postings);
    }

    private record Api(HttpClient http, String base, ObjectMapper json, MutableClock clock) {
        HttpResponse<String> get(String path) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String body) throws Exception {
            return http.send(
                    HttpRequest.newBuilder(URI.create(base + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        JsonNode ok(String path) throws Exception {
            PaymentQueryTest.PROBE.observations.clear();
            var response = get(path);
            assertThat(response.statusCode())
                    .as("%s: %s", path, response.body())
                    .isEqualTo(200);
            assertThat(PaymentQueryTest.PROBE.observations.size()).isGreaterThanOrEqualTo(2);
            PaymentQueryTest.assertQueryCredentials(PaymentQueryTest.PROBE.observations.size());
            JsonNode result = json.readTree(response.body());
            assertThat(Instant.parse(result.path("asOf").path("at").asString())).isEqualTo(clock.instant());
            return result;
        }
    }
}
