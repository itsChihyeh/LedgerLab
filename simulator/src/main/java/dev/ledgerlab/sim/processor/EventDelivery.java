package dev.ledgerlab.sim.processor;

/** Delivery boundary: HTTP in the live simulator, original-byte capture in scenario exports. */
@FunctionalInterface
public interface EventDelivery {
    void deliver(String eventId, String payload);
}
