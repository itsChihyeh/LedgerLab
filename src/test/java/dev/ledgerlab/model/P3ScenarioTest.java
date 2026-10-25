package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class P3ScenarioTest {
    @Test
    void ac_3_7_ac_4_11_fifoReleasesOnlyLongestPrefixAfterNewSettlement() {
        try (var app = new ModelApplication()) {
            var state = new PaymentModelProperties.State(1, app);
            charge(state, "funding", 1576);
            state.p2.execute(P2Scenario.Kind.CREATE_BATCH, 2, false);
            state.check();
            state.p2.execute(P2Scenario.Kind.READY, 0, false);
            state.check();
            assertThat(app.balances().get(2102)).isEqualTo(1500L);
            charge(state, "refundable", 10000);
            state.p3.accept("ch_" + state.prefix + "refundable", 2000);
            state.check();
            state.p3.accept("ch_" + state.prefix + "refundable", 1000);
            state.check();
            assertThat(state.p3.waiting()).hasSize(2);
            charge(state, "topup", 1061);
            var batch = new SettlementModel.Batch(
                    "po_" + state.prefix + "topup",
                    java.util.List.of(
                            new SettlementModel.Line("txn_topup", "charge", "ch_" + state.prefix + "topup", 1061, 61)),
                    app.clock.instant());
            state.model.settlement.batches.put(batch.id, batch);
            state.p2.install(batch, false);
            state.p2.event(batch, "payout.reconciliation_completed");
            state.check();
            assertThat(state.p3.waiting()).hasSize(1);
            assertThat(state.p3.waiting().getFirst().amount).isEqualTo(1000);
            assertThat(app.balances().get(2102)).isEqualTo(-500L);
            replay(state);
        }
    }

    static void charge(PaymentModelProperties.State state, String id, long amount) {
        state.receive(new PaymentModel.Input(
                state.nextEvent(),
                "ch_" + state.prefix + id,
                "charge.succeeded",
                Long.toString(amount),
                Long.toString(amount),
                "cad",
                true,
                false,
                false));
        state.drain("p3-fixture");
        state.check();
    }

    static PaymentModelProperties.State funded(ModelApplication app) {
        var state = new PaymentModelProperties.State(1, app);
        charge(state, "p1", 10000);
        state.p2.execute(P2Scenario.Kind.CREATE_BATCH, 2, false);
        state.check();
        state.p2.execute(P2Scenario.Kind.READY, 0, false);
        state.check();
        charge(state, "p2", 5000);
        return state;
    }

    static void replay(PaymentModelProperties.State state) {
        state.p3.finish();
        state.p2.finish();
        state.check();
        state.app.assertNoPending();
        try (var rebuilt = new ModelApplication()) {
            state.app.log.replay(rebuilt);
            RebuildSnapshot.read(state.app).assertMatches(RebuildSnapshot.read(rebuilt));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void ac_4_11_ac_4_13_receiptsTimeoutsAndExpiredRecoveryRebuild(int variant) {
        try (var app = new ModelApplication()) {
            var state = funded(app);
            state.p3.accept("ch_" + state.prefix + "p1", 2000);
            state.check();
            state.p3.command(variant);
            state.check();
            if (!state.p3.commands().isEmpty()) {
                app.advance(25 * 3600);
                state.p3.command(7);
                state.check();
                state.p3.execute(P3Scenario.Kind.RECOVER_EXPIRED, 0, 0);
                state.check();
            }
            if (!state.p3.external().isEmpty()) {
                state.p3.execute(P3Scenario.Kind.REFUND_WEBHOOK, 0, 0);
                state.check();
                state.p3.execute(P3Scenario.Kind.REPLAY_REFUND, 0, 0);
                state.check();
            }
            state.p3.execute(P3Scenario.Kind.API_REPLAY, 0, 0);
            state.check();
            replay(state);
        }
    }

    @Test
    void ac_3_7_ac_4_11_ac_4_13_waitingCancellationAndRefundBatchUseIndependentOracle() {
        try (var app = new ModelApplication()) {
            var state = new PaymentModelProperties.State(1, app);
            String payment = "ch_" + state.prefix + "p1";
            charge(state, "p1", 10000);
            state.p3.accept(payment, 10000);
            state.check();
            state.p3.accept(payment, 1);
            state.check();
            state.p3.cancel(state.p3.waiting().getFirst());
            state.check();
            state.p2.execute(P2Scenario.Kind.CREATE_BATCH, 2, false);
            state.check();
            state.p2.execute(P2Scenario.Kind.READY, 0, false);
            state.check();
            charge(state, "p2", 5000);
            state.p3.accept(payment, 2000);
            state.check();
            state.p3.command(1);
            state.check();
            assertThat(state.p3.eligible(P3Scenario.Kind.REFUND_BATCH)).isTrue();
            state.p3.batch();
            state.check();
            state.p3.execute(P3Scenario.Kind.REFUND_WEBHOOK, 0, 1);
            state.check();
            replay(state);
        }
    }
}
