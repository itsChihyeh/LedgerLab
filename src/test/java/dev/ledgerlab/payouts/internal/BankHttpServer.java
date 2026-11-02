package dev.ledgerlab.payouts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.payouts.BankPayoutGateway;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Loopback HTTP fixture using the simulator's request, reply and permanent request-id contract. */
final class BankHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpClient client;
    final ObjectMapper mapper = new ObjectMapper();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final Map<String, String> payments = new ConcurrentHashMap<>();
    volatile HttpHandler handler = this::normal;

    BankHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.setExecutor(executor);
        server.createContext("/sim/bank/payments", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String id = exchange.getRequestMethod().equals("POST")
                    ? mapper.readTree(body).path("requestId").asString()
                    : path.substring(path.lastIndexOf('/') + 1);
            requests.add(new Request(exchange.getRequestMethod(), path, id, body));
            handler.handle(exchange);
        });
        server.start();
        client = new BankPayoutConfiguration()
                .bankPayoutHttpClient(
                        new BankPayoutProperties(URI.create(url()), Duration.ofSeconds(1), Duration.ofMillis(300)));
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    BankPayoutGateway gateway(Clock clock) {
        return gateway(clock, Duration.ofMillis(300));
    }

    BankPayoutGateway gateway(Clock clock, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return new HttpBankPayoutGateway(
                RestClient.builder()
                        .baseUrl(url())
                        .requestFactory(factory)
                        .requestInterceptor((request, body, execution) -> {
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                    .isFalse();
                            return execution.execute(request, body);
                        })
                        .build(),
                mapper,
                clock);
    }

    void normal(HttpExchange exchange) throws IOException {
        Request request = requests.getLast();
        if (request.method().equals("POST")) {
            boolean fresh = payments.putIfAbsent(request.id(), payment(request.id(), "ACCEPTED")) == null;
            respond(exchange, fresh ? 201 : 200, payments.get(request.id()));
        } else {
            String payment = payments.get(request.id());
            respond(exchange, payment == null ? 404 : 200, payment == null ? "{\"code\":\"NOT_FOUND\"}" : payment);
        }
    }

    static String payment(String id, String status) {
        return "{\"paymentId\":\"bpm_only\",\"requestId\":\"" + id + "\",\"status\":\"" + status
                + "\",\"executionDate\":\"2026-11-03\",\"reason\":"
                + (status.equals("REJECTED") || status.equals("RETURNED") ? "\"ACCOUNT_CLOSED\"" : "null") + "}";
    }

    void stall(HttpExchange exchange) {
        await(release);
        exchange.close();
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS))
                    .as("controlled interleaving reached")
                    .isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.close();
        client.close();
    }

    record Request(String method, String path, String id, String body) {}
}
