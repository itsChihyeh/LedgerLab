package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ingestion.internal.WorkerHooks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

class PaymentModelProperties {
    static final String SEED = "2026102201";
    private static ModelApplication app;
    private static ModelReport report;
    private static long sequenceId;
    private State current;

    @BeforeContainer
    static void start() {
        report = new ModelReport();
        app = new ModelApplication();
    }

    @AfterContainer
    static void stop() throws Exception {
        try {
            report.write(SEED);
        } finally {
            if (app != null) app.close();
        }
    }

    @Property(seed = SEED, shrinking = ShrinkingMode.BOUNDED, generation = GenerationMode.RANDOMIZED)
    void ac_4_11_p1ActionsMatchIndependentModel(@ForAll("actions") ActionChain<State> chain) {
        current = null;
        boolean passed = false;
        try {
            chain.withInvariant("P1 ledger, payment, inbox and issue model", State::check)
                    .run();
            current.drain("final-worker");
            current.check();
            passed = true;
        } catch (AssertionError | RuntimeException failure) {
            report.lastFailure = chain + "\n" + failure;
            throw failure;
        } finally {
            if (current != null) {
                report.sequences++;
                report.steps += current.length;
                report.maxLength = Math.max(report.maxLength, current.length);
                if (passed) report.passed++;
            }
        }
    }

    @AfterTry
    void finishPendingWork() {
        // Finish failed/shrunk attempts before the next baseline without rewriting sealed entries.
        if (current == null) return;
        try {
            app.advance(31);
            app.drain("cleanup-worker");
        } catch (AssertionError | RuntimeException cleanupFailure) {
            report.cleanupFailures++;
            throw cleanupFailure;
        } finally {
            current = null;
        }
    }

    @Provide
    Arbitrary<ActionChain<State>> actions() {
        return ActionChain.startWith(() -> {
                    current = new State(++sequenceId);
                    return current;
                })
                .withAction(5, new NewCharge())
                .withAction(2, new Replay(false, false))
                .withAction(2, new Replay(false, true))
                .withAction(3, new Replay(true, false))
                .withAction(2, new Replay(true, true))
                .withAction(1, simple("UNSUBSCRIBED", s -> s.other("customer.created")))
                .withAction(1, simple("DISPUTE", s -> s.other("charge.dispute.created")))
                .withAction(3, simple("DRAIN", s -> s.drain("normal-worker")))
                .withAction(2, new Crash(false))
                .withAction(2, new Crash(true))
                .withAction(1, (Action.Independent<State>) () -> Arbitraries.integers()
                        .between(1, 240)
                        .map(seconds -> step("ADVANCE_CLOCK", "advance " + seconds + "s", s -> app.advance(seconds))))
                .withMaxTransformations(24);
    }

    private static Action<State> simple(String name, Consumer<State> operation) {
        return (Action.Independent<State>) () -> Arbitraries.just(step(name, name, operation));
    }

    private static Transformer<State> step(String action, String description, Consumer<State> operation) {
        return Transformer.mutate(description, state -> {
            report.action(action);
            state.length++;
            operation.accept(state);
        });
    }

    static final class NewCharge implements Action.Independent<State> {
        @Override
        public Arbitrary<Transformer<State>> transformer() {
            Arbitrary<String> amounts = Arbitraries.oneOf(
                    Arbitraries.longs().between(50, 99_999_999).map(Object::toString),
                    Arbitraries.of("50", "99999999", "49", "100000000", "100.5", "0", "-1", "\"100\""));
            // Repeated NORMAL preserves enough admissible charges for dependent actions.
            Arbitrary<String> variants = Arbitraries.of(
                    "NORMAL",
                    "NORMAL",
                    "NORMAL",
                    "NORMAL",
                    "CURRENCY",
                    "NOT_CAPTURED",
                    "PARTIAL",
                    "ENVELOPE_LIVE",
                    "CHARGE_LIVE");
            return Combinators.combine(amounts, variants)
                    .as((amount, variant) -> step("NEW_CHARGE", "charge amount=" + amount + " " + variant, s -> {
                        String event = s.nextEvent();
                        s.receive(new PaymentModel.Input(
                                event,
                                "ch_" + s.prefix + s.counter,
                                "charge.succeeded",
                                amount,
                                variant.equals("PARTIAL") ? "1" : amount,
                                variant.equals("CURRENCY") ? "usd" : "cad",
                                !variant.equals("NOT_CAPTURED"),
                                variant.equals("ENVELOPE_LIVE"),
                                variant.equals("CHARGE_LIVE")));
                    }));
        }
    }

    record Replay(boolean newId, boolean changed) implements Action.Dependent<State> {
        private List<PaymentModel.Input> candidates(State state) {
            return newId
                    ? state.model.validCharges()
                    : state.model.events.values().stream()
                            .map(PaymentModel.Received::input)
                            .toList();
        }

        @Override
        public boolean precondition(State state) {
            return !candidates(state).isEmpty();
        }

        @Override
        public Arbitrary<Transformer<State>> transformer(State state) {
            return Arbitraries.integers()
                    .between(0, candidates(state).size() - 1)
                    .map(index -> {
                        String name = newId
                                ? (changed ? "NEW_ID_CHANGED_AMOUNT" : "NEW_ID_SAME_FACT")
                                : (changed ? "SAME_ID_CHANGED_AMOUNT" : "SAME_ID_SAME_BODY");
                        return step(name, name + " event-index=" + index, s -> {
                            PaymentModel.Input input = candidates(s).get(index);
                            if (newId) input = input.withId(s.nextEvent());
                            else app.advance(1); // A genuinely new signature for same-ID redelivery.
                            if (changed) input = input.withAmount(input.amount().equals("50") ? "51" : "50");
                            s.receive(input);
                        });
                    });
        }
    }

    record Crash(boolean afterBusiness) implements Action.Dependent<State> {
        @Override
        public boolean precondition(State state) {
            return !state.model.pending().isEmpty();
        }

        @Override
        public Arbitrary<Transformer<State>> transformer(State state) {
            String name = afterBusiness ? "CRASH_AFTER_BUSINESS" : "CRASH_AFTER_CLAIM";
            return Arbitraries.just(step(name, name, s -> s.crash(afterBusiness)));
        }
    }

    static final class State {
        final String prefix;
        final PaymentModel model = new PaymentModel();
        final Map<Integer, Long> baseline = app.balances();
        final long entriesBefore = app.jdbc
                .sql("SELECT count(*) FROM ledger.journal_entry")
                .query(Long.class)
                .single();
        int counter;
        int length;

        State(long sequence) {
            // No SQL LIKE metacharacters: exact prefix filtering cannot include another sequence.
            prefix = "model" + sequence + "x";
        }

        String nextEvent() {
            // Same received_at, reverse lexical IDs: a later delivery can be processed first.
            return "evt_" + prefix + "%04d".formatted(9999 - ++counter);
        }

        void receive(PaymentModel.Input input) {
            app.deliver(input);
            model.receive(input, app.clock.instant());
        }

        void other(String type) {
            String id = nextEvent();
            receive(new PaymentModel.Input(
                    id, "ch_" + prefix + counter, type, "100", "100", "cad", true, false, false));
        }

        void drain(String owner) {
            app.drain(owner);
            model.drain();
        }

        void crash(boolean afterBusiness) {
            String first = model.pending().getFirst().id();
            Map<String, Object> before = row(first);
            var doomed = app.worker("doomed-worker", new WorkerHooks() {
                @Override
                public void afterClaim(String id) {
                    assertThat(id).isEqualTo(first);
                    if (!afterBusiness) throw new ProcessDeath();
                }

                @Override
                public void afterBusiness(String id) {
                    if (afterBusiness) throw new ProcessDeath();
                }
            });
            assertThatThrownBy(doomed::processAvailable).isInstanceOf(ProcessDeath.class);
            assertThat(row(first))
                    .containsEntry("status", "RECEIVED")
                    .containsEntry("result", null)
                    .containsEntry("attempts", 0)
                    .containsEntry("lease_owner", "doomed-worker")
                    .containsEntry("lease_epoch", ((Long) before.get("lease_epoch")) + 1);
            check(); // All business writes (including issues) must have rolled back before recovery.
            app.advance(31);
            drain("recovery-worker");
            assertThat(row(first))
                    .containsEntry("lease_epoch", ((Long) before.get("lease_epoch")) + 2)
                    .containsEntry("lease_owner", null)
                    .containsEntry("lease_until", null);
        }

        Map<String, Object> row(String id) {
            return app.jdbc
                    .sql("SELECT * FROM io.inbox_event WHERE event_id=:id")
                    .param("id", id)
                    .query()
                    .singleRow();
        }

        void check() {
            report.checks++;
            try {
                assertThat(app.audit.check()).as("global INV-1..5/8").isEmpty();
                Map<Integer, Long> expectedBalances = new HashMap<>(baseline);
                expectedBalances.merge(1101, model.balanceDelta(), Long::sum);
                expectedBalances.merge(2101, model.balanceDelta(), Long::sum);
                Map<Integer, Long> balances = app.balances();
                assertThat(balances).as("snapshot + model deltas, all accounts").isEqualTo(expectedBalances);

                Map<String, PaymentModel.Outcome> actualOutcomes = new HashMap<>();
                app.jdbc
                        .sql("SELECT event_id, status, result FROM io.inbox_event WHERE event_id LIKE :prefix")
                        .param("prefix", "evt_" + prefix + "%")
                        .query((rs, n) ->
                                Map.entry(rs.getString(1), new PaymentModel.Outcome(rs.getString(2), rs.getString(3))))
                        .list()
                        .forEach(entry -> actualOutcomes.put(entry.getKey(), entry.getValue()));
                assertThat(actualOutcomes)
                        .as("every event, including pending events")
                        .isEqualTo(model.outcomes);

                Map<String, Long> payments = new HashMap<>();
                app.jdbc
                        .sql(
                                "SELECT processor_payment_ref, amount, status FROM core.payment WHERE processor_payment_ref LIKE :prefix")
                        .param("prefix", "ch_" + prefix + "%")
                        .query((rs, n) -> {
                            assertThat(rs.getString(3)).isEqualTo("CAPTURED");
                            return Map.entry(rs.getString(1), rs.getLong(2));
                        })
                        .list()
                        .forEach(entry -> payments.put(entry.getKey(), entry.getValue()));
                assertThat(payments).isEqualTo(model.payments);
                assertThat(balances.get(2101) - baseline.get(2101))
                        .as("INV-9 P1")
                        .isEqualTo(payments.values().stream()
                                .mapToLong(Long::longValue)
                                .sum());

                Map<String, Long> entries = new HashMap<>();
                app.jdbc
                        .sql(
                                "SELECT source_key, count(*) FROM ledger.journal_entry WHERE source_key LIKE :prefix AND entry_type='PAYMENT_CAPTURED' GROUP BY source_key")
                        .param("prefix", "payment:ch_" + prefix + "%")
                        .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                        .list()
                        .forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
                Map<String, Long> expectedEntries = new HashMap<>();
                model.payments.keySet().forEach(charge -> expectedEntries.put("payment:" + charge + ":captured", 1L));
                assertThat(entries)
                        .as("INV-10 P1: exactly zero or one captured entry per charge")
                        .isEqualTo(expectedEntries);
                assertThat(app.jdbc
                                .sql("SELECT count(*) FROM ledger.journal_entry")
                                .query(Long.class)
                                .single())
                        .isEqualTo(entriesBefore + model.payments.size());

                assertThat(groupCounts("recon.issue", "category", "subject_id")).isEqualTo(model.issueCounts());
                assertThat(groupCounts("io.inbox_conflict", "event_id", "event_id"))
                        .isEqualTo(model.conflicts);
            } catch (AssertionError failure) {
                report.violations++;
                throw failure;
            }
        }

        private Map<String, Long> groupCounts(String table, String key, String subject) {
            Map<String, Long> counts = new HashMap<>();
            app.jdbc
                    .sql("SELECT " + key + ", count(*) FROM " + table + " WHERE " + subject + " LIKE :prefix GROUP BY "
                            + key)
                    .param("prefix", "evt_" + prefix + "%")
                    .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                    .list()
                    .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
            return counts;
        }

        @Override
        public String toString() {
            return "payments=" + model.payments + ", outcomes=" + model.outcomes;
        }
    }

    private static final class ProcessDeath extends Error {}
}
