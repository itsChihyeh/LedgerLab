package dev.ledgerlab.shared;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Puts correlation ids into the logging MDC for the length of a {@code try} block and puts the previous values back
 * on close, so nesting is safe and a reused worker thread never carries an id into its next job
 * (docs/design/observability.md section 1.2). Keys use the fixed spellings below; nothing else calls
 * {@code MDC.put} or {@code MDC.remove}.
 */
public final class LogContext implements AutoCloseable {
    public static final String REQUEST_ID = "requestId";
    public static final String EVENT_ID = "eventId";
    public static final String COMMAND_ID = "commandId";
    public static final String COMMAND_KIND = "commandKind";
    public static final String RUN_ID = "runId";
    public static final String ISSUE_ID = "issueId";
    public static final String BATCH_ID = "batchId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String REFUND_ID = "refundId";
    public static final String PAYOUT_ID = "payoutId";
    public static final String CORRECTION_ID = "correctionId";

    private record Saved(String key, @Nullable String previous) {}

    private final List<Saved> saved = new ArrayList<>(2);

    private LogContext() {}

    /** Starts a context holding {@code key=value}; a null value leaves the key untouched. */
    public static LogContext with(String key, @Nullable String value) {
        return new LogContext().and(key, value);
    }

    /** Adds one more key to this context; it is restored by the same {@link #close()}. */
    public LogContext and(String key, @Nullable String value) {
        if (value == null) return this;
        saved.add(new Saved(key, MDC.get(key)));
        MDC.put(key, value);
        return this;
    }

    @Override
    public void close() {
        for (int i = saved.size() - 1; i >= 0; i--) {
            Saved entry = saved.get(i);
            if (entry.previous() == null) MDC.remove(entry.key());
            else MDC.put(entry.key(), entry.previous());
        }
        saved.clear();
    }
}
