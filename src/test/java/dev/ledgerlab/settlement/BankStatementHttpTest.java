package dev.ledgerlab.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.bank.scheduling-enabled=false"})
class BankStatementHttpTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    BankStatements statements;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @AfterAll
    static void stop() {
        HTTP.close();
        DB.drop();
    }

    private HttpResponse<String> post(byte[] content, String type) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bank-statements"))
                        .header("Content-Type", type)
                        .header("X-Source-Name", "http.csv")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ac_5_6_httpArchivesOriginalBytesAndReturnsStableJson() throws Exception {
        byte[] csv = ("H,BANK_STATEMENT,SIM-CAD-001,2026-10-23,2026-10-23,1,CAD\r\n"
                        + "D,http_bank,CREDIT,100,CAD,BOOKED,unidentified,2026-10-23,2026-10-23,\"银行, memo\"\r\n"
                        + "T,1,100,0,0,100\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var response = post(csv, "text/csv;charset=UTF-8");
        assertThat(response.statusCode()).isEqualTo(200);
        var json = mapper.readTree(response.body());
        assertThat(json.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(json.path("fileId").asText()).startsWith("file_");
        assertThat(post(csv, "text/csv").body()).isEqualTo(response.body());
        assertThat(jdbc.sql("SELECT raw FROM io.imported_file WHERE id=:id")
                        .param("id", json.path("fileId").asText())
                        .query(byte[].class)
                        .single())
                .isEqualTo(csv);
        assertThat(jdbc.sql("SELECT source_name FROM io.imported_file WHERE id=:id")
                        .param("id", json.path("fileId").asText())
                        .query(String.class)
                        .single())
                .isEqualTo("http.csv");
        assertThat(jdbc.sql("SELECT count(*) FROM ledger.journal_entry WHERE source_key='bank_txn:http_bank'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        new BankWaitingSchedule(statements).scan();
    }

    @Test
    void ac_6_9_overviewReflectsReturnedCashAndClosedIssues() throws Exception {
        var before = overview();
        long cash = before.path("bankCash").asLong();
        long unapplied = before.path("unappliedCash").asLong();
        long open =
                before.path("openIssuesByCategory").path("UNMATCHED_BANK_LINE").asLong();
        byte[] credit = ("H,BANK_STATEMENT,SIM-CAD-001,2026-10-24,2026-10-24,1,CAD\r\n"
                        + "D,http_return_credit,CREDIT,9680,CAD,BOOKED,unknown,2026-10-24,2026-10-24,return\r\n"
                        + "T,1,9680,0,0,9680\r\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(post(credit, "text/csv").statusCode()).isEqualTo(200);
        var received = overview();
        assertThat(received.path("unappliedCash").asLong()).isEqualTo(unapplied + 9680);
        assertThat(received.path("bankCash").asLong()).isEqualTo(cash + 9680);
        assertThat(received.path("openIssuesByCategory")
                        .path("UNMATCHED_BANK_LINE")
                        .asLong())
                .isEqualTo(open + 1);
        byte[] debit = ("H,BANK_STATEMENT,SIM-CAD-001,2026-10-25,2026-10-25,1,CAD\r\n"
                        + "D,http_return_debit,DEBIT,9680,CAD,BOOKED,http_return_credit,2026-10-25,2026-10-25,return\r\n"
                        + "T,1,0,9680,9680,0\r\n")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(post(debit, "text/csv").statusCode()).isEqualTo(200);
        var returned = overview();
        assertThat(returned.path("unappliedCash").asLong()).isEqualTo(unapplied);
        assertThat(returned.path("bankCash").asLong()).isEqualTo(cash);
        assertThat(returned.path("openIssuesByCategory")
                        .path("UNMATCHED_BANK_LINE")
                        .asLong())
                .isEqualTo(open);
    }

    private tools.jackson.databind.JsonNode overview() throws Exception {
        var response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/overview"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    @Test
    void ac_5_10_emptyAndMalformedFilesReturnArchivedRejection() throws Exception {
        for (byte[] content : new byte[][] {new byte[0], "invalid CSV".getBytes(StandardCharsets.UTF_8)}) {
            var response = post(content, "text/csv");
            assertThat(response.statusCode()).isEqualTo(200);
            var json = mapper.readTree(response.body());
            assertThat(json.path("status").asText()).isEqualTo("REJECTED");
            assertThat(json.path("rejectReason").asText()).isEqualTo("FILE_INCOMPLETE");
            assertThat(jdbc.sql("SELECT raw FROM io.imported_file WHERE id=:id")
                            .param("id", json.path("fileId").asText())
                            .query(byte[].class)
                            .single())
                    .isEqualTo(content);
        }
    }

    @Test
    void ac_5_10_nonCsvMediaIsRejectedBeforeImport() throws Exception {
        assertThat(post("{}".getBytes(StandardCharsets.UTF_8), "application/json")
                        .statusCode())
                .isEqualTo(415);
    }

    @Test
    void ac_5_20_bankScheduleCanRunWithoutOtherModuleSchedules() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BankStatements.class, () -> statements);
            context.register(BankWaitingSchedule.class);
            context.refresh();
            var schedulers = context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(schedulers).hasSize(1);
            assertThat(schedulers.values().iterator().next().getScheduledTasks())
                    .hasSize(1);
        }
    }
}
