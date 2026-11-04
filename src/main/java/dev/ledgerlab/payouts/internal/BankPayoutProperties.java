package dev.ledgerlab.payouts.internal;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerlab.bank.payments")
record BankPayoutProperties(
        @DefaultValue("http://localhost:8081") URI baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {
    BankPayoutProperties {
        if (!("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException(
                    "ledgerlab.bank.payments.base-url must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (connectTimeout.isZero()
                || connectTimeout.isNegative()
                || readTimeout.isZero()
                || readTimeout.isNegative()) {
            throw new IllegalArgumentException("ledgerlab.bank.payments timeouts must be positive");
        }
    }
}
