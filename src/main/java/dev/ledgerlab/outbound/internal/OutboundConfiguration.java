package dev.ledgerlab.outbound.internal;

import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboundProperties.class)
class OutboundConfiguration {
    @Bean
    OutboundHooks outboundHooks() {
        return new OutboundHooks() {};
    }

    @Bean
    RandomGenerator outboundRandom() {
        return new Random();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "ledgerlab.outbound.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class Polling {
        private final OutboundWorker worker;

        Polling(OutboundWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${ledgerlab.outbound.poll-interval:1s}")
        void poll() {
            worker.processAvailable();
        }
    }
}
