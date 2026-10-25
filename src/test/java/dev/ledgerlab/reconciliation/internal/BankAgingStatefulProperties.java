package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

class BankAgingStatefulProperties {
    private final List<BankAgingRuleTest> fixtures = new ArrayList<>();

    @AfterTry
    void cleanup() {
        fixtures.forEach(BankAgingRuleTest::cleanup);
        fixtures.clear();
    }

    @Property(seed = "2026102311")
    void ac_5_9_ac_6_9_bankAndReturnActionsMatchIndependentModel(@ForAll("operations") ActionChain<State> chain) {
        chain.withInvariant("findings, replay, balances and INV-12", State::verify)
                .run();
    }

    @Provide
    Arbitrary<ActionChain<State>> operations() {
        return ActionChain.startWith(() -> {
                    var fixture = new BankAgingRuleTest();
                    fixture.setup();
                    fixtures.add(fixture);
                    return new State(fixture);
                })
                .withAction(new Actions())
                .withMaxTransformations(12);
    }

    static final class Actions implements Action.Independent<State> {
        @Override
        public Arbitrary<Transformer<State>> transformer() {
            return Arbitraries.of(BankAgingReference.Operation.values())
                    .map(op -> Transformer.mutate(op.name(), state -> state.apply(op)));
        }
    }

    static final class State {
        final BankAgingRuleTest fixture;
        final BankAgingReference model = new BankAgingReference();

        State(BankAgingRuleTest fixture) {
            this.fixture = fixture;
            fixture.f.payment("ch_model", 10000, ProcessorRuleTest.CAPTURED);
            fixture.f.clock.set(Instant.parse("2026-11-12T12:00:00Z"));
        }

        void apply(BankAgingReference.Operation operation) {
            fixture.f.clock.set(fixture.f.clock.instant().plusSeconds(120));
            switch (operation) {
                case CONFIRM -> {
                    if (!model.confirmed)
                        fixture.f.batch(
                                "po_model",
                                ProcessorRuleTest.CAPTURED,
                                List.of(fixture.f.localLine("txn_model", "ch_model", 10000, 320)));
                }
                case IMPORT ->
                    fixture.statement(
                            "2026-11-10", 1, 0, 9680, fixture.credit("bt_model", 9680, "po_model", "2026-11-10"));
                case ADVANCE_TO_DUE -> {
                    if (!model.expired) fixture.f.clock.set(Instant.parse("2026-11-14T12:00:00Z"));
                }
                case EXPIRE_WAITING -> fixture.bank.expireWaiting();
                case RETURN_UNAPPLIED -> {
                    if (model.unapplied)
                        fixture.statement(
                                "2026-11-14",
                                1,
                                9680,
                                0,
                                "D,bt_return,DEBIT,9680,CAD,BOOKED,bt_model,2026-11-14,2026-11-14,return");
                }
                case REPLAY -> {}
            }
            model.apply(operation);
        }

        void verify() {
            fixture.f.clock.set(fixture.f.clock.instant().plusSeconds(120));
            Instant cutoff = model.expired ? Instant.parse("2026-11-14T05:00:00Z") : ProcessorRuleTest.CUTOFF;
            Instant evidence = fixture.f.clock.instant().minusSeconds(61);
            var money = fixture.f.money();
            for (var kind : List.of(RunRequest.Kind.BANK, RunRequest.Kind.AGING)) {
                var report = fixture.run(kind, cutoff, evidence, false);
                Set<String> expected =
                        kind == RunRequest.Kind.BANK ? model.bank() : model.aging(fixture.f.paymentId("ch_model"));
                assertThat(report.findings().stream()
                                .map(o -> o.finding().category() + ":"
                                        + o.finding().subjectType() + ":"
                                        + o.finding().subjectId())
                                .collect(Collectors.toSet()))
                        .isEqualTo(expected);
                assertThat(fixture.run(kind, cutoff, evidence, true).findings()).isEqualTo(report.findings());
            }
            assertThat(fixture.f.money()).isEqualTo(money);
            long unapplied = fixture.f
                    .jdbc
                    .sql("SELECT balance FROM ledger.account WHERE code=2105")
                    .query(Long.class)
                    .single();
            assertThat(unapplied).isEqualTo(model.unapplied ? 9680 : 0);
            assertThat(fixture.f.jdbc.sql("""
                    SELECT coalesce(sum(amount),0) FROM (
                        SELECT DISTINCT ON (bank_txn_id) * FROM recon.bank_line
                        ORDER BY bank_txn_id, version DESC
                    ) current_lines WHERE match_status='UNAPPLIED'
                    """).query(Long.class).single()).isEqualTo(unapplied);
            assertThat(fixture.f
                            .jdbc
                            .sql("SELECT balance FROM ledger.account WHERE code=1103")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.matched || model.unapplied ? 9680 : 0);
            if (model.returned)
                assertThat(fixture.f
                                .jdbc
                                .sql("SELECT match_status FROM recon.bank_line ORDER BY bank_txn_id")
                                .query(String.class)
                                .list())
                        .containsExactly("RETURNED", "RETURN_POSTED");
        }
    }
}
