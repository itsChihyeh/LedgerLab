package dev.ledgerlab.outbound;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public sealed interface CommandResult {
    String code();

    Instant observedAt();

    interface Receipt {}

    record Done(Receipt receipt, @Nullable String externalRef, String code, Instant observedAt)
            implements CommandResult {}

    record Unknown(String code, Instant observedAt) implements CommandResult {}
}
