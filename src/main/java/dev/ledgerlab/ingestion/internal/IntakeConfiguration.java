package dev.ledgerlab.ingestion.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StripeProperties.class)
class IntakeConfiguration {
    @Bean
    IntakeCommitted intakeCommitted() {
        return () -> {};
    }
}
