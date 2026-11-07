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
 * Runs {@code promtool} from the pinned {@code prom/prometheus} image, once per call. The image is only used in tests;
 * it is the same tag as the optional {@code observability} profile of {@code compose.demo.yaml}.
 */
final class Promtool {
    static final String IMAGE = "prom/prometheus:v3.5.0";
    /** The real rule files, mounted read-only. */
    static final Path OPS_DIR = Path.of("ops", "prometheus").toAbsolutePath();

    private static final String RULES = "/rules";
    private static final String GENERATED = "/generated";
    private static final String EXIT_MARK = "PROMTOOL_EXIT=";

    record Result(long exitCode, String output) {}

    private Promtool() {}

    /** {@code promtool check rules alerts.yml} against the real rule file. */
    static Result checkRules() {
        return run(null, "check", "rules", RULES + "/alerts.yml");
    }

    /** {@code promtool test rules} on a test file that lives in the real directory. */
    static Result testRules(String testFile) {
        return run(null, "test", "rules", RULES + "/" + testFile);
    }

    /**
     * {@code promtool test rules} on a generated test file. The directory with the generated files is mounted next to
     * the real one; the generated file points at the real {@code alerts.yml} by absolute path, so no copy of the rule
     * exists.
     */
    static Result testRulesGenerated(Path generatedDir, String testFile) {
        return run(generatedDir, "test", "rules", GENERATED + "/" + testFile);
    }

    /** The {@code rule_files} line a generated test file uses to load the real rule. */
    static String realRuleFile() {
        return RULES + "/alerts.yml";
    }

    private static Result run(Path generatedDir, String... arguments) {
        // The shell prints promtool's exit status: a one-shot container that exits non-zero is torn down by
        // Testcontainers before its exit code can be read.
        String command = "/bin/promtool " + String.join(" ", arguments) + "; echo " + EXIT_MARK + "$?";
        try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))) {
            container
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-c", command))
                    .withFileSystemBind(OPS_DIR.toString(), RULES, BindMode.READ_ONLY)
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)));
            if (generatedDir != null)
                container.withFileSystemBind(
                        ContainerMounts.readable(generatedDir).toAbsolutePath().toString(),
                        GENERATED,
                        BindMode.READ_ONLY);
            container.start();
            String output = container.getLogs();
            Matcher exit = Pattern.compile(EXIT_MARK + "(\\d+)").matcher(output);
            if (!exit.find()) throw new IllegalStateException("promtool printed no exit status: " + output);
            return new Result(Long.parseLong(exit.group(1)), output);
        }
    }
}
