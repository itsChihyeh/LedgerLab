package dev.ledgerlab.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only place that reads the system clock (contracts 1.2 rule 4). Tests replace these beans. */
@Configuration(proxyBeanMethods = false)
class TimeAndIdConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator(Clock clock) {
        return IdGenerator.secure(clock);
    }
}
