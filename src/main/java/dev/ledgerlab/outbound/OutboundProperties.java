package dev.ledgerlab.outbound;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledgerlab.outbound")
public record OutboundProperties(
        String owner,
        int batchSize,
        Duration leaseDuration,
        Duration base,
        Duration cap,
        int maxAttempts,
        Duration refundResendAfter) {
    public OutboundProperties {
        if (owner == null
                || owner.isBlank()
                || batchSize < 1
                || maxAttempts < 1
                || leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()
                || base == null
                || base.toMillis() < 1
                || cap == null
                || cap.compareTo(base) < 0
                || refundResendAfter == null
                || refundResendAfter.isZero()
                || refundResendAfter.isNegative()) {
            throw new IllegalArgumentException("Invalid outbound configuration");
        }
    }

    public Duration backoff(int attempts, java.util.random.RandomGenerator random) {
        long maximum = cap.toMillis();
        long delay = base.toMillis();
        for (int i = 1; i < attempts && delay < maximum; i++) {
            delay = delay > maximum / 2 ? maximum : Math.multiplyExact(delay, 2);
        }
        // Add up to 25 percent jitter, keeping the configured cap a hard upper bound.
        long room = Math.min(delay / 4, maximum - delay);
        return Duration.ofMillis(Math.addExact(delay, random.nextLong(Math.addExact(room, 1))));
    }
}
