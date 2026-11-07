package dev.ledgerlab.shared;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One database read per Prometheus scrape, shared by the several gauges that are cut from the same query.
 *
 * <p>Micrometer has no "before scrape" hook, but a scrape reads each gauge exactly once. So a gauge that asks for a
 * value it has already read in the current snapshot knows a new scrape has begun, and the snapshot is reloaded.
 * Every gauge sharing a snapshot must therefore be read once per scrape (they all live on the same registry).
 *
 * <p>If the loader fails, every reader gets {@code NaN} and one WARN line is written; the scrape itself never
 * fails (docs/design/observability.md section 3).
 */
public final class ScrapeSnapshot<T> {
    private static final Logger LOG = LoggerFactory.getLogger(ScrapeSnapshot.class);

    private final String name;
    private final Supplier<T> loader;
    private final Set<String> readers = new HashSet<>();
    private boolean loaded;
    private @Nullable T value;

    /** @param name which group of metrics this is, for the log line; never a query parameter */
    public ScrapeSnapshot(String name, Supplier<T> loader) {
        this.name = name;
        this.loader = loader;
    }

    /** @param reader identifies the gauge; each gauge must use its own distinct string */
    public synchronized double read(String reader, ToDoubleFunction<T> extract) {
        if (!loaded || !readers.add(reader)) {
            reload();
            readers.clear();
            readers.add(reader);
        }
        T current = value;
        if (current == null) return Double.NaN;
        return extract.applyAsDouble(current);
    }

    private void reload() {
        loaded = true;
        try {
            value = loader.get();
        } catch (RuntimeException failure) {
            value = null;
            // The exception class only: messages can carry SQL text and parameters.
            LOG.atWarn()
                    .addKeyValue("event.action", "metrics.query.failed")
                    .addKeyValue("metrics", name)
                    .addKeyValue("errorClass", failure.getClass().getSimpleName())
                    .log("Metric query failed; reporting NaN");
        }
    }
}
