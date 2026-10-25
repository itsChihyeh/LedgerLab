package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenarioRebuildTest {
    @Test
    void ac_4_13_failedShrinkCleanupCanCloseApplicationAgain() {
        var app = new ModelApplication();
        app.close();
        app.close();
    }

    @Test
    void ac_4_13_refundHistoryCommandAndIdempotencyDifferencesAreReported() {
        var original = new RebuildSnapshot(
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("ch_test:R1", Map.of("status", "SUCCEEDED")),
                Map.of("ch_test:R1:1", Map.of("to_status", "READY")),
                Map.of("ch_test:R1", Map.of("status", "DONE")),
                Map.of("client:key", Map.of("response_status", 201)));
        var empty = new RebuildSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        assertThatThrownBy(() -> original.assertMatches(empty))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("refunds[ch_test:R1]")
                .hasMessageContaining("refundHistory[ch_test:R1:1]")
                .hasMessageContaining("commands[ch_test:R1]")
                .hasMessageContaining("idempotency[client:key]");
    }

    @Test
    void ac_4_13_lastDeliveryIsDrainedOnBothDatabases() {
        try (ModelApplication original = new ModelApplication();
                ModelApplication replay = new ModelApplication()) {
            var input = new PaymentModel.Input(
                    "evt_rebuild", "ch_rebuild", "charge.succeeded", "1234", "1234", "cad", true, false, false);
            original.deliver(input);
            original.advance(1);
            original.deliver(input); // Same raw bytes, a distinct signature: both deliveries must survive.
            original.deliver(input.withAmount("50")); // Retain conflicting input, not only inbox rows.
            original.deliver(new PaymentModel.Input(
                    "evt_rebuild_tail",
                    "ch_rebuild_tail",
                    "charge.succeeded",
                    "777",
                    "777",
                    "cad",
                    true,
                    false,
                    false));
            assertThat(original.log.inputs()).hasSize(5);
            var first = (ScenarioLog.Webhook) original.log.inputs().get(0);
            var duplicate = (ScenarioLog.Webhook) original.log.inputs().get(2);
            assertThat(first.body()).isEqualTo(duplicate.body());
            assertThat(first.signature()).isNotEqualTo(duplicate.signature());
            byte[] copy = first.body();
            copy[0] = 0;
            assertThat(first.body()[0]).isEqualTo((byte) '{');

            // The generated sequence ends in delivery, with a real RECEIVED event left behind.
            assertThat(RebuildSnapshot.read(original).inbox().get("evt_rebuild").status())
                    .isEqualTo("RECEIVED");
            original.drain("final-worker");
            original.assertNoPending();
            original.log.replay(replay);
            RebuildSnapshot.read(original).assertMatches(RebuildSnapshot.read(replay));
            assertThat(replay.balances().get(1101)).isEqualTo(2011L);
        }
    }

    @Test
    void ac_4_13_diagnosticsRetainAllDifferencesAndPostingMultiplicity() {
        var posting = new RebuildSnapshot.Posting(1101, "D", 50);
        var original = new RebuildSnapshot(
                Map.of(1101, 50L),
                Map.of("ch_test", new RebuildSnapshot.Payment("CAPTURED", 50)),
                Map.of("evt_test", new RebuildSnapshot.Outcome("PROCESSED", "APPLIED")),
                Map.of(
                        "payment:ch_test:captured",
                        List.of(new RebuildSnapshot.Entry("PAYMENT_CAPTURED", Map.of(posting, 2L)))),
                Set.of(new RebuildSnapshot.Issue("EVENT_CONTENT_CONFLICT", "event", "evt_test")),
                Map.of("po_test", Map.of("status", "BANKED", "net", 50L)),
                Map.of("txn_test", Map.of("batch_id", "po_test", "fee", 0L)),
                Map.of("bank_test:1", Map.of("match_status", "MATCHED", "amount", 50L)));
        var replay = new RebuildSnapshot(
                Map.of(1101, 51L),
                Map.of("ch_test", new RebuildSnapshot.Payment("VOIDED", 51)),
                Map.of("evt_test", new RebuildSnapshot.Outcome("RECEIVED", null)),
                Map.of(
                        "payment:ch_test:captured",
                        List.of(new RebuildSnapshot.Entry("PAYMENT_CAPTURED", Map.of(posting, 1L)))),
                Set.of());
        assertThatThrownBy(() -> original.assertMatches(replay))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("balances[1101]: original=50, replay=51")
                .hasMessageContaining("payments[ch_test]")
                .hasMessageContaining("inbox[evt_test]")
                .hasMessageContaining("entries[payment:ch_test:captured]")
                .hasMessageContaining("amount=50]=2")
                .hasMessageContaining("amount=50]=1")
                .hasMessageContaining("batches[po_test]")
                .hasMessageContaining("batchLines[txn_test]")
                .hasMessageContaining("bankLines[bank_test:1]")
                .hasMessageContaining("issues: original-only=[Issue[category=EVENT_CONTENT_CONFLICT");
    }

    @Test
    void ac_4_13_bankInputLogOwnsTheOriginalBytes() {
        byte[] original = "synthetic bank input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file = new ScenarioLog.BankFile(java.time.Instant.parse("2026-11-10T14:00:00Z"), original);
        original[0] = 0;
        byte[] read = file.raw();
        read[0] = 0;
        assertThat(file.raw()).isEqualTo("synthetic bank input".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(file.toString()).contains("rawBase64=");
    }
}
