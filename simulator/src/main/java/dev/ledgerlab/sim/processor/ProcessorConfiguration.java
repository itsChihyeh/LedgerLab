package dev.ledgerlab.sim.processor;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorConfiguration {
    @Bean
    SimulationClock simulationClock(@Value("${sim.clock.start:}") String start) {
        return new SimulationClock(start.isBlank() ? Instant.now() : Instant.parse(start));
    }

    @Bean
    SeededIds seededIds(@Value("${sim.seed}") long seed) {
        return new SeededIds(seed);
    }

    @Bean
    Clock signingClock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient webhookClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
}
