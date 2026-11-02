package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.TestDatabase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real simulator in a separate JVM/database. Tests communicate exclusively over HTTP. */
public final class SampleBankProcess implements AutoCloseable {
    private static boolean built;
    private final TestDatabase.Handle db;
    private final HttpClient client = HttpClient.newHttpClient();
    private final Process process;
    private final URI endpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    SampleBankProcess() throws Exception {
        this("2026-10-20T14:00:00Z", 12909);
    }

    /** E2E fixtures may align independent simulator cash and time with their replayed opening evidence. */
    public SampleBankProcess(String start, long chargeAmount) throws Exception {
        build();
        db = TestDatabase.freshBlank();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var command = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                        .toString(),
                "-jar",
                "simulator/target/ledgerlab-simulator-0.0.1-SNAPSHOT.jar",
                "--server.port=0",
                "--spring.datasource.url=" + db.url(),
                "--spring.datasource.password=" + TestDatabase.SIMULATOR_PASSWORD,
                "--sim.stripe.webhook-secret=synthetic-sample-secret",
                "--sim.stripe.webhook-url=http://127.0.0.1:1/webhook",
                "--sim.clock.start=" + start,
                "--spring.main.banner-mode=off"));
        process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var ready = new CompletableFuture<Integer>();
        Thread.startVirtualThread(() -> {
            StringBuilder output = new StringBuilder();
            try (var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                var pattern = Pattern.compile("Tomcat started on port ([0-9]+)");
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    var match = pattern.matcher(line);
                    if (match.find()) ready.complete(Integer.parseInt(match.group(1)));
                }
                ready.completeExceptionally(new AssertionError(output.toString()));
            } catch (Exception e) {
                ready.completeExceptionally(e);
            }
        });
        try {
            endpoint = URI.create("http://127.0.0.1:" + ready.get(60, TimeUnit.SECONDS));
        } catch (Exception e) {
            process.destroyForcibly();
            db.drop();
            client.close();
            throw e;
        }
        // Establish actual simulator cash via charges and settlement, without SQL fixtures.
        try {
            json("POST", "/sim/charges", "{\"amount\":" + chargeAmount + "}");
            String payout =
                    json("POST", "/sim/payouts", "{}").path("payout").path("id").asString();
            assertThat(payout).startsWith("po_");
            json("POST", "/sim/payouts/" + payout + "/paid", "{}");
            advance(86400);
        } catch (Exception | AssertionError failure) {
            close();
            throw failure;
        }
    }

    private static synchronized void build() throws Exception {
        if (built) return;
        // Fresh CI checkouts have no jar; build the existing standalone module, without new dependencies.
        var args = new ArrayList<String>();
        if (System.getProperty("os.name").startsWith("Windows"))
            args.addAll(List.of(
                    "cmd.exe", "/c", Path.of("mvnw.cmd").toAbsolutePath().toString()));
        else args.addAll(List.of("sh", "./mvnw"));
        args.addAll(List.of("-B", "-f", "simulator/pom.xml", "package", "-DskipTests"));
        var build = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(Path.of("target/t09-simulator-build.log").toFile())
                .start();
        if (!build.waitFor(180, TimeUnit.SECONDS)) {
            build.destroyForcibly();
            throw new AssertionError("Simulator build timed out");
        }
        assertThat(build.exitValue())
                .as(new String(Files.readAllBytes(Path.of("target/t09-simulator-build.log")), StandardCharsets.UTF_8))
                .isZero();
        built = true;
    }

    public String url() {
        return endpoint.toString();
    }

    JsonNode json(String method, String path, String body) throws Exception {
        return mapper.readTree(request(method, path, body));
    }

    public byte[] statement(String date) throws Exception {
        return request("GET", "/sim/bank/statements/" + date, "").getBytes(StandardCharsets.UTF_8);
    }

    public void advance(int seconds) throws Exception {
        json("POST", "/sim/clock/advance", "{\"seconds\":" + seconds + "}");
    }

    void plan(String id, String fault, int parameter) throws Exception {
        json(
                "PUT",
                "/sim/bank/plan",
                mapper.writeValueAsString(java.util.Map.of("target_ref", id, "fault", fault, "parameter", parameter)));
    }

    private String request(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response.body();
    }

    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS))
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        client.close();
        db.drop();
    }
}
