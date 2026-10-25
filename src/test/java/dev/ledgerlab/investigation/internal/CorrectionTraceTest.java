package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.corrections.CorrectionCommand;
import dev.ledgerlab.corrections.Corrections;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.settlement.BankStatements;
import dev.ledgerlab.testsupport.CorrectionPageFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.settlement.scheduling-enabled=false",
            "ledgerlab.bank.scheduling-enabled=false", "ledgerlab.outbound.scheduling-enabled=false"
        })
class CorrectionTraceTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext app;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_API_KEY", () -> "");
        registry.add("STRIPE_WEBHOOK_SECRET", () -> "whsec_test_only");
    }

    @AfterAll
    static void drop() {
        DB.drop();
    }

    @Test
    void ac_6_14_sampleEAndBankApplicationResolveEvidenceAndReadonlyStates() throws Exception {
        var sample = CorrectionPageFixture.seed(app);
        var trace = get("/api/correction-traces/" + sample.correctionId());
        assertThat(trace.path("evidence").size()).isEqualTo(4);
        assertThat(trace.path("evidence").get(1).path("links").get(0).path("id").asString())
                .isEqualTo(sample.fileId());
        assertThat(trace.path("evidence")
                        .get(1)
                        .path("links")
                        .get(1)
                        .path("kind")
                        .asString())
                .isEqualTo("batch-lines");
        assertThat(trace.path("evidence").get(3).path("links").get(0).path("id").asString())
                .isEqualTo("po_t07_b6");
        assertThat(trace.path("objects").toString()).contains("SETTLED", "CONFIRMED", "RESOLVED");
        assertThat(trace.path("history").toString()).contains("CAPTURED", "HELD", "RECEIVED", "CONFIRMED");
        assertThat(trace.path("asOf").path("seq").asLong()).isPositive();
        var entry = get("/api/entries/" + sample.entryId());
        assertThat(entry.path("reversedBy").asString()).isEqualTo(sample.correctionId());
        assertThat(entry.path("postings").size()).isEqualTo(2);
        assertThat(entry.path("postings").get(0).path("amount").asLong()).isEqualTo(4500);
        var detail = get("/api/corrections/" + sample.correctionId());
        assertThat(detail.path("correction").path("params").path("newAmount").asLong())
                .isEqualTo(5400);
        for (JsonNode generated : detail.path("entries")) {
            var found = get("/api/entries/" + generated.path("entryId").asString());
            assertThat(found.path("entry").path("correctionId").asString()).isEqualTo(sample.correctionId());
            assertThat(found.path("reversedBy").isNull()).isTrue();
        }
        assertThat(get("/api/recon/issues/" + sample.issueId())
                        .path("issue")
                        .path("resolutionCorrectionId")
                        .asString())
                .isEqualTo(sample.correctionId());
        assertThat(get("/api/statement-lines/" + sample.fileId() + "/1")
                        .path("statementLine")
                        .path("gross")
                        .asLong())
                .isEqualTo(5400);
        assertThat(get("/api/events/evt_t07_p5").path("eventId").asString()).isEqualTo("evt_t07_p5");
        var readonly = app.getBean("investigationDataSource", DataSource.class);
        assertThat(JdbcClient.create(readonly)
                        .sql("SELECT current_user")
                        .query(String.class)
                        .single())
                .isEqualTo("readonly");
        for (String sql : List.of(
                "SELECT * FROM ledger.correction",
                "SELECT * FROM io.processor_fetch",
                "UPDATE ledger.v_correction SET reason_note='bad'",
                "DELETE FROM core.v_payment")) Sql.assertRejected("42501", readonly, sql);

        String date = app.getBean(Clock.class)
                .instant()
                .atOffset(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .toString();
        String csv = "H,BANK_STATEMENT,SIM-CAD-001," + date + "," + date + ",1,CAD\r\n"
                + "D,bt_t07_a,CREDIT,2500,CAD,BOOKED,po_t07_b6," + date + "," + date + ",sample\r\n"
                + "D,bt_t07_b,CREDIT,2713,CAD,BOOKED,po_t07_b6," + date + "," + date + ",sample\r\n"
                + "T,2,5213,0,0,5213\r\n";
        assertThat(app.getBean(BankStatements.class)
                        .importBankStatement("t07.csv", csv.getBytes(StandardCharsets.UTF_8))
                        .status())
                .isEqualTo("ACCEPTED");
        var bank = app.getBean(Corrections.class)
                .submit(new CorrectionCommand(
                        CorrectionType.APPLY_UNAPPLIED_CASH,
                        null,
                        null,
                        "po_t07_b6",
                        List.of("bt_t07_a", "bt_t07_b"),
                        CorrectionReason.SPLIT_DEPOSIT,
                        "synthetic split",
                        List.of(new CorrectionCommand.Evidence("bank_txn", "bt_t07_a")),
                        "operator"));
        var bankTrace = get("/api/correction-traces/" + bank.correction().get("id"));
        assertThat(bankTrace.path("objects").toString()).contains("BANKED", "APPLIED", "bt_t07_a", "bt_t07_b");
        assertThat(bankTrace
                        .path("evidence")
                        .get(0)
                        .path("links")
                        .get(0)
                        .path("kind")
                        .asString())
                .isEqualTo("bank-lines");
        for (var target : bank.targets())
            assertThat(get("/api/entries/" + target.get("entryId"))
                            .path("reversedBy")
                            .isNull())
                    .isTrue();
    }

    @Test
    void ac_6_14_recentIsBoundedAndDescendingWithoutChangingSubjectHistory() throws Exception {
        var now = app.getBean(Clock.class).instant();
        var tx = app.getBean(TransactionTemplate.class);
        String event = recentEvent();
        for (int i = 0; i < 51; i++) {
            String ref = "ch_t07_recent_" + i;
            tx.executeWithoutResult(ignored -> app.getBean(PaymentCapture.class)
                    .capture(new ChargeEvidence(
                            event, ref, null, 100L, 100L, "cad", true, "succeeded", false, now, now)));
            String entry = jdbc.sql("SELECT id FROM ledger.journal_entry WHERE source_key=?")
                    .param("payment:" + ref + ":captured")
                    .query(String.class)
                    .single();
            app.getBean(Corrections.class)
                    .submit(new CorrectionCommand(
                            CorrectionType.VOID_CAPTURE,
                            entry,
                            null,
                            null,
                            null,
                            CorrectionReason.OTHER,
                            "synthetic",
                            List.of(new CorrectionCommand.Evidence("inbox_event", event)),
                            "operator"));
        }
        var recent = get("/api/corrections/recent");
        assertThat(recent.size()).isEqualTo(50);
        var expected = jdbc.sql("SELECT id FROM ledger.correction ORDER BY recorded_at DESC, id DESC LIMIT 50")
                .query(String.class)
                .list();
        for (int i = 0; i < 50; i++)
            assertThat(recent.get(i).path("correction").path("id").asString()).isEqualTo(expected.get(i));
        var last = recent.get(0).path("correction");
        assertThat(get("/api/corrections?subjectType="
                                + last.path("subjectType").asString() + "&subjectId="
                                + last.path("subjectId").asString())
                        .size())
                .isEqualTo(1);
    }

    private String recentEvent() {
        jdbc.sql("""
                INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at,result)
                VALUES ('evt_t07_recent','charge.succeeded','test',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                :now,:now,:now,:now,'PROCESSED',:now,'APPLIED') ON CONFLICT DO NOTHING
                """)
                .param("now", app.getBean(Clock.class).instant().atOffset(java.time.ZoneOffset.UTC))
                .update();
        return "evt_t07_recent";
    }

    @Test
    void ac_6_14_missingRecordsAndWriteMethodsAreRejected() throws Exception {
        for (String path : List.of("/api/correction-traces/missing", "/api/entries/missing")) {
            assertThat(request("GET", path).statusCode()).isEqualTo(404);
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE"))
                assertThat(request(method, path).statusCode()).isEqualTo(405);
        }
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .method(method, HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode get(String path) throws Exception {
        var response = request("GET", path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
