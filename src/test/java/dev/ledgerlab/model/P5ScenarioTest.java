package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class P5ScenarioTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void ac_7_27_receiptsBankEvidenceCorrectionAndQueriesRebuild(int variant) {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            s.p5.prepare(false);
            s.p5.reserve(1);
            s.check(); // FREE_CASH
            s.p5.prepare(true);
            var p = s.p5.reserve(5000);
            s.check();
            s.p5.receipts(variant);
            s.check();
            s.p5.query(p);
            s.check();
            if (variant == 1) {
                s.p5.bank(p, true);
                s.check();
                s.p5.correct(s.p5.conflicts().getFirst());
                s.check();
            } else {
                s.p5.finish();
                s.p5.bank(p, true);
                s.check();
            }
            s.p5.bank(p, false);
            s.check();
            s.p5.query(p);
            s.check();
            s.p5.execute(P5Scenario.Kind.REPLAY_PAYOUT_FILE, 0, 0);
            s.check();
            s.p5.reserve(99_999_999);
            s.check();
            s.p5.finish();
            P3ScenarioTest.replay(s);
        }
    }

    @Test
    void ac_7_27_payoutReleaseWakesRefundQueueAndCorrectionMayCreateDebt() {
        try (var app = new ModelApplication()) {
            var s = new PaymentModelProperties.State(1, app);
            s.p5.prepare(true);
            var p = s.p5.reserve(9680);
            s.check();
            String payment = s.model.payments.keySet().iterator().next();
            s.p3.accept(payment, 800);
            s.check();
            assertThat(s.p3.waiting()).hasSize(1);
            s.p5.receipts(1);
            s.check();
            assertThat(s.p3.waiting()).isEmpty();
            s.p5.bank(p, true);
            s.check();
            s.p5.correct(s.p5.conflicts().getFirst());
            s.check();
            assertThat(app.balances().get(2102)).isEqualTo(-800);
            s.p5.bank(p, false);
            s.check();
            P3ScenarioTest.replay(s);
        }
    }
}
