package dev.ledgerlab.refunds.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledgerlab.api.idempotency-cleanup-enabled", havingValue = "true", matchIfMissing = true)
final class IdempotencyCleanupSchedule {
    private final IdempotencyCleanup cleanup;

    IdempotencyCleanupSchedule(IdempotencyCleanup cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${ledgerlab.api.idempotency-cleanup-interval:PT1M}")
    void clean() {
        cleanup.cleanBatch();
    }
}
