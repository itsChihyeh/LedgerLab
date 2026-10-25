package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import java.time.Clock;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class RefundCommandConfiguration {
    /** Without the T08 adapter installed, commands remain PENDING and no worker sends them. */
    @Bean
    @Nullable
    RefundCommandHandler refundCommandHandler(
            JdbcClient jdbc,
            PaymentRefunds payments,
            Optional<ProcessorRefundGateway> gateway,
            JdbcRefundEvidence evidence,
            Clock clock,
            OutboundProperties properties) {
        // Resolve after all bean definitions exist; ConditionalOnBean here would depend on scan order.
        return gateway.map(adapter -> new RefundCommandHandler(jdbc, payments, adapter, evidence, clock, properties))
                .orElse(null);
    }
}
