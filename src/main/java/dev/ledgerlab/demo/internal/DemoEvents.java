package dev.ledgerlab.demo.internal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * The live feed (section 2.2): {@code step}, {@code timeline}, {@code invariants} and {@code counters}. A short
 * in-memory history lets a reconnecting browser resume from {@code Last-Event-ID}; the feed holds no personal
 * data and nothing here is persisted.
 */
@Component
@Profile("demo")
final class DemoEvents {
    private static final int HISTORY = 1000;

    record Event(long id, String type, String data) {}

    /** Raised when the connection limit is reached; the controller answers 503. */
    static final class TooManyStreams extends RuntimeException {
        TooManyStreams() {
            super("Too many open streams");
        }
    }

    private final ObjectMapper mapper;
    private final int maxStreams;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger open = new AtomicInteger();
    private final Deque<Event> history = new ArrayDeque<>();
    private final Map<String, Event> latest = new ConcurrentHashMap<>();
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    DemoEvents(ObjectMapper mapper, DemoProperties properties) {
        this.mapper = mapper;
        this.maxStreams = properties.maxStreams();
    }

    void publish(String type, Object payload) {
        Event event = new Event(sequence.incrementAndGet(), type, mapper.writeValueAsString(payload));
        synchronized (history) {
            history.addLast(event);
            if (history.size() > HISTORY) history.removeFirst();
        }
        if (type.equals("invariants") || type.equals("counters")) latest.put(type, event);
        for (SseEmitter emitter : emitters) send(emitter, event);
    }

    SseEmitter subscribe(@Nullable String lastEventId) {
        if (open.incrementAndGet() > maxStreams) {
            open.decrementAndGet();
            throw new TooManyStreams();
        }
        SseEmitter emitter = new SseEmitter(0L);
        Runnable close = () -> {
            if (emitters.remove(emitter)) open.decrementAndGet();
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(failure -> close.run());
        // Replay first, then register: a live event published meanwhile can at worst be sent twice, never lost.
        List<Event> backlog = backlog(lastEventId);
        emitters.add(emitter);
        for (Event event : backlog) send(emitter, event);
        return emitter;
    }

    private List<Event> backlog(@Nullable String lastEventId) {
        Long after = parse(lastEventId);
        if (after == null) {
            List<Event> now = new ArrayList<>();
            for (String type : List.of("invariants", "counters")) {
                Event event = latest.get(type);
                if (event != null) now.add(event);
            }
            return now;
        }
        synchronized (history) {
            return history.stream().filter(event -> event.id() > after).toList();
        }
    }

    private static @Nullable Long parse(@Nullable String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return null;
        try {
            return Long.valueOf(lastEventId.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void send(SseEmitter emitter, Event event) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.id()))
                        .name(event.type())
                        .data(event.data()));
            }
        } catch (IOException | IllegalStateException gone) {
            if (emitters.remove(emitter)) open.decrementAndGet();
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void keepAlive() {
        for (SseEmitter emitter : emitters) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                }
            } catch (IOException | IllegalStateException gone) {
                if (emitters.remove(emitter)) open.decrementAndGet();
            }
        }
    }

    int openStreams() {
        return open.get();
    }
}
