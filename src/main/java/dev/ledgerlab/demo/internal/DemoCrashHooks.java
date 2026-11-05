package dev.ledgerlab.demo.internal;

import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The controlled crash behind the {@code kill-worker} action. When armed, the next command that has already
 * been sent to the processor dies just before its result is written back, as if the worker process had been
 * killed at the worst moment. The lease then expires and the command is taken over and finished without a
 * second execution. Present only under the {@code demo} profile; there is no HTTP entry to it.
 */
@Component
@Primary
@Profile("demo")
final class DemoCrashHooks implements OutboundHooks {
    /** What the simulated crash throws; the worker's scheduler logs it and carries on with the next poll. */
    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) {
            super(message);
        }
    }

    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicReference<@Nullable String> crashed = new AtomicReference<>();

    void arm() {
        crashed.set(null);
        armed.set(true);
    }

    void disarm() {
        armed.set(false);
    }

    /** The subject (refund) of the command that crashed, once it has. */
    @Nullable
    String crashedSubject() {
        return crashed.get();
    }

    @Override
    public void beforeWriteback(CommandSnapshot command) {
        if (armed.compareAndSet(true, false)) {
            crashed.set(command.subjectId());
            throw new SimulatedCrash("Simulated worker crash before write-back of " + command.id());
        }
    }
}
