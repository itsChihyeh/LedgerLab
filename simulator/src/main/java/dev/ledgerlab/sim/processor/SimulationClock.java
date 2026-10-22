package dev.ledgerlab.sim.processor;

import java.time.Duration;
import java.time.Instant;

/** A frozen, controllable scenario clock, independent from webhook signing time. */
public final class SimulationClock {
    private Instant now;

    public SimulationClock(Instant start) {
        now = start;
    }

    public synchronized Instant instant() {
        return now;
    }

    public synchronized void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Cannot move simulation time backwards");
        now = now.plus(duration);
    }
}
