package dev.ledgerlab.refunds.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("ledgerlab.api")
final class RefundApiProperties {
    private String clientId = "demo-merchant";
    private Duration idempotencyLockTimeout = Duration.ofSeconds(5);
    private int idempotencyCleanupBatchSize = 500;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("API client id must not be blank");
        clientId = value;
    }

    public Duration getIdempotencyLockTimeout() {
        return idempotencyLockTimeout;
    }

    public void setIdempotencyLockTimeout(Duration value) {
        if (value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("API lock timeout must be 1..2147483647 milliseconds");
        idempotencyLockTimeout = value;
    }

    public int getIdempotencyCleanupBatchSize() {
        return idempotencyCleanupBatchSize;
    }

    public void setIdempotencyCleanupBatchSize(int value) {
        if (value < 1) throw new IllegalArgumentException("API cleanup batch size must be positive");
        idempotencyCleanupBatchSize = value;
    }
}
