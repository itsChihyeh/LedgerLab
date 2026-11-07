package dev.ledgerlab.sim.processor;

import dev.ledgerlab.sim.bank.Bank;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "sim.clock.follow-wall", havingValue = "true")
public class WallClockFollower {
    private final SimulationClock simulation;
    private final Clock wall;
    private final Bank bank;

    public WallClockFollower(SimulationClock simulation, Clock wall, Bank bank) {
        this.simulation = simulation;
        this.wall = wall;
        this.bank = bank;
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (simulation.advanceTo(wall.instant())) bank.advance();
    }
}
