package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

class ProcessorStatefulProperties {
    private final List<ProcessorRuleTest> fixtures = new ArrayList<>();

    @AfterTry
    void cleanup() {
        fixtures.forEach(ProcessorRuleTest::cleanup);
        fixtures.clear();
    }

    @Property(seed = "2026102310")
    void ac_5_9_snapshotActionsMatchIndependentModelAndNeverChangeMoney(
            @ForAll("operations") ActionChain<State> chain) {
        chain.withInvariant("snapshots, latest findings and money", State::verify)
                .run();
    }

    @Provide
    Arbitrary<ActionChain<State>> operations() {
        return ActionChain.startWith(() -> {
                    var fixture = new ProcessorRuleTest();
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
            return Arbitraries.of(ProcessorReference.Operation.values())
                    .map(op -> Transformer.mutate(op.name(), state -> state.apply(op)));
        }
    }

    static final class State {
        final ProcessorRuleTest fixture;
        final ProcessorReference model = new ProcessorReference();
        final Map<String, List<String>> before;

        State(ProcessorRuleTest fixture) {
            this.fixture = fixture;
            fixture.payment("ch_p1", 10000, ProcessorRuleTest.CAPTURED);
            fixture.batch(
                    "po_b1", ProcessorRuleTest.CAPTURED, List.of(fixture.localLine("txn_l1", "ch_p1", 10000, 320)));
            fixture.clock.set(Instant.parse("2026-11-11T08:00:00Z"));
            before = fixture.money();
        }

        void apply(ProcessorReference.Operation op) {
            model.apply(op);
            if (op == ProcessorReference.Operation.REPLAY) return;
            fixture.clock.set(fixture.clock.instant().plusSeconds(120));
            String row = fixture.line("txn_l1", "ch_p1", 10000, op == ProcessorReference.Operation.FEE ? 321 : 320);
            String lines = ProcessorSnapshotsTest.page(
                    op == ProcessorReference.Operation.DUPLICATE ? row + "," + row : row,
                    op == ProcessorReference.Operation.INCOMPLETE);
            fixture.snapshots.importSnapshot(
                    "model",
                    LocalDate.parse("2026-11-10"),
                    ProcessorSnapshotsTest.PAYOUTS,
                    Map.of("po_b1", lines),
                    fixture.clock.instant());
        }

        void verify() {
            fixture.clock.set(fixture.clock.instant().plusSeconds(120));
            var first = fixture.run(
                    ProcessorRuleTest.CUTOFF, fixture.clock.instant().minusSeconds(61), true);
            assertThat(first.summary()).isEqualTo(model.findings());
            assertThat(fixture.run(
                                    ProcessorRuleTest.CUTOFF,
                                    fixture.clock.instant().minusSeconds(61),
                                    true)
                            .findings())
                    .isEqualTo(first.findings());
            assertThat(fixture.jdbc
                            .sql("SELECT count(*) FROM io.imported_file")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.archived.size());
            assertThat(fixture.jdbc
                            .sql("SELECT count(*) FROM recon.statement_line")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.rows);
            assertThat(fixture.jdbc
                            .sql("SELECT count(*) FROM recon.issue WHERE category='STATEMENT_VERSION_CONFLICT'")
                            .query(Long.class)
                            .single())
                    .isEqualTo(model.conflict ? 1 : 0);
            assertThat(fixture.money()).isEqualTo(before);
        }
    }
}
