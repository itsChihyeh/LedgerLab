package dev.ledgerlab.ingestion.internal;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerlab.stripe")
record StripeReaderProperties(
        @DefaultValue("https://api.stripe.com") URI apiBaseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout) {
    StripeReaderProperties {
        if (!("http".equals(apiBaseUrl.getScheme()) || "https".equals(apiBaseUrl.getScheme()))
                || apiBaseUrl.getHost() == null
                || apiBaseUrl.getUserInfo() != null
                || apiBaseUrl.getQuery() != null
                || apiBaseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "ledgerlab.stripe.api-base-url must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (connectTimeout.isZero()
                || connectTimeout.isNegative()
                || readTimeout.isZero()
                || readTimeout.isNegative()) {
            throw new IllegalArgumentException("ledgerlab.stripe timeouts must be positive");
        }
    }
}
