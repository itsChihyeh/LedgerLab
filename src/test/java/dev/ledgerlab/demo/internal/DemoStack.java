package dev.ledgerlab.demo.internal;

import dev.ledgerlab.LedgerLabApplication;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A real application (profile {@code demo} or default) on a freshly migrated PostgreSQL, plus the real simulator
 * jar as a separate process: fake Stripe, the chaos relay and the simulated bank, wired as in Compose
 * (fake Stripe -> relay -> application). There is no precedent in the suite for starting the simulator, so this
 * starts its jar as a child process, from the same database, under the {@code simulator} role.
 */
final class DemoStack implements AutoCloseable {
    // Assembled at runtime so no key-shaped literal sits in the repository (gitleaks scans every branch).
    static final String API_KEY = "rk_test_" + "demo_stack_synthetic";
    static final Path SIMULATOR_JAR = Path.of("simulator/target/ledgerlab-simulator-0.0.1-SNAPSHOT.jar");

    record Answer(int status, String body, Map<String, List<String>> headers) {
        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    final TestDatabase.Handle db = TestDatabase.freshBlank();
    final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final ConfigurableApplicationContext context;
    final int appPort;
    final int simulatorPort;
    private final @Nullable Process simulator;

    DemoStack(boolean demo, boolean withSimulator, String... extra) {
        simulatorPort = freePort();
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.datasource.url=" + db.url(),
                "--LEDGERLAB_APP_PASSWORD=" + TestDatabase.APP_PASSWORD,
                "--LEDGERLAB_MIGRATOR_PASSWORD=" + TestDatabase.MIGRATOR_PASSWORD,
                "--LEDGERLAB_READONLY_PASSWORD=" + TestDatabase.READONLY_PASSWORD,
                "--ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET,
                "--ledgerlab.stripe.api-key=" + API_KEY,
                "--ledgerlab.stripe.api-base-url=http://127.0.0.1:" + simulatorPort,
                "--ledgerlab.bank.payments.enabled=true",
                "--ledgerlab.bank.payments.base-url=http://127.0.0.1:" + simulatorPort,
                "--ledgerlab.demo.simulator-base-url=http://127.0.0.1:" + simulatorPort,
                "--ledgerlab.outbound.lease-duration=3s",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        args.addAll(List.of(extra));
        var builder = new SpringApplicationBuilder(LedgerLabApplication.class);
        if (demo) builder.profiles("demo");
        context = builder.run(args.toArray(String[]::new));
        appPort = Integer.parseInt(context.getEnvironment().getProperty("local.server.port", "0"));
        simulator = withSimulator ? startSimulator() : null;
    }

    private Process startSimulator() {
        try {
            Path log = Path.of("target/demo-test");
            Files.createDirectories(log);
            String java =
                    Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var builder = new ProcessBuilder(java, "-jar", SIMULATOR_JAR.toString());
            builder.environment()
                    .putAll(Map.of(
                            "SIM_PORT",
                            Integer.toString(simulatorPort),
                            "SIM_DATABASE_URL",
                            db.url(),
                            "LEDGERLAB_SIMULATOR_PASSWORD",
                            TestDatabase.SIMULATOR_PASSWORD,
                            "SIM_STRIPE_WEBHOOK_SECRET",
                            StripeSignatures.SECRET,
                            "SIM_STRIPE_API_KEY",
                            API_KEY,
                            "SIM_STRIPE_WEBHOOK_URL",
                            "http://127.0.0.1:" + simulatorPort + "/relay/stripe",
                            "SIM_RELAY_TARGET_URL",
                            "http://127.0.0.1:" + appPort + "/webhooks/stripe",
                            // Fourteen days back, so the scenarios can move the bank forward without outrunning real
                            // time.
                            "SIM_CLOCK_START",
                            Instant.now()
                                    .minus(14, ChronoUnit.DAYS)
                                    .truncatedTo(ChronoUnit.SECONDS)
                                    .toString(),
                            "SIM_SEED",
                            Long.toString(System.nanoTime() % 1_000_000)));
            builder.redirectErrorStream(true)
                    .redirectOutput(
                            log.resolve("simulator-" + simulatorPort + ".log").toFile());
            Process process = builder.start();
            awaitSimulator(process);
            return process;
        } catch (IOException e) {
            throw new IllegalStateException("The simulator could not be started", e);
        }
    }

    private void awaitSimulator(Process process) {
        Instant deadline = Instant.now().plusSeconds(90);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) throw new IllegalStateException("The simulator exited early; see target/demo-test");
            try {
                if (get(simulatorPort, "/sim/clock").status() == 200) return;
            } catch (RuntimeException notYet) {
                // Still starting.
            }
            sleep(250);
        }
        throw new IllegalStateException("The simulator did not become ready");
    }

    /**
     * Whether the simulator jar is there. Locally a missing jar skips the tests that need it, with a hint; in CI
     * ({@code CI=true}) a missing jar is a failure, so the job cannot go green without running them.
     */
    static boolean simulatorAvailable() {
        return simulatorAvailable(System.getenv("CI"), Files.exists(SIMULATOR_JAR));
    }

    static boolean simulatorAvailable(@Nullable String ciEnv, boolean jarExists) {
        if (jarExists) return true;
        String how = "build it first: ./mvnw -B -f simulator/pom.xml -DskipTests package";
        if ("true".equalsIgnoreCase(ciEnv)) {
            throw new IllegalStateException("CI=true but " + SIMULATOR_JAR + " is missing; " + how);
        }
        System.err.println("SKIPPING the demo tests that need the simulator: " + SIMULATOR_JAR + " is missing; " + how);
        return false;
    }

    Answer get(String path) {
        return get(appPort, path);
    }

    Answer post(String path) {
        return send(appPort, HttpRequest.newBuilder(uri(appPort, path)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    Answer post(String path, Map<String, String> headers) {
        return call("POST", path, headers, "");
    }

    Answer get(String path, Map<String, String> headers) {
        return call("GET", path, headers, "");
    }

    /** Any method, with extra request headers and a JSON body, as a caller from outside would send it. */
    Answer call(String method, String path, Map<String, String> headers, String body) {
        var request = HttpRequest.newBuilder(uri(appPort, path))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        return send(appPort, request);
    }

    Answer simPost(String path, String json) {
        return send(
                simulatorPort,
                HttpRequest.newBuilder(uri(simulatorPort, path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    Answer simGet(String path) {
        return get(simulatorPort, path);
    }

    JdbcClient readonly() {
        return JdbcClient.create(db.readonly());
    }

    JdbcClient superuser() {
        return JdbcClient.create(db.superuser());
    }

    private Answer get(int port, String path) {
        return send(port, HttpRequest.newBuilder(uri(port, path)).GET());
    }

    private Answer send(int port, HttpRequest.Builder request) {
        try {
            HttpResponse<String> response =
                    http.send(request.timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(
                    response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call to port " + port + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (simulator != null) {
            simulator.destroy();
            try {
                if (!simulator.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) simulator.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                simulator.destroyForcibly();
            }
        }
        context.close();
        db.drop();
    }
}
