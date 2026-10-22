package dev.ledgerlab.ingestion.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerlab.inbox")
public record WorkerProperties(
        String owner,
        int batchSize,
        Duration leaseDuration,
        Duration initialBackoff,
        Duration maxBackoff,
        int maxAttempts) {
    public WorkerProperties {
        if (owner == null
                || owner.isBlank()
                || batchSize < 1
                || maxAttempts < 1
                || leaseDuration == null
                || leaseDuration.isNegative()
                || leaseDuration.isZero()
                || initialBackoff == null
                || initialBackoff.isNegative()
                || initialBackoff.isZero()
                || maxBackoff == null
                || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Invalid inbox worker configuration");
        }
    }

    Duration backoff(int attempts) {
        Duration delay = initialBackoff;
        for (int i = 1; i < attempts && delay.compareTo(maxBackoff) < 0; i++) {
            delay = delay.compareTo(maxBackoff.dividedBy(2)) > 0 ? maxBackoff : delay.multipliedBy(2);
        }
        return delay;
    }
}
