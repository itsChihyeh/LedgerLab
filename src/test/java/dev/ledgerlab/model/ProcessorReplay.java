package dev.ledgerlab.model;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local processor HTTP evidence, including a successful first page followed by a 503. */
final class ProcessorReplay implements AutoCloseable {
    record Pages(String first, String last, boolean interrupted) {}

    private final Map<String, Pages> responses = new ConcurrentHashMap<>();
    private final HttpServer server;

    ProcessorReplay() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/balance_transactions", exchange -> {
                Map<String, String> query = new java.util.HashMap<>();
                for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
                Pages pages = responses.get(query.get("payout"));
                boolean next = query.containsKey("starting_after");
                int status = pages == null ? 404 : next && pages.interrupted() ? 503 : 200;
                String body = pages == null ? "{}" : next ? pages.last() : pages.first();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void install(String payout, Pages pages) {
        responses.put(payout, pages);
    }

    void clear() {
        responses.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
