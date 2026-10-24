package dev.ledgerlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T04's JDK HTTP server pattern, replaying exact request/response pairs with no processor dependency. */
public final class ScenarioHttpReplay implements AutoCloseable {
    private final HttpServer server;
    private final ArrayDeque<JsonNode> pending = new ArrayDeque<>();
    private final List<String> failures = new ArrayList<>();
    private final Path directory;
    private final JsonMapper json = JsonMapper.builder().build();

    public ScenarioHttpReplay(Path directory) throws IOException {
        this.directory = directory;
        server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized void expect(JsonNode responses) {
        assertConsumed();
        responses.forEach(pending::add);
    }

    public synchronized void assertConsumed() {
        assertThat(failures).as("Unexpected processor HTTP requests").isEmpty();
        assertThat(pending).as("Unconsumed exported processor responses").isEmpty();
    }

    private synchronized void respond(HttpExchange exchange) throws IOException {
        try {
            JsonNode expected = pending.poll();
            assertThat(expected)
                    .as("Unexpected request %s", exchange.getRequestURI())
                    .isNotNull();
            if (expected == null) throw new AssertionError("No exported response");
            assertThat(exchange.getRequestMethod())
                    .isEqualTo(expected.path("method").asText());
            assertThat(exchange.getRequestURI().getPath())
                    .isEqualTo(expected.path("path").asText());
            Map<String, String> query = new TreeMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null)
                for (String parameter : raw.split("&", -1)) {
                    String[] parts = parameter.split("=", 2);
                    query.put(
                            URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
            assertThat((JsonNode) json.valueToTree(query)).isEqualTo(expected.path("query"));
            assertThat(exchange.getRequestHeaders().getFirst("Stripe-Version")).isEqualTo("2026-09-30.endive");
            byte[] body =
                    Files.readAllBytes(directory.resolve(expected.path("body").asText()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(expected.path("status").asInt(), body.length);
            exchange.getResponseBody().write(body);
        } catch (AssertionError error) {
            failures.add(error.getMessage());
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
