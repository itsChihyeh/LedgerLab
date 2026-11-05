package dev.ledgerlab.demo.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Reads {@code /api/demo/stream} like a browser would and keeps every event it receives. */
final class SseCollector implements AutoCloseable {
    record Event(String id, String type, String data) {}

    final List<Event> events = new CopyOnWriteArrayList<>();
    final int status;
    private final HttpResponse<Stream<String>> response;
    private final Thread reader;

    SseCollector(int port, @Nullable String lastEventId) {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/demo/stream"))
                    .header("Accept", "text/event-stream");
            if (lastEventId != null) request.header("Last-Event-ID", lastEventId);
            response = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(request.build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        status = response.statusCode();
        reader = new Thread(this::read, "sse-collector");
        reader.setDaemon(true);
        if (status == 200) reader.start();
    }

    private void read() {
        String id = "";
        String type = "";
        StringBuilder data = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            for (var it = lines.iterator(); it.hasNext(); ) {
                String line = it.next();
                if (line.isEmpty()) {
                    if (!type.isEmpty()) events.add(new Event(id, type, data.toString()));
                    id = "";
                    type = "";
                    data.setLength(0);
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).trim();
                } else if (line.startsWith("event:")) {
                    type = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
        } catch (RuntimeException closed) {
            // The test closed the stream.
        }
    }

    /** Waits until an event of the type satisfies the predicate; returns it or fails after the timeout. */
    Event await(String type, Predicate<String> data, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (Event event : events) {
                if (event.type().equals(type) && data.test(event.data())) return event;
            }
            DemoStack.sleep(50);
        }
        throw new AssertionError("No " + type + " event matched within " + timeout + "; saw " + events);
    }

    @Override
    public void close() {
        response.body().close();
        reader.interrupt();
    }
}
