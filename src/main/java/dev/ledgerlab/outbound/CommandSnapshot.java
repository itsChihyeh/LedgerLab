package dev.ledgerlab.outbound;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record CommandSnapshot(
        String id,
        String kind,
        String subjectId,
        String requestId,
        @Nullable Instant firstSentAt,
        @Nullable String externalRef,
        int attempts,
        long leaseEpoch,
        boolean retry) {}
