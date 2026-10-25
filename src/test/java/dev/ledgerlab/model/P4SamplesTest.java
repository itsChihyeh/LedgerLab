package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class P4SamplesTest {
    @Test
    void ac_1_4d_sampleEEveryCellIncludingAtomicReversalAndRestatement() {
        try (var app = new ModelApplication()) {
            var state = new PaymentModelProperties.State(1, app);
            P3ScenarioTest.charge(state, "p5", 4500);
            cells(app.balances(), 4500, 0, 0, 4500, 0, 0, 0, 0);
            String payment = "ch_" + state.prefix + "p5";
            var batch = new SettlementModel.Batch(
                    "po_" + state.prefix + "b6",
                    List.of(new SettlementModel.Line("txn_b6", "charge", payment, 5400, 187)),
                    app.clock.instant());
            state.model.settlement.batches.put(batch.id, batch);
            state.p2.install(batch, false);
            state.p2.event(batch, "payout.reconciliation_completed");
            state.check();
            assertThat(batch.status).isEqualTo("HELD");
            cells(app.balances(), 4500, 0, 0, 4500, 0, 0, 0, 0);

            state.p4.capture(payment, 5400L);
            state.check();
            assertThat(batch.status).isEqualTo("CONFIRMED");
            // Reconstruct all eight cells at each entry boundary from persisted balance_after.
            // These intermediate snapshots are inside the single correction transaction.
            Map<Integer, Long> checkpoint = new TreeMap<>();
            app.balances().keySet().forEach(code -> checkpoint.put(code, 0L));
            long[][] expected = {
                {4500, 0, 0, 4500, 0, 0, 0, 0},
                {0, 0, 0, 0, 0, 0, 0, 0},
                {5400, 0, 0, 5400, 0, 0, 0, 0},
                {0, 5213, 0, 0, 5213, 0, 0, 0}
            };
            var entries = app.jdbc
                    .sql("SELECT id FROM ledger.journal_entry ORDER BY seq")
                    .query(String.class)
                    .list();
            assertThat(entries).hasSize(expected.length);
            for (int i = 0; i < entries.size(); i++) {
                app.jdbc
                        .sql("SELECT account_code,balance_after FROM ledger.posting WHERE entry_id=? ORDER BY line_no")
                        .param(entries.get(i))
                        .query((rs, n) -> {
                            checkpoint.put(rs.getInt(1), rs.getLong(2));
                            return 1;
                        })
                        .list();
                cells(checkpoint, expected[i]);
            }
            cells(app.balances(), 0, 5213, 0, 0, 5213, 0, 0, 0);
            assertThat(state.p3.model.remaining(payment)).isEqualTo(5400);
            assertThat(state.model.settlement.resolutions).hasSize(1);
            P3ScenarioTest.replay(state);
        }
    }

    private static void cells(Map<Integer, Long> balances, long... expected) {
        assertThat(new TreeMap<>(balances).values())
                .containsExactly(java.util.Arrays.stream(expected).boxed().toArray(Long[]::new));
    }
}
