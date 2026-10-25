package dev.ledgerlab.corrections.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.corrections.enabled=false"})
class CorrectionsDisabledTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext context;

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
    void ac_6_1_disabledConfigurationDoesNotRegisterController() throws Exception {
        assertThat(context.getBeansOfType(CorrectionController.class)).isEmpty();
        try (var http = HttpClient.newHttpClient()) {
            for (String path : List.of("/api/corrections", "/api/corrections/cor_probe")) {
                for (String method : List.of("GET", "POST")) {
                    var response = http.send(
                            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                    .header("Content-Type", "application/json")
                                    .method(method, HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(404);
                }
            }
        }
    }
}
