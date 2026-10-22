package dev.ledgerlab.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ModelReport {
    final Map<String, Long> actions = new LinkedHashMap<>();
    long sequences;
    long passed;
    long steps;
    long maxLength;
    long violations;
    long checks;
    long cleanupFailures;
    final long started = System.nanoTime();
    String lastFailure = "none";

    ModelReport() {
        for (String name : new String[] {
            "NEW_CHARGE",
            "SAME_ID_SAME_BODY",
            "SAME_ID_CHANGED_AMOUNT",
            "NEW_ID_SAME_FACT",
            "NEW_ID_CHANGED_AMOUNT",
            "UNSUBSCRIBED",
            "DISPUTE",
            "DRAIN",
            "CRASH_AFTER_CLAIM",
            "CRASH_AFTER_BUSINESS",
            "ADVANCE_CLOCK"
        }) {
            actions.put(name, 0L);
        }
    }

    void action(String name) {
        actions.merge(name, 1L, Long::sum);
    }

    void write(String seed) throws IOException {
        StringBuilder text = new StringBuilder("# P1 model test report (AC-4.11)\n\n");
        text.append("- Seed: ")
                .append(seed)
                .append("\n- Sequence executions: ")
                .append(sequences)
                .append("\n- Passed executions: ")
                .append(passed)
                .append("\n- Mean generated length: ")
                .append(String.format(Locale.ROOT, "%.2f", sequences == 0 ? 0.0 : (double) steps / sequences))
                .append("\n- Maximum generated length: ")
                .append(maxLength)
                .append("\n- Configured length range: 1..24 (plus final drain)")
                .append("\n- Invariant/model check failures: ")
                .append(violations)
                .append("\n- Checkpoints: ")
                .append(checks)
                .append("\n- Cleanup failures: ")
                .append(cleanupFailures)
                .append("\n- Elapsed seconds (including application startup): ")
                .append(String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000_000.0))
                .append(
                        "\n\nCounts include executed shrinking/replay attempts if a property fails; they are not unique generated samples.\n")
                .append(
                        "Crash points: afterClaim (before business transaction), afterBusiness (before epoch writeback); lease 30s, recovery after 31s using a different worker.\n\n")
                .append("| Action | Executions |\n|---|---:|\n");
        actions.forEach((name, count) ->
                text.append('|').append(name).append('|').append(count).append("|\n"));
        text.append(
                        "\nChecks: global LedgerAudit INV-1..5/8; all account balance deltas; per-event outcomes; payment amounts/status; issue categories; conflict copies; INV-9 and INV-10 P1 portions.\n")
                .append("\nINV-6、7、11：不适用，P2/P3/P5 扩展时加入。\n")
                .append(
                        "\nThis only covers generated actions understood by the independent model. It is not a concurrency test; controlled interleavings/barriers remain separate. AC-4.13 empty-database reconstruction is not covered.\n")
                .append(
                        "\nLast failing execution (jqwik console/Surefire output has the minimized chain):\n\n```text\n")
                .append(lastFailure)
                .append("\n```\n");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/model-report.md"), text);
    }
}
