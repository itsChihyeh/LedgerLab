package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.payouts.BankPayoutGateway;
import java.time.Clock;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class PayoutCommandConfiguration {
    @Bean
    @Nullable
    PayoutCommandHandler payoutQueryCommandHandler(
            JdbcClient jdbc, Optional<BankPayoutGateway> gateway, PayoutCommandEvidence evidence, Clock clock) {
        return gateway.map(adapter -> new PayoutCommandHandler(jdbc, adapter, evidence, clock, true))
                .orElse(null);
    }

    @Bean
    @Nullable
    PayoutCommandHandler payoutCommandHandler(
            JdbcClient jdbc, Optional<BankPayoutGateway> gateway, PayoutCommandEvidence evidence, Clock clock) {
        // As in P3, resolve after definitions exist, independently of component scan order.
        return gateway.map(adapter -> new PayoutCommandHandler(jdbc, adapter, evidence, clock))
                .orElse(null);
    }
}
