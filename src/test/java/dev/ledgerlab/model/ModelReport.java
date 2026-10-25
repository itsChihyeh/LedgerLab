package dev.ledgerlab.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ModelReport {
    final Map<String, Long> actions = new LinkedHashMap<>();
    final Map<String, Long> features = new LinkedHashMap<>();
    long sequences;
    long passed;
    long steps;
    long maxLength;
    long violations;
    long checks;
    long cleanupFailures;
    long rebuilds;
    long rebuildMatches;
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
        for (P2Scenario.Kind kind : P2Scenario.Kind.values()) actions.put(kind.name(), 0L);
        for (P3Scenario.Kind kind : P3Scenario.Kind.values()) actions.put(kind.name(), 0L);
        for (P4Scenario.Kind kind : P4Scenario.Kind.values()) actions.put(kind.name(), 0L);
        for (int i = 0; i <= 7; i++) features.put("COMMAND_RESULT_" + i, 0L);
        for (String name : new String[] {
            "QUERY_INCOMPLETE",
            "WEBHOOK_BEFORE_RESPONSE",
            "REFUND_ZERO_REMAINING",
            "REFUND_EXCESS",
            "ACCEPTED_READY",
            "ACCEPTED_AWAITING_FUNDS"
        }) features.put(name, 0L);
        for (String name : new String[] {
            "MULTI_PAYMENT_BATCH",
            "ACTUAL_FEE_DIFFERS",
            "ZERO_FEE_BATCH",
            "READY_BEFORE_CREATED",
            "BANK_WAITING",
            "BANK_MATCHED",
            "BANK_UNAPPLIED",
            "BANK_IGNORED_PENDING"
        }) features.put(name, 0L);
    }

    void action(String name) {
        actions.merge(name, 1L, Long::sum);
    }

    void write(String seed) throws IOException {
        StringBuilder text = new StringBuilder("# P1/P2/P3/P4 model test report (AC-6.13, AC-4.11, AC-4.13)\n\n");
        text.append("- Seed: ")
                .append(seed)
                .append("\n- Sequence executions: ")
                .append(sequences)
                .append("\n- Passed executions: ")
                .append(passed)
                .append("\n- Generated actions: ")
                .append(steps)
                .append("\n- Mean generated length: ")
                .append(String.format(Locale.ROOT, "%.2f", sequences == 0 ? 0.0 : (double) steps / sequences))
                .append("\n- Maximum generated length: ")
                .append(maxLength)
                .append("\n- Configured length range: 1..24 (plus final drain and WAITING expiry)")
                .append("\n- Invariant/model check failures: ")
                .append(violations)
                .append("\n- Checkpoints: ")
                .append(checks)
                .append("\n- Cleanup failures: ")
                .append(cleanupFailures)
                .append("\n- Rebuild attempts (every tenth sequence): ")
                .append(rebuilds)
                .append("\n- Matching rebuilds: ")
                .append(rebuildMatches)
                .append("\n- Rebuild failures: ")
                .append(rebuilds - rebuildMatches)
                .append("\n- IdGenerator seed: ")
                .append(ModelApplication.ID_SEED)
                .append("\n- Elapsed seconds (including application startup): ")
                .append(String.format(Locale.ROOT, "%.3f", (System.nanoTime() - started) / 1_000_000_000.0))
                .append(
                        "\n\nCounts include executed shrinking/replay attempts if a property fails; they are not unique generated samples.\n")
                .append(
                        "Crash points: afterClaim (before business transaction), afterBusiness (before epoch writeback); lease 30s, recovery after 31s using a different worker.\n\n")
                .append("| Action | Executions |\n|---|---:|\n");
        actions.forEach((name, count) ->
                text.append('|').append(name).append('|').append(count).append("|\n"));
        text.append("\n| P2/P3/P4 feature | Occurrences |\n|---|---:|\n");
        features.forEach((name, count) ->
                text.append('|').append(name).append('|').append(count).append("|\n"));
        text.append(
                        "\nP4 preconditions: VOID active unsettled capture with zero reserved refunds; RESTATE active unsettled capture, new amount 50..99999999, different and >= reserved; APPLY confirmed batch plus exact same-reference unapplied credits; RESTORE failed batch plus exact unapplied credit and no failure return; RETURN exact currently unapplied credit; RECHECK held batch. PREPARE supplies held, split, failed, expired-early or unassigned inputs. INVALID has weight 2 against 35 valid P4 weights; 4xx compares complete business/audit rows. Bank nonmatches are accepted input plus an unmatched issue, per ADR-0011.\n")
                .append(
                        "\nP4 checks: INV-6 independent available balance, INV-9 current captured amount and empty P5 obligations, INV-12 current unapplied credits; correction/target identities, roles and counts, exact posting multisets, payment current fee/remaining refund amount, issue status/resolution.\n");
        text.append(
                        "\nChecks: global LedgerAudit INV-1..5/8; all account balance deltas; per-event outcomes; payment amounts/status/fees/batches; batch totals/states; P2 posting multisets; all bank versions/matches/deadlines; issue identities; conflict copies; whole-database INV-9 (2101/1102) and INV-11 (unique BOOKED credits/debits, WAITING and explained unmatched debits); INV-10 captured source keys.\n")
                .append(
                        "\nP3: INV-7 refund limits and reserved cache; INV-9 2103; INV-10 unique refund effects; AC-3.14 no refund cash posting. P5 withdrawals remain outside this generator; bank inputs are CAD CREDIT and DEBIT returns.\n")
                .append(
                        "\nThis only covers generated actions understood by the independent model. It is not a concurrency test; controlled interleavings/barriers remain separate. AC-4.13 replays raw signed webhooks, local processor REST pages, bank files, scans, API requests/results, outbound receipts/timeouts and clock changes; compares all balances, payments, batches, batch lines, bank versions, inbox outcomes, source-key entry/posting multisets, issue identities/statuses, refunds/history, command outcomes, idempotency results, corrections, targets, evidence and business/issue histories on separate freshly migrated databases.\n")
                .append(
                        "\nLast failing execution (jqwik console/Surefire output has the minimized chain):\n\n```text\n")
                .append(lastFailure)
                .append("\n```\n");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/model-report.md"), text);
    }
}
