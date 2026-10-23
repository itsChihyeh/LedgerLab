package dev.ledgerlab.model;

import static dev.ledgerlab.model.P2Scenario.Kind.ADVANCE_DAYS;
import static dev.ledgerlab.model.P2Scenario.Kind.BANK_DUPLICATE_FILE;
import static dev.ledgerlab.model.P2Scenario.Kind.BANK_EXACT;
import static dev.ledgerlab.model.P2Scenario.Kind.BANK_PENDING;
import static dev.ledgerlab.model.P2Scenario.Kind.BANK_SHORT;
import static dev.ledgerlab.model.P2Scenario.Kind.BANK_UNKNOWN;
import static dev.ledgerlab.model.P2Scenario.Kind.BOOK_PENDING;
import static dev.ledgerlab.model.P2Scenario.Kind.CREATE_BATCH;
import static dev.ledgerlab.model.P2Scenario.Kind.FAIL;
import static dev.ledgerlab.model.P2Scenario.Kind.INTERRUPT_FETCH;
import static dev.ledgerlab.model.P2Scenario.Kind.NEW_ID_READY;
import static dev.ledgerlab.model.P2Scenario.Kind.READY;
import static dev.ledgerlab.model.P2Scenario.Kind.REISSUE;
import static dev.ledgerlab.model.P2Scenario.Kind.REPLAY_PAYOUT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class P2ScenarioTest {
    private static PaymentModelProperties.State state(ModelApplication app) {
        return state(app, "10000", "5000", "4000");
    }

    private static PaymentModelProperties.State state(ModelApplication app, String... amounts) {
        var state = new PaymentModelProperties.State(1, app);
        for (String amount : amounts) {
            String event = state.nextEvent();
            state.receive(new PaymentModel.Input(
                    event,
                    "ch_" + state.prefix + state.counter,
                    "charge.succeeded",
                    amount,
                    amount,
                    "cad",
                    true,
                    false,
                    false));
        }
        state.drain("capture-fixture");
        state.check();
        return state;
    }

    private static void step(PaymentModelProperties.State state, P2Scenario.Kind kind, int index, boolean variant) {
        assertThat(state.p2.eligible(kind)).as("precondition %s", kind).isTrue();
        state.p2.execute(kind, index, variant);
        state.check();
    }

    private static void replay(ModelApplication app) {
        app.assertNoPending();
        try (var replay = new ModelApplication()) {
            app.log.replay(replay);
            RebuildSnapshot.read(app).assertMatches(RebuildSnapshot.read(replay));
        }
    }

    @Test
    void ac_4_11_ac_4_13_ac_5_20_multiPaymentBatchWakesEarlyCashAndRetainsDuplicateDeposit() {
        try (var app = new ModelApplication()) {
            var state = state(app);
            step(state, CREATE_BATCH, 2, false);
            step(state, BANK_EXACT, 0, false);
            step(state, BANK_EXACT, 0, false);
            assertThat(state.model.settlement.banks.values())
                    .allSatisfy(b -> assertThat(b.match).isEqualTo("WAITING"));
            step(state, READY, 0, false);
            step(state, NEW_ID_READY, 0, false);
            step(state, REPLAY_PAYOUT, 1, false);
            step(state, BANK_DUPLICATE_FILE, 0, false);
            assertThat(app.balances().get(1103)).isEqualTo(36_718L);
            assertThat(app.balances().get(2105)).isEqualTo(18_359L);
            replay(app);
        }
    }

    @Test
    void ac_4_11_ac_4_13_ac_5_18_failedPayoutReissueHasTwoPostingsAndNoSecondMerchantCredit() {
        try (var app = new ModelApplication()) {
            var state = state(app, "10000");
            step(state, CREATE_BATCH, 2, false);
            step(state, INTERRUPT_FETCH, 0, false);
            step(state, FAIL, 0, false);
            long available = app.balances().get(2102);
            assertThat(available).isEqualTo(9680L);
            step(state, REISSUE, 0, false);
            assertThat(app.balances().get(2102)).isEqualTo(available);
            var entries = state.model.settlement.entries.values().stream()
                    .filter(e -> e.type().equals("BATCH_SETTLED"))
                    .toList();
            assertThat(entries.getLast().postings()).hasSize(2);
            step(state, BANK_SHORT, 1, false);
            step(state, BANK_EXACT, 1, false);
            replay(app);
        }
    }

    @Test
    void ac_4_11_ac_4_13_ac_5_17_ac_5_20_readyBeforeCreatedPendingAndExpiredWaitingReplay() {
        try (var app = new ModelApplication()) {
            var state = state(app);
            step(state, CREATE_BATCH, 1, true);
            step(state, BANK_PENDING, 0, false);
            // This BOOKED line precedes the first line's BOOKED version in import_order.
            step(state, BANK_EXACT, 0, false);
            step(state, BOOK_PENDING, 0, false);
            step(state, READY, 0, false);
            step(state, BANK_UNKNOWN, 0, false);
            step(state, ADVANCE_DAYS, 0, true);
            replay(app);
        }
    }
}
