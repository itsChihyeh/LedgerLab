package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class P4ScenarioTest {
    @Test
    void ac_6_13_bankCorrectionBeforeLocalBatchExistsLeavesNoWrites() {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            P3ScenarioTest.charge(s, "early", 10000);
            s.p2.execute(P2Scenario.Kind.CREATE_BATCH, 2, true);
            s.check();
            s.p2.execute(P2Scenario.Kind.BANK_EXACT, 0, false);
            s.check();
            s.p4.invalid(0, 4);
            s.check();
            P3ScenarioTest.replay(s);
        }
    }

    @Test
    void ac_6_13_consecutiveCorrectionsRefundFloorVoidAndDuplicateInputs() {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            P3ScenarioTest.charge(s, "payment", 4500);
            String p = "ch_" + s.prefix + "payment";
            s.p4.capture(p, 5400L);
            s.check();
            s.p4.capture(p, 6000L);
            s.check();
            s.p4.invalid(0, 0);
            s.check();
            s.p4.capture(p, 6000L);
            s.check();
            s.p4.capture(p, 49L);
            s.check();
            s.p3.accept(p, 3000);
            s.check();
            s.p4.capture(p, null);
            s.check();
            s.p4.capture(p, 2999L);
            s.check();
            s.p4.capture(p, 3000L);
            s.check();
            s.p3.accept(p, 1);
            s.check();
            s.p3.cancel(s.p3.waiting().getFirst());
            s.check();
            s.p4.capture(p, null);
            s.check();
            s.p3.accept(p, 1);
            s.check();
            var input = s.model.events.values().iterator().next().input();
            s.receive(input.withId(s.nextEvent()).withAmount("3000"));
            s.drain("void-replay");
            s.check();
            assertThat(s.model.voided).containsExactly(p);
            var batch = new SettlementModel.Batch(
                    "po_" + s.prefix + "void",
                    List.of(new SettlementModel.Line("txn_void", "charge", p, 3000, 117)),
                    app.clock.instant());
            s.model.settlement.batches.put(batch.id, batch);
            s.p2.install(batch, false);
            s.p2.event(batch, "payout.reconciliation_completed");
            s.check();
            s.p4.recheck(batch);
            s.check();
            assertThat(batch.hold).isEqualTo("PAYMENT_STATE_CONFLICT");
            P3ScenarioTest.replay(s);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void ac_6_13_bankCorrectionsAndRejectedRepeatRebuild(int variant) {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            var batch = s.p4.prepare(variant);
            var banks = s.p4.credits(batch);
            if (variant == 1) {
                s.p4.bankCorrection(batch, List.of(banks.getFirst()), false);
                s.check();
            }
            s.p4.bankCorrection(batch, banks, variant == 2);
            s.check();
            s.p4.bankCorrection(batch, banks, variant == 2);
            s.check();
            s.p4.recheck(batch);
            s.check();
            String payment = batch.lines.getFirst().reference();
            s.p4.capture(payment, null);
            s.check();
            s.p4.capture(payment, 12000L);
            s.check();
            P3ScenarioTest.replay(s);
        }
    }

    @Test
    void ac_6_13_returnCashRequiresExactCurrentUnappliedCredit() {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            var batch = s.p4.prepare(2);
            var credit = s.p4.credits(batch).getFirst();
            s.p4.returnCash(credit, true);
            s.check();
            s.p4.returnCash(credit, false);
            s.check();
            s.p4.returnCash(credit, false);
            s.check();
            s.p4.bankCorrection(batch, List.of(credit), true);
            s.check();
            P3ScenarioTest.replay(s);
        }
    }

    @Test
    void ac_6_13_heldRecheckRetainsIssueUntilCorrectionWakesWaitingBank() {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            var batch = s.p4.prepare(0);
            s.p4.credit(batch, batch.net);
            s.check();
            s.p4.recheck(batch);
            s.check();
            String payment = batch.lines.getFirst().reference();
            s.p4.capture(payment, 5000L);
            s.check();
            assertThat(batch.status).isEqualTo("HELD");
            s.p4.capture(payment, 5400L);
            s.check();
            assertThat(batch.status).isEqualTo("BANKED");
            P3ScenarioTest.replay(s);
        }
    }

    @Test
    void ac_6_13_failedPayoutAlreadyReturnedCannotBeRestored() {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            var batch = s.p4.prepare(2);
            s.p2.execute(P2Scenario.Kind.REISSUE, 0, false);
            s.check();
            s.p4.bankCorrection(batch, s.p4.credits(batch), true);
            s.check();
            s.p4.returnCash(s.p4.credits(batch).getFirst(), false);
            s.check();
            P3ScenarioTest.replay(s);
        }
    }
}
