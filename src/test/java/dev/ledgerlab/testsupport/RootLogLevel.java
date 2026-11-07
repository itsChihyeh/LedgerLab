package dev.ledgerlab.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the root log level back after a test that started an application with {@code --logging.level.root=WARN}.
 * Logback is one per JVM, so a level left behind silences the INFO lines that later tests collect with
 * {@link LogEvents}.
 */
public final class RootLogLevel {
    private final Level before = root().getLevel();

    private RootLogLevel() {}

    /** Remember the level now; call before starting the application. */
    public static RootLogLevel remember() {
        return new RootLogLevel();
    }

    public void restore() {
        root().setLevel(before);
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
