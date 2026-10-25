package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import dev.ledgerlab.testsupport.StripeSignatures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Loopback-only Stripe protocol fixture; stalled replies use a latch, never timing sleeps. */
public final class RefundHttpServer implements AutoCloseable {
    public static final String KEY = "sk_test_" + "refund_synthetic_only";
    private final HttpServer server;
    private final HttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch release = new CountDownLatch(1);
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public volatile HttpHandler handler = exchange -> respond(exchange, 200, "{\"data\":[],\"has_more\":false}");

    public RefundHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    URLDecoder.decode(
                            java.util.Objects.toString(exchange.getRequestURI().getRawQuery(), ""),
                            StandardCharsets.UTF_8),
                    URLDecoder.decode(
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Stripe-Version"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            handler.handle(exchange);
        });
        server.start();
        client = new StripeReaderConfiguration()
                .stripeReadHttpClient(new StripeReaderProperties(
                        java.net.URI.create(url()), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public ProcessorRefundGateway gateway(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock,
            IngestionIssues issues) {
        return gateway(jdbc, manager, mapper, clock, issues, KEY);
    }

    public ProcessorRefundGateway gateway(
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock,
            IngestionIssues issues,
            String key) {
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(1));
        var rest = RestClient.builder()
                .baseUrl(url())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isFalse();
                    return execution.execute(request, body);
                })
                .build();
        return new StripeRefundAdapter(
                rest, new StripeProperties(StripeSignatures.SECRET, key), jdbc, manager, mapper, clock, issues);
    }

    public void stall(HttpExchange exchange) {
        await(release);
        exchange.close();
    }

    public long posts() {
        return requests.stream().filter(r -> r.method().equals("POST")).count();
    }

    public static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    public static String refund(String id, String charge, String refundId, long amount, String status) {
        return new tools.jackson.databind.json.JsonMapper()
                .writeValueAsString(Map.of(
                        "id",
                        id,
                        "charge",
                        charge,
                        "amount",
                        amount,
                        "status",
                        status,
                        "created",
                        1,
                        "metadata",
                        Map.of("ledgerlab_refund_id", refundId)));
    }

    public static String page(List<String> items, boolean more) {
        return "{\"data\":[" + String.join(",", items) + "],\"has_more\":" + more + "}";
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.close();
        client.close();
    }

    public record Request(
            String method,
            String path,
            String query,
            String form,
            String key,
            String authorization,
            String version,
            String contentType) {}
}
