package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenarioRebuildTest {
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
                Set.of(new RebuildSnapshot.Issue("EVENT_CONTENT_CONFLICT", "event", "evt_test")));
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
                .hasMessageContaining("issues: original-only=[Issue[category=EVENT_CONTENT_CONFLICT");
    }
}
