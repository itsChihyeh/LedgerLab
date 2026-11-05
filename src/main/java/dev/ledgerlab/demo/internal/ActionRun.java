package dev.ledgerlab.demo.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** One running scenario: the steps it has taken so far, each also pushed to the live feed as it happens. */
final class ActionRun {
    record Step(int n, String label, Instant at) {}

    final String actionId;
    final String action;
    private final Clock clock;
    private final BiConsumer<ActionRun, Step> sink;
    private final List<Step> steps = new ArrayList<>();

    ActionRun(String actionId, String action, Clock clock, BiConsumer<ActionRun, Step> sink) {
        this.actionId = actionId;
        this.action = action;
        this.clock = clock;
        this.sink = sink;
    }

    void step(String label) {
        Step step = new Step(steps.size() + 1, label, clock.instant());
        steps.add(step);
        sink.accept(this, step);
    }

    List<Step> steps() {
        return List.copyOf(steps);
    }
}
