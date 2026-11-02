package dev.ledgerlab.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.bank.scheduling-enabled=false",
            "ledgerlab.api.client-id=payout-http-test", "ledgerlab.api.idempotency-lock-timeout=150ms"
        })
class PayoutServerTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Ledger ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    PayoutApiProperties properties;

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
    static void cleanup() {
        DB.drop();
    }

    @Test
    void ac_7_1_ac_7_4_realHttpWiresReadonlyQueriesOverviewAndSharedIdempotencyNamespace() throws Exception {
        tx.executeWithoutResult(s -> {
            ledger.lockFunds();
            ledger.post(new EntryRequest(
                    EntryType.PAYMENT_CAPTURED,
                    "fixture:overview",
                    clock.instant(),
                    SubjectType.PAYMENT,
                    "pay_fixture",
                    List.of(
                            PostingLine.debit(Account.BANK_CASH, 4854),
                            PostingLine.debit(Account.SETTLEMENT_IN_TRANSIT, 3000),
                            PostingLine.credit(Account.MERCHANT_AVAILABLE, 3854),
                            PostingLine.credit(Account.UNAPPLIED_CASH, 1000),
                            PostingLine.credit(Account.PROCESSOR_RECEIVABLE, 3000))));
        });
        assertThat(properties.getClientId()).isEqualTo("payout-http-test");
        assertThat(properties.getIdempotencyLockTimeout()).isEqualTo(Duration.ofMillis(150));
        try (var http = HttpClient.newHttpClient()) {
            var overview = get(http, "/api/overview");
            assertThat(overview.path("freeCash").asLong()).isEqualTo(854);
            assertThat(overview.path("payoutInTransit").asLong()).isZero();
            var request = HttpRequest.newBuilder(uri("/api/payouts"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "K1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":854,\"merchantPayoutRef\":\"MP-1\"}"))
                    .build();
            var first = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(201);
            var replay = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(first.body());
            String id = mapper.readTree(first.body()).path("payoutId").asString();
            assertThat(get(http, "/api/payouts/" + id).path("postings").size()).isEqualTo(2);
            assertThat(get(http, "/api/payouts").path("payouts").size()).isEqualTo(1);
            overview = get(http, "/api/overview");
            assertThat(overview.path("payoutInTransit").asLong()).isEqualTo(854);
            assertThat(overview.path("freeCash").asLong()).isZero();
            assertThat(overview.path("merchantAvailable").asLong()).isEqualTo(3000);
            assertThat(overview.path("bankCash").asLong()).isEqualTo(4854);
            assertThat(jdbc.sql("SELECT client_id FROM io.idempotency_record")
                            .query(String.class)
                            .single())
                    .isEqualTo("payout-http-test");
            var conflict = http.send(
                    HttpRequest.newBuilder(uri("/api/refunds"))
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", "K1")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"payment_ref\":\"ch_missing\",\"amount\":1,\"merchant_refund_ref\":\"MR-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(conflict.statusCode()).isEqualTo(422);
            assertThat(mapper.readTree(conflict.body()).path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private JsonNode get(HttpClient http, String path) throws Exception {
        var result = http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        return mapper.readTree(result.body());
    }
}
