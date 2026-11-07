package dev.ledgerlab.testsupport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Collects the application's log events in memory so a test can read the MDC and the key/value pairs of a line
 * without parsing console text, and can wait for a line another thread is about to write without sleeping.
 * Attached to the root logger, so it sees what the console appender sees (it runs right after it on the same thread).
 */
public final class LogEvents extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private final List<ILoggingEvent> events = new ArrayList<>();

    private LogEvents() {}

    public static LogEvents attach() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        LogEvents appender = new LogEvents();
        appender.setContext(root.getLoggerContext());
        appender.setName("test-log-events");
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    @Override
    protected void append(ILoggingEvent event) {
        // Logback reads the MDC lazily from the current thread; touch it here so the event keeps this thread's values.
        event.getMDCPropertyMap();
        synchronized (events) {
            events.add(event);
            events.notifyAll();
        }
    }

    /** Lines whose {@code event.action} key/value equals {@code action}, in the order they were written. */
    public List<ILoggingEvent> action(String action) {
        return matching(event -> action.equals(keyValues(event).get("event.action")));
    }

    public List<ILoggingEvent> matching(Predicate<ILoggingEvent> test) {
        synchronized (events) {
            return events.stream().filter(test).toList();
        }
    }

    /** Waits until a line matches, or fails after {@code timeout}; no polling interval, it wakes on each new line. */
    public ILoggingEvent await(Predicate<ILoggingEvent> test, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (events) {
            while (true) {
                for (ILoggingEvent event : events) {
                    if (test.test(event)) return event;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new AssertionError("No log line matched within " + timeout);
                events.wait(Math.max(1, left / 1_000_000));
            }
        }
    }

    public static Map<String, Object> keyValues(ILoggingEvent event) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) pairs.forEach(pair -> result.put(pair.key, pair.value));
        return result;
    }

    /** Message, key/values and MDC of every collected line, as the text a reader of the log could have seen. */
    public String allText() {
        StringBuilder text = new StringBuilder();
        synchronized (events) {
            for (ILoggingEvent event : events) {
                text.append(event.getFormattedMessage())
                        .append(' ')
                        .append(keyValues(event))
                        .append(' ')
                        .append(event.getMDCPropertyMap())
                        .append('\n');
            }
        }
        return text.toString();
    }

    @Override
    public void close() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAppender(this);
        stop();
    }
}
