package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** AC-12.4: Actuator lives on its own port and shows only what the design lists. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledgerlab.inbox.scheduling-enabled=false")
class ActuatorExposureTest {
    private static final TestDatabase.Handle DB = TestDatabase.freshBlank();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    @LocalManagementPort
    int managementPort;

    @Test
    void ac_12_4_managementIsOnItsOwnPortAndTheMainPortHasNoActuator() throws Exception {
        assertThat(managementPort).isPositive().isNotEqualTo(port);
        assertThat(status(port, "/actuator/health")).isEqualTo(404);
        assertThat(status(port, "/actuator/prometheus")).isEqualTo(404);
        assertThat(status(port, "/actuator")).isEqualTo(404);
    }

    @Test
    void ac_12_4_healthOnTheManagementPortReportsStatusAndNothingElse() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("status");
        assertThat(body.get("status").asString()).isEqualTo("UP");
    }

    @Test
    void ac_12_4_onlyHealthAndPrometheusAreExposed() throws Exception {
        assertThat(status(managementPort, "/actuator/prometheus")).isEqualTo(200);
        for (String hidden : List.of(
                "/actuator/env",
                "/actuator/heapdump",
                "/actuator/loggers",
                "/actuator/beans",
                "/actuator/configprops",
                "/actuator/metrics",
                "/actuator/threaddump")) {
            assertThat(status(managementPort, hidden)).as(hidden).isEqualTo(404);
        }
    }

    private static int status(int port, String path) throws Exception {
        return get(port, path).statusCode();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
