package dev.ledgerlab.demo.internal;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only backend for the demo page's browser tests (AC-9.1, AC-9.2, AC-9.7): the real application in the
 * {@code demo} profile on a fresh PostgreSQL, with the real simulator jar as a child process, seeded the way
 * {@code reset.sh} seeds the public site. The Vite dev server proxies {@code /api} to port 8080.
 *
 * <p>{@code LEDGERLAB_E2E_SCENARIO=demo-broken-lock} additionally switches on the AC-9.2 refund-lock fault. A
 * loopback control server on 8090 answers {@code /ready} once seeding is done and, for the secrecy assertion,
 * hands the internal token to the test; none of this exists outside this class.
 */
public final class DemoE2eApplication {
    private DemoE2eApplication() {}

    public static void main(String[] args) throws Exception {
        if (!DemoStack.simulatorAvailable()) {
            throw new IllegalStateException(
                    "Build the simulator first: ./mvnw -B -f simulator/pom.xml -DskipTests package");
        }
        boolean broken = "demo-broken-lock".equals(System.getenv("LEDGERLAB_E2E_SCENARIO"));
        Path archive = Path.of("target/demo-e2e-archive");
        Files.createDirectories(archive);
        Files.writeString(archive.resolve("last-reset.txt"), "2026-11-05T04:00:00Z\n", StandardCharsets.UTF_8);
        Files.writeString(archive.resolve("2026-11-04.json"), """
                {"day":"2026-11-04","counters":{"payments":9,"entries":31,"refunds":2,"batches":2,
                "duplicatesAbsorbed":4,"parkedThenResolved":1,"overRefundsRejected":2,"unknownRecovered":1,
                "workerCrashesRecovered":1,"discrepanciesOpened":2},
                "actionsToday":{"pay":5,"double-refund":2},"pageViews":{"today":14,"total":14},
                "invariants":{"checkedAt":"2026-11-05T03:59:58Z","ok":true,"checks":[],"failed":[]},
                "archivedAt":"2026-11-05T04:00:00Z"}
                """, StandardCharsets.UTF_8);

        String[] extra = broken
                ? new String[] {
                    "--ledgerlab.demo.archive-dir=" + archive.toAbsolutePath(),
                    "--ledgerlab.test.break-refund-lock=true"
                }
                : new String[] {"--ledgerlab.demo.archive-dir=" + archive.toAbsolutePath()};
        var stack = new DemoStack(true, true, 8080, extra);
        Runtime.getRuntime().addShutdownHook(new Thread(stack::close));
        var seeded = stack.post("/api/demo/seed");
        if (seeded.status() != 200) throw new IllegalStateException("Seeding failed: " + seeded.body());
        String token = stack.context.getBean(DemoInternalToken.class).value();

        var control = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8090), 0);
        control.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String body = path.equals("/ready") ? "ok" : path.equals("/internal-token") ? token : null;
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException failure) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        control.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> control.stop(0)));
    }
}
