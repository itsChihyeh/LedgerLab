package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

/** Adds batch/paid/held and reordered new-ID facts to the existing P1 stateful coverage. */
class SettlementModelProperties {
    private SettlementWorkerTest fixture = new SettlementWorkerTest();

    @Property(tries = 100, seed = "2026102305")
    void ac_4_11_ac_2_8_batchActionsMatchIndependentReference(@ForAll("actions") ActionChain<State> actions)
            throws Exception {
        fixture = new SettlementWorkerTest();
        fixture.start();
        try {
            fixture.capture();
            actions.withInvariant("batch balances, history and exactly-once effects", State::check)
                    .run();
        } finally {
            fixture.stop();
        }
    }

    @Provide
    Arbitrary<ActionChain<State>> actions() {
        Action.Independent<State> operation = () -> Arbitraries.of(SettlementReference.Action.values())
                .map(action -> Transformer.mutate(action.name(), state -> state.apply(action)));
        return ActionChain.startWith(() -> new State(fixture))
                .withAction(operation)
                .withMaxTransformations(16);
    }

    private static final class State {
        private final SettlementWorkerTest fixture;
        private final SettlementReference reference = new SettlementReference();
        private int sequence;

        State(SettlementWorkerTest fixture) {
            this.fixture = fixture;
        }

        void apply(SettlementReference.Action action) {
            String type = switch (action) {
                case ANNOUNCE, CREATED_READY, UNSUPPORTED -> "payout.created";
                case READY -> "payout.reconciliation_completed";
                case PAID -> "payout.paid";
            };
            var payout = fixture.payout(
                    "po_b1", action == SettlementReference.Action.ANNOUNCE ? "in_progress" : "completed");
            if (action == SettlementReference.Action.UNSUPPORTED) payout.put("automatic", false);
            fixture.event("evt_model_" + ++sequence, type, payout);
            fixture.drain();
            reference.apply(action);
        }

        void check() {
            fixture.assertLedger();
            assertThat(fixture.count("core.settlement_batch")).isEqualTo(reference.status.equals("ABSENT") ? 0 : 1);
            if (!reference.status.equals("ABSENT")) assertThat(fixture.state()).isEqualTo(reference.status);
            assertThat(fixture.count("core.batch_transition")).isEqualTo(reference.history);
            assertThat(fixture.count("ledger.posting")).isEqualTo(reference.settled() ? 7 : 2);
            assertThat(fixture.count("core.payment_transition")).isEqualTo(reference.settled() ? 2 : 1);
            if (reference.settled()) {
                assertThat(fixture.balances()).containsExactly(0L, 9680L, 0L, 0L, 9680L, 0L, 0L, 0L);
            } else {
                assertThat(fixture.balances()).containsExactly(10000L, 0L, 0L, 10000L, 0L, 0L, 0L, 0L);
            }
        }
    }
}
