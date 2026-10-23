package dev.ledgerlab.ingestion.internal;

/** Controlled crash points; production uses the no-op implementation. */
public interface WorkerHooks {
    default void afterClaim(String eventId) {}

    default void afterFetch(String eventId) {}

    default void afterBusiness(String eventId) {}
}
