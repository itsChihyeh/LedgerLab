package dev.ledgerlab.outbound;

import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** Every gateway operation goes through this lease check and append-only audit boundary. */
public interface CommandExecution {
    <T> T call(Map<String, Object> summary, Supplier<Response<T>> request);

    record Response<T>(
            T value,
            @Nullable Integer status,
            @Nullable String body,
            @Nullable String error) {}
}
