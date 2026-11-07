package dev.ledgerlab.ops;

import dev.ledgerlab.testsupport.ContainerMounts;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs {@code amtool} from the pinned {@code prom/alertmanager} image, once per call. The image is only used in tests
 * and in the optional {@code observability} profile of {@code compose.demo.yaml} (same tag).
 */
final class Amtool {
    static final String IMAGE = "prom/alertmanager:v0.28.1";
    /** The real configuration directory, mounted read-only. */
    static final Path OPS_DIR = Path.of("ops", "alertmanager").toAbsolutePath();

    static final String REAL_CONFIG = "/config/alertmanager.yml";
    private static final String CONFIG = "/config";
    private static final String GENERATED = "/generated";
    private static final String EXIT_MARK = "AMTOOL_EXIT=";

    record Result(long exitCode, String output) {}

    private Amtool() {}

    /** {@code amtool <arguments>} with the real configuration directory mounted at {@code /config}. */
    static Result run(String... arguments) {
        return run(null, arguments);
    }

    /** Same, with a directory of generated files mounted at {@code /generated}. */
    static Result run(Path generatedDir, String... arguments) {
        // The shell prints amtool's exit status: a one-shot container that exits non-zero is torn down by
        // Testcontainers before its exit code can be read (same reason as in Promtool).
        String command = "amtool " + String.join(" ", arguments) + "; echo " + EXIT_MARK + "$?";
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))) {
            container
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-c", command))
                    .withFileSystemBind(OPS_DIR.toString(), CONFIG, BindMode.READ_ONLY)
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)));
            if (generatedDir != null)
                container.withFileSystemBind(
                        ContainerMounts.readable(generatedDir).toAbsolutePath().toString(),
                        GENERATED,
                        BindMode.READ_ONLY);
            container.start();
            String output = container.getLogs();
            Matcher exit = Pattern.compile(EXIT_MARK + "(\\d+)").matcher(output);
            if (!exit.find()) throw new IllegalStateException("amtool printed no exit status: " + output);
            return new Result(Long.parseLong(exit.group(1)), output);
        }
    }

    static String generated(String name) {
        return GENERATED + "/" + name;
    }
}
