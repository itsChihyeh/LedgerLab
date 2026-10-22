package dev.ledgerlab;

import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** Test-only backend for Playwright; every launch uses a fresh PostgreSQL database. */
public class TestLedgerLabApplication {
    public static void main(String[] args) {
        SpringApplication.from(LedgerLabApplication::main)
                .with(E2eConfiguration.class)
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class E2eConfiguration {
        @Bean
        DynamicPropertyRegistrar e2eProperties() {
            TestDatabase.Handle database = TestDatabase.freshBlank();
            return registry -> {
                registry.add("spring.datasource.url", database::url);
                registry.add("LEDGERLAB_APP_PASSWORD", () -> TestDatabase.APP_PASSWORD);
                registry.add("LEDGERLAB_MIGRATOR_PASSWORD", () -> TestDatabase.MIGRATOR_PASSWORD);
                registry.add("LEDGERLAB_READONLY_PASSWORD", () -> TestDatabase.READONLY_PASSWORD);
                registry.add("STRIPE_WEBHOOK_SECRET", () -> StripeSignatures.SECRET);
                registry.add("STRIPE_API_KEY", () -> "");
                registry.add("ledgerlab.inbox.poll-interval", () -> "50ms");
                registry.add("server.port", () -> "8080");
            };
        }
    }
}
