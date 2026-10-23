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
    void ac_5_9_bankAndAgingActionsMatchIndependentModel(@ForAll("operations") ActionChain<State> chain) {
        chain.withInvariant("findings, replay and unchanged ledger", State::verify)
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
        }
    }
}
