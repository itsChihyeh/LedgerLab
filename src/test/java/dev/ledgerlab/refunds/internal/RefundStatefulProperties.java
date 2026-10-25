package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.refunds.ProcessorRefundFact;
import dev.ledgerlab.refunds.RefundResult;
import dev.ledgerlab.settlement.PayoutEvidence;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

class RefundStatefulProperties {
    private final List<RefundFixture> fixtures = new ArrayList<>();

    @AfterTry
    void cleanup() {
        fixtures.forEach(RefundFixture::close);
        fixtures.clear();
    }

    @Property(seed = "2026102407")
    void ac_3_7_ac_3_9_actionsMatchIndependentModel(@ForAll("operations") ActionChain<State> chain) {
        chain.withInvariant("refunds, funds, commands and ledger", State::verify)
                .run();
    }

    @Provide
    Arbitrary<ActionChain<State>> operations() {
        return ActionChain.startWith(() -> {
                    var fixture = new RefundFixture();
                    fixtures.add(fixture);
                    return new State(fixture);
                })
                .withAction(new Operations())
                .withMaxTransformations(12);
    }

    enum Kind {
        ACCEPT,
        CANCEL,
        SETTLE,
        RELEASE,
        PENDING,
        SUCCEEDED,
        FAILED,
        TIMEOUT,
        REFUND_BATCH
    }

    static final class Operations implements Action.Independent<State> {
        @Override
        public Arbitrary<Transformer<State>> transformer() {
            return Combinators.combine(
                            Arbitraries.of(Kind.values()),
                            Arbitraries.of(1, 49, 1000, 2000, 5000, 6000, 10000),
                            Arbitraries.integers().between(0, 20))
                    .as((kind, amount, index) -> Transformer.mutate(
                            kind + " " + amount + " " + index, state -> state.apply(kind, amount, index)));
        }
    }

    static final class State {
        final RefundFixture f;
        final RefundReference model = new RefundReference();
        final String payment;
        final List<RefundResult> accepted = new ArrayList<>();
        final JdbcRefundEvidence evidence;
        int batches;
        int refundBatches;

        State(RefundFixture fixture) {
            f = fixture;
            payment = f.s1();
            evidence = new JdbcRefundEvidence(
                    f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        }

        void apply(Kind kind, long amount, int index) {
            f.clock.set(f.clock.instant().plusSeconds(1));
            switch (kind) {
                case REFUND_BATCH -> {
                    if (accepted.isEmpty()) return;
                    int selected = index % accepted.size();
                    if (!model.batchRefund(selected)) return;
                    var refund = accepted.get(selected);
                    String ref = "ch_refund_batch_" + ++refundBatches;
                    f.payment(ref, 20000);
                    var base = f.batchEvidence("po_refund_batch_" + refundBatches, ref, 20000, 100);
                    var baseLines = java.util.Objects.requireNonNull(base.lines());
                    var items = new ArrayList<>(baseLines.items());
                    items.add(new PayoutEvidence.Line(
                            "txn_refund_model_" + selected,
                            "refund",
                            "re_model_" + selected,
                            -refund.amount(),
                            0,
                            -refund.amount(),
                            "cad",
                            f.clock.instant()));
                    var batch = new PayoutEvidence(
                            base.eventId(),
                            base.evidenceType(),
                            base.evidenceId(),
                            base.eventType(),
                            base.batchId(),
                            19900 - refund.amount(),
                            base.currency(),
                            base.automatic(),
                            base.method(),
                            base.reconciliationStatus(),
                            base.arrivalDate(),
                            base.created(),
                            new PayoutEvidence.Lines(
                                    baseLines.fetchId(),
                                    items,
                                    java.util.Map.of(
                                            "re_model_" + selected,
                                            new PayoutEvidence.RefundIdentity(
                                                    refund.refundId(), refund.amount(), "ch_p1"))));
                    f.tx.executeWithoutResult(tx -> f.batches.process(batch));
                }
                case PENDING, SUCCEEDED, FAILED, TIMEOUT -> {
                    if (accepted.isEmpty()) return;
                    int selected = index % accepted.size();
                    String id = accepted.get(selected).refundId();
                    f.tx.executeWithoutResult(tx -> {
                        if (kind == Kind.TIMEOUT) evidence.unknown(id, "model-timeout", f.clock.instant());
                        else
                            evidence.apply(new ProcessorRefundFact(
                                    id,
                                    "re_model_" + selected,
                                    ProcessorRefundFact.Status.valueOf(
                                            kind.name().toLowerCase(java.util.Locale.ROOT)),
                                    null,
                                    ProcessorRefundFact.Source.WEBHOOK,
                                    "model-event",
                                    f.clock.instant()));
                    });
                    model.observe(selected, kind.name());
                }
                case ACCEPT -> {
                    if (model.accept(amount)) accepted.add(f.accept(payment, amount));
                    else RefundServiceTest.error("REFUND_EXCEEDS_REMAINING", () -> f.accept(payment, amount));
                }
                case CANCEL -> {
                    if (accepted.isEmpty()) return;
                    int selected = index % accepted.size();
                    String id = accepted.get(selected).refundId();
                    if (model.cancel(selected)) f.refunds.cancel(id, "model cancellation");
                    else
                        RefundServiceTest.error(
                                "REFUND_NOT_CANCELLABLE", () -> f.refunds.cancel(id, "model cancellation"));
                }
                case SETTLE -> {
                    if (++batches == 1) {
                        // Settle original payment with net 1500 to exercise a blocked large head.
                        f.batch("po_initial", "ch_p1", 10000, 8500);
                        model.settle(1500);
                    } else {
                        String ref = "ch_extra_" + batches;
                        f.payment(ref, 1100);
                        f.batch("po_extra_" + batches, ref, 1100, 100);
                        model.settle(1000);
                    }
                }
                case RELEASE ->
                    f.tx.executeWithoutResult(tx -> {
                        f.ledger.lockFunds();
                        f.queue.release();
                    });
            }
        }

        void verify() {
            f.invariants();
            assertThat(f.balance(2102)).isEqualTo(model.settled - model.reserved);
            assertThat(f.balance(2103)).isEqualTo(model.reserved - model.executed);
            assertThat(f.balance(1103)).isZero();
            assertThat(f.balance(1102)).isEqualTo(model.settled - model.batchedRefunds);
            assertThat(f.balance(2101)).isEqualTo(batches == 0 ? 10000 : 0);
            assertThat(f.balance(1101)).isEqualTo((batches == 0 ? 10000 : 0) - model.executed + model.batchedRefunds);
            assertThat(f.count("core.refund")).isEqualTo(model.refunds.size());
            assertThat(f.count("io.outbound_command"))
                    .isEqualTo(
                            model.refunds.stream().filter(r -> r.commandCreated).count());
            assertThat(f.number("SELECT count(*) FROM ledger.journal_entry WHERE entry_type LIKE 'REFUND_%'"))
                    .isEqualTo(model.refunds.size() + model.cancelled + model.failed + model.successes);
            long lastSeq = 0;
            for (int i = 0; i < accepted.size(); i++) {
                var actual = accepted.get(i);
                assertThat(actual.queueSeq()).isGreaterThan(lastSeq);
                lastSeq = actual.queueSeq();
                assertThat(f.status(actual.refundId())).isEqualTo(model.refunds.get(i).state);
                assertThat(f.jdbc.sql("SELECT batch_id IS NOT NULL FROM core.refund WHERE id=:id")
                                .param("id", actual.refundId())
                                .query(Boolean.class)
                                .single())
                        .isEqualTo(model.refunds.get(i).batched);
                assertThat(f.jdbc.sql("SELECT initial_result->'body'->>'status' FROM core.refund WHERE id=:id")
                                .param("id", actual.refundId())
                                .query(String.class)
                                .single())
                        .isEqualTo(actual.status());
            }
        }
    }
}
