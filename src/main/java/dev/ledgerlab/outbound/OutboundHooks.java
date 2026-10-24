package dev.ledgerlab.outbound;

/** No-op in production; test controls can pause or crash at delivery boundaries. */
public interface OutboundHooks {
    default void afterClaim(CommandSnapshot command) {}

    default void beforeHttp(CommandSnapshot command) {}

    default void beforeWriteback(CommandSnapshot command) {}
}
