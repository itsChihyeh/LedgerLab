package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.payments.ChargeEvidence;
import dev.ledgerlab.payments.PaymentCapture;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
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
        properties = {
            "ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.api.client-id=http-test",
            "ledgerlab.api.idempotency-lock-timeout=150ms", "ledgerlab.api.idempotency-cleanup-batch-size=3"
        })
class RefundServerTest {
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

    @Autowired
    RefundApiProperties properties;

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
    void ac_2_10_and_ac_3_9_realHttpWiresConfiguredClientAndReadonlyDetail() throws Exception {
        var now = clock.instant();
        jdbc.sql("""
                INSERT INTO io.inbox_event(event_id,event_type,api_version,livemode,payload,payload_sha256,
                    signed_at,occurred_at,received_at,recorded_at,status,next_attempt_at)
                VALUES ('evt_http','charge.succeeded','test',false,'{}'::bytea,encode(sha256('{}'::bytea),'hex'),
                    :now,:now,:now,:now,'RECEIVED',:now)
                """).param("now", now.atOffset(ZoneOffset.UTC)).update();
        tx.executeWithoutResult(ignored -> capture.capture(new ChargeEvidence(
                "evt_http", "ch_http", null, 100L, 100L, "cad", true, "succeeded", false, now, now)));
        long postings = count("ledger.posting");
        assertThat(properties.getIdempotencyLockTimeout()).isEqualTo(Duration.ofMillis(150));
        assertThat(properties.getIdempotencyCleanupBatchSize()).isEqualTo(3);
        try (var http = HttpClient.newHttpClient()) {
            String body = "{\"payment_ref\":\"ch_http\",\"amount\":49,\"merchant_refund_ref\":\"MR-http\"}";
            var request = HttpRequest.newBuilder(uri("/api/refunds"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "K1")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var first = http.send(request, HttpResponse.BodyHandlers.ofString());
            var retry = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(retry.statusCode()).isEqualTo(201);
            assertThat(retry.body()).isEqualTo(first.body());
            assertThat(count("core.refund")).isEqualTo(1);
            assertThat(count("ledger.posting")).isEqualTo(postings + 2);
            assertThat(count("io.outbound_command")).isZero();
            assertThat(count("io.idempotency_record")).isEqualTo(1);
            assertThat(jdbc.sql("SELECT client_id FROM io.idempotency_record")
                            .query(String.class)
                            .single())
                    .isEqualTo("http-test");
            String id = mapper.readTree(first.body()).path("refund_id").asString();
            var detail = http.send(
                    HttpRequest.newBuilder(uri("/api/refunds/" + id)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(detail.body())
                            .path("refund")
                            .path("status")
                            .asString())
                    .isEqualTo("AWAITING_FUNDS");
            var cancel = http.send(
                    HttpRequest.newBuilder(uri("/api/refunds/" + id + "/cancel"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "C1")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"customer request\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cancel.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(cancel.body()).path("status").asString()).isEqualTo("CANCELLED");
            assertThat(count("core.refund")).isEqualTo(1);
            assertThat(count("ledger.posting")).isEqualTo(postings + 4);
            assertThat(count("io.outbound_command")).isZero();
            assertThat(count("io.idempotency_record")).isEqualTo(2);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
