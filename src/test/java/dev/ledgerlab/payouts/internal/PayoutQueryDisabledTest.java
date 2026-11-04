package dev.ledgerlab.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.corrections.enabled=false"})
class PayoutQueryDisabledTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

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
    void ac_7_23_publicConfigurationDoesNotRegisterQueryRoute() throws Exception {
        assertThat(context.getBeansOfType(PayoutOperatorController.class)).isEmpty();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/payouts/mpo_probe/query"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
            var list = http.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/payouts"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(list.statusCode()).isEqualTo(200);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM io.outbound_command")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
