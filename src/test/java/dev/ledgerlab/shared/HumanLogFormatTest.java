package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.LogEvents;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-12.1, the other half: with {@code LEDGERLAB_LOG_FORMAT} unset the console stays human-readable, so local
 * development and test output do not change. The unique property keeps this context out of the shared cache, so the
 * logging system is initialised from this class's own settings.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledgerlab.inbox.scheduling-enabled=false", "ledgerlab.test.log-format=human"})
@ExtendWith(OutputCaptureExtension.class)
class HumanLogFormatTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::url);
        registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
        registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
        registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
        registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
        registry.add("STRIPE_API_KEY", () -> "");
    }

    @AfterAll
    static void close() {
        HTTP.close();
        DB.drop();
    }

    @LocalServerPort
    int port;

    @Test
    void ac_12_1_withoutTheSwitchTheConsoleIsNotJson(CapturedOutput out) throws Exception {
        // Only this test's own output counts. The capture also holds lines printed before this context took over the
        // logging system, such as an earlier JSON-logging context shutting down when the context cache evicts it
        // (seen in CI, 2026-11-07, where the test order differs from Windows).
        int start = out.getAll().length();
        try (LogEvents events = LogEvents.attach()) {
            HttpResponse<Void> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/evt_none"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
            events.await(line -> requestId.equals(line.getMDCPropertyMap().get("requestId")), Duration.ofSeconds(10));
        }
        JsonMapper json = JsonMapper.builder().build();
        String own = out.getAll().substring(start);
        assertThat(own).isNotBlank();
        assertThat(own.lines().filter(line -> isJson(json, line))).isEmpty();
    }

    private static boolean isJson(JsonMapper json, String line) {
        try {
            return json.readTree(line).isObject();
        } catch (RuntimeException notJson) {
            return false;
        }
    }
}
