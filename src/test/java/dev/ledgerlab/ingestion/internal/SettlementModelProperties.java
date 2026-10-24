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
import net.jqwik.api.statistics.Statistics;

/** T05/T06 batch actions, reordered captures and new-ID repeated facts against a pure Java oracle. */
class SettlementModelProperties {
    private SettlementWorkerTest fixture = new SettlementWorkerTest();
    private boolean initiallyCaptured;

    @Property(seed = "2026102308")
    void ac_4_11_ac_4_1_ac_5_3_batchActionsMatchIndependentReference(
            @ForAll("actions") ActionChain<State> actions, @ForAll boolean capturedInitially) throws Exception {
        fixture = new SettlementWorkerTest();
        initiallyCaptured = capturedInitially;
        fixture.start();
        try {
            if (capturedInitially) fixture.capture();
            Statistics.label("initial capture").collect(capturedInitially);
            actions.withInvariant("batch balances, history, issues and exactly-once effects", State::check)
                    .run();
        } finally {
            fixture.stop();
        }
    }

    @Provide
    Arbitrary<ActionChain<State>> actions() {
        Action.Independent<State> operation = () -> Arbitraries.of(SettlementReference.Action.values())
                .map(action -> Transformer.mutate(action.name(), state -> state.apply(action)));
        return ActionChain.startWith(() -> new State(fixture, initiallyCaptured))
                .withAction(operation)
                .withMaxTransformations(24);
    }

    private static final class State {
        private final SettlementWorkerTest fixture;
        private final SettlementReference reference = new SettlementReference();
        private int sequence;

        State(SettlementWorkerTest fixture, boolean captured) {
            this.fixture = fixture;
            reference.captured = captured;
        }

        void apply(SettlementReference.Action action) {
            if (!reference.enabled(action)) {
                Statistics.label("precondition skipped").collect(action.name());
                return;
            }
            Statistics.label("executed action").collect(action.name());
            String eventId = "evt_model_" + ++sequence;
            if (action.name().contains("BANK")) {
                fixture.modelBank(action.name());
            } else if (action == SettlementReference.Action.CAPTURE) {
                fixture.event(eventId, "charge.succeeded", fixture.charge("ch_p1", 10000));
            } else {
                String type = switch (action) {
                    case ANNOUNCE, CREATED_READY, UNSUPPORTED -> "payout.created";
                    case PAID -> "payout.paid";
                    case FAILED -> "payout.failed";
                    case CANCELED -> "payout.canceled";
                    default -> "payout.reconciliation_completed";
                };
                String batch = switch (action) {
                    case REISSUE -> "po_reissued";
                    case OTHER_BATCH -> "po_other";
                    default -> "po_b1";
                };
                fixture.modelLines(
                        action == SettlementReference.Action.CHANGED_VERSION,
                        action == SettlementReference.Action.REISSUE);
                var payout = fixture.payout(
                        batch, action == SettlementReference.Action.ANNOUNCE ? "in_progress" : "completed");
                if (action == SettlementReference.Action.UNSUPPORTED) payout.put("automatic", false);
                if (action == SettlementReference.Action.UNSUPPORTED_LINE) fixture.modelUnsupportedLine();
                if (action == SettlementReference.Action.FEE_EXCEEDS_GROSS) fixture.modelExcessFee();
                fixture.event(eventId, type, payout);
            }
            fixture.drain();
            reference.apply(action);
            Statistics.label("resulting state").collect(reference.status);
        }

        void check() {
            fixture.assertLedger();
            fixture.assertSettlementInvariants();
            long batches = (reference.status.equals("ABSENT") ? 0 : 1)
                    + (reference.reissued ? 1 : 0)
                    + (reference.otherBatch ? 1 : 0);
            assertThat(fixture.count("core.settlement_batch")).isEqualTo(batches);
            if (!reference.status.equals("ABSENT")) {
                assertThat(fixture.state()).isEqualTo(reference.status);
                assertThat(fixture.holdReason())
                        .isEqualTo(reference.holdReason.isEmpty() ? null : reference.holdReason);
            }
            assertThat(fixture.count("core.batch_transition")).isEqualTo(reference.history);
            assertThat(fixture.count("core.payment")).isEqualTo(reference.captured ? 1 : 0);
            assertThat(fixture.count("ledger.posting"))
                    .isEqualTo((reference.captured ? 2 : 0)
                            + (reference.settled ? 5 : 0)
                            + (reference.failed ? 2 : 0)
                            + (reference.reissued ? 2 : 0)
                            + reference.bankEntries * 2);
            assertThat(fixture.count("core.payment_transition"))
                    .isEqualTo((reference.captured ? 1 : 0) + (reference.settled ? 1 : 0));
            assertThat(fixture.count("core.settlement_batch_line"))
                    .isEqualTo((reference.settled ? 1 : 0) + (reference.reissued ? 1 : 0));
            assertThat(fixture.issueIdentities()).containsExactlyInAnyOrderElementsOf(reference.issues);
            assertThat(fixture.balances()).isEqualTo(reference.balances());
            assertThat(fixture.count("io.imported_file")).isEqualTo(reference.bankFiles.size());
            assertThat(fixture.count("recon.bank_line")).isEqualTo(reference.bankVersions);
            reference.banks.forEach(
                    (id, bank) -> assertThat(fixture.bankState(id)).isEqualTo(bank.state));
        }
    }
}
