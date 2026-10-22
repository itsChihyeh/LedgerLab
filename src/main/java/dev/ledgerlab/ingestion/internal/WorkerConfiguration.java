package dev.ledgerlab.ingestion.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {
    @Bean
    WorkerHooks workerHooks() {
        return new WorkerHooks() {};
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ledgerlab.inbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class Polling {
        private final InboxWorker worker;

        Polling(InboxWorker worker) {
            this.worker = worker;
        }

        @Scheduled(fixedDelayString = "${ledgerlab.inbox.poll-interval:1s}")
        void poll() {
            worker.processAvailable();
        }
    }
}
