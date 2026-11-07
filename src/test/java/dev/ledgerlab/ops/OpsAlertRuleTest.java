package dev.ledgerlab.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AC-12.7 and AC-13.1: the alert rules are valid and does what the design says, judged by {@code promtool} itself. */
class OpsAlertRuleTest {

    @Test
    void ac_12_7_theRuleFilePassesPromtoolCheck() {
        Promtool.Result result = Promtool.checkRules();
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("4 rules found");
    }

    @Test
    void ac_12_7_theCommittedRuleTestsPass() {
        Promtool.Result result = Promtool.testRules("alerts.test.yml");
        assertThat(result.exitCode()).as(result.output()).isZero();
    }

    @Test
    void ac_12_7_aWrongExpectationMakesPromtoolFail(@TempDir Path dir) throws Exception {
        // Same input as the "fires" case, but claiming silence: promtool must say no, or the tests prove nothing.
        write(dir, "wrong.test.yml", testFile("600x6", "6m", false));
        Promtool.Result wrongSilence = Promtool.testRulesGenerated(dir, "wrong.test.yml");
        assertThat(wrongSilence.exitCode()).as(wrongSilence.output()).isNotZero();
        assertThat(wrongSilence.output()).contains("FAILED");

        // And the mirror image: a value that stays at 0 cannot fire.
        write(dir, "wrong2.test.yml", testFile("0x6", "6m", true));
        Promtool.Result wrongFiring = Promtool.testRulesGenerated(dir, "wrong2.test.yml");
        assertThat(wrongFiring.exitCode()).as(wrongFiring.output()).isNotZero();
        assertThat(wrongFiring.output()).contains("FAILED");

        // The same generator with the right expectation passes, so the failures above come from the expectation.
        write(dir, "right.test.yml", testFile("600x6", "6m", true));
        Promtool.Result right = Promtool.testRulesGenerated(dir, "right.test.yml");
        assertThat(right.exitCode()).as(right.output()).isZero();
    }

    @Test
    void ac_13_1_aWrongExpectationOnTheReconFailureRuleMakesPromtoolFail(@TempDir Path dir) throws Exception {
        // The failed counter steps 0 -> 1: claiming silence at minute 10 must be rejected by promtool.
        write(dir, "recon-wrong.test.yml", reconFailureTestFile("        exp_alerts: []\n"));
        Promtool.Result wrong = Promtool.testRulesGenerated(dir, "recon-wrong.test.yml");
        assertThat(wrong.exitCode()).as(wrong.output()).isNotZero();
        assertThat(wrong.output()).contains("FAILED");

        write(dir, "recon-right.test.yml", reconFailureTestFile("""
                        exp_alerts:
                          - exp_labels:
                              kind: BANK
                              outcome: failed
                              severity: ticket
                            exp_annotations:
                              summary: "A BANK reconciliation run failed in the last 30 minutes"
                              runbook: "docs/ops/runbook.md#recon-run-failed"
                """));
        Promtool.Result right = Promtool.testRulesGenerated(dir, "recon-right.test.yml");
        assertThat(right.exitCode()).as(right.output()).isZero();
    }

    private static String reconFailureTestFile(String expected) {
        return """
                rule_files:
                  - %s
                evaluation_interval: 1m
                tests:
                  - interval: 1m
                    input_series:
                      - series: 'ledgerlab_recon_runs_total{kind="BANK",outcome="failed"}'
                        values: '0x3 1x20'
                    alert_rule_test:
                      - eval_time: 10m
                        alertname: LedgerLabReconRunFailed
                %s""".formatted(Promtool.realRuleFile(), expected);
    }

    /** A promtool test file against the real rule, with one series and one expectation at {@code evalTime}. */
    static String testFile(String values, String evalTime, boolean expectFiring) {
        String expected = expectFiring ? """
                        exp_alerts:
                          - exp_labels:
                              severity: page
                            exp_annotations:
                              summary: "Webhook inbox has had due events waiting over 5 minutes"
                              runbook: "docs/ops/runbook.md#inbox-stuck"
                """ : "        exp_alerts: []\n";
        return """
                rule_files:
                  - %s
                evaluation_interval: 1m
                tests:
                  - interval: 1m
                    input_series:
                      - series: 'ledgerlab_inbox_oldest_due_age_seconds'
                        values: '%s'
                    alert_rule_test:
                      - eval_time: %s
                        alertname: LedgerLabInboxStuck
                %s""".formatted(Promtool.realRuleFile(), values, evalTime, expected);
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }
}
