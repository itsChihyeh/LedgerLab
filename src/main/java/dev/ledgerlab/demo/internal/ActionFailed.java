package dev.ledgerlab.demo.internal;

/** A scenario step did not do what the scenario needs; the message is safe to show to a visitor. */
final class ActionFailed extends RuntimeException {
    ActionFailed(String message) {
        super(message);
    }

    ActionFailed(String message, Throwable cause) {
        super(message, cause);
    }
}
