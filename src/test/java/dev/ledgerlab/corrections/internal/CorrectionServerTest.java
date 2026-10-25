package dev.ledgerlab.corrections.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
class CorrectionServerTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PaymentCapture capture;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @Autowired
    ObjectMapper mapper;

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
    void ac_6_1_noHttpEndpointCanWriteLedgerOrReadonlyResources() throws Exception {
        var paths = List.of(
                "/api/ledger/entries",
                "/api/ledger/entries/je_probe",
                "/api/ledger/postings",
                "/api/ledger/accounts/1101",
                "/api/payments",
                "/api/payments/ch_probe",
                "/api/overview",
                "/api/batches",
                "/api/batches/po_probe",
                "/api/recon/issues",
                "/api/recon/issues/iss_probe",
                "/api/bank-lines/bt_probe",
                "/api/statement-lines/file_probe/1",
                "/api/events/evt_probe",
                "/api/refunds/rfd_probe");
        try (var http = HttpClient.newHttpClient()) {
            for (String path : paths)
                for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                    var response = http.send(
                            HttpRequest.newBuilder(uri(path))
                                    .header("Content-Type", "application/json")
                                    .method(method, HttpRequest.BodyPublishers.ofString("{\"amount\":1}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as("%s %s", method, path).isIn(404, 405);
                }
        }
    }

    @Test
    void ac_6_3_defaultEnabledHttpUsesAppTransactionAndReadonlyViews() throws Exception {
        var now = clock.instant();
        jdbc.sql("""
                INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                    signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
                VALUES ('evt_cor_http','charge.succeeded','test',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                    :now,:now,:now,:now,'RECEIVED',:now)
                """).param("now", now.atOffset(ZoneOffset.UTC)).update();
        tx.executeWithoutResult(ignored -> capture.capture(new ChargeEvidence(
                "evt_cor_http", "ch_cor_http", null, 10000L, 10000L, "cad", true, "succeeded", false, now, now)));
        String entry = jdbc.sql("SELECT id FROM ledger.journal_entry WHERE source_key='payment:ch_cor_http:captured'")
                .query(String.class)
                .single();
        String body = mapper.writeValueAsString(Map.of(
                "type",
                "VOID_CAPTURE",
                "originalEntryId",
                entry,
                "reasonCode",
                "OTHER",
                "reasonNote",
                "synthetic error",
                "evidence",
                List.of(Map.of("type", "inbox_event", "id", "evt_cor_http")),
                "requestedBy",
                "test-operator"));
        try (var http = HttpClient.newHttpClient()) {
            var first = http.send(
                    HttpRequest.newBuilder(uri("/api/corrections"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(201);
            String id =
                    mapper.readTree(first.body()).path("correction").path("id").asString();
            var detail = http.send(
                    HttpRequest.newBuilder(uri("/api/corrections/" + id)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(detail.body())).isEqualTo(mapper.readTree(first.body()));
            var refund = http.send(
                    HttpRequest.newBuilder(uri("/api/refunds"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "voided-payment")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"payment_ref\":\"ch_cor_http\",\"amount\":1,\"merchant_refund_ref\":\"after-void\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(refund.statusCode()).isEqualTo(409);
            assertThat(mapper.readTree(refund.body()).path("code").asString()).isEqualTo("PAYMENT_NOT_REFUNDABLE");
            assertThat(jdbc.sql("SELECT count(*) FROM io.idempotency_record")
                            .query(Long.class)
                            .single())
                    .isZero();
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
