package dev.ledgerlab.sim.processor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.PriorityQueue;

/** A frozen, controllable scenario clock, independent from webhook signing time. */
public final class SimulationClock extends Clock {
    private Instant now;
    private long sequence;
    private final PriorityQueue<Scheduled> scheduled =
            new PriorityQueue<>(Comparator.comparing(Scheduled::at).thenComparingLong(Scheduled::sequence));

    public SimulationClock(Instant start) {
        now = start;
    }

    public synchronized Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        java.util.Objects.requireNonNull(zone);
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return SimulationClock.this.withZone(other);
            }

            @Override
            public Instant instant() {
                return SimulationClock.this.instant();
            }
        };
    }

    public void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Cannot move simulation time backwards");
        synchronized (this) {
            now = now.plus(duration);
        }
        runDue();
    }

    public void schedule(Duration delay, Runnable task) {
        if (delay.isNegative()) throw new IllegalArgumentException("Negative delay");
        synchronized (this) {
            scheduled.add(new Scheduled(now.plus(delay), ++sequence, task));
        }
        runDue();
    }

    private void runDue() {
        while (true) {
            Scheduled next;
            synchronized (this) {
                if (scheduled.isEmpty() || scheduled.element().at().isAfter(now)) return;
                next = scheduled.remove();
            }
            // Callbacks may perform HTTP and query the clock; never hold the clock monitor here.
            next.task().run();
        }
    }

    private record Scheduled(Instant at, long sequence, Runnable task) {}
}
