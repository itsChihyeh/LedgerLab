package dev.ledgerlab.refunds.internal;

import org.springframework.context.ApplicationContext;

/** Access to the existing package-local cleanup entrypoint, without scheduling or SQL fixtures. */
public final class ModelIdempotency {
    private ModelIdempotency() {}

    public static void expire(ApplicationContext context) {
        var cleanup = context.getBean(IdempotencyCleanup.class);
        for (int i = 0; i < 100; i++) if (cleanup.cleanBatch() == 0) return;
        throw new AssertionError("Idempotency cleanup did not drain");
    }
}
