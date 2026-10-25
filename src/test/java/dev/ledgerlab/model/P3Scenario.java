package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.testsupport.RefundAssertions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.Transformer;
import tools.jackson.databind.ObjectMapper;

/** Real API/inbox/outbound drivers; RefundModel alone predicts the business effects. */
final class P3Scenario {
    enum Kind {
        ACCEPT_REFUND,
        CANCEL_REFUND,
        COMMAND,
        REFUND_WEBHOOK,
        REPLAY_REFUND,
        RECOVER_EXPIRED,
        API_REPLAY,
        PROCESSOR_AVAILABLE,
        REFUND_BATCH
    }

    record Operation(Kind kind) implements Action.Dependent<PaymentModelProperties.State> {
        @Override
        public boolean precondition(PaymentModelProperties.State state) {
            return state.p3.eligible(kind);
        }

        @Override
        public Arbitrary<Transformer<PaymentModelProperties.State>> transformer(PaymentModelProperties.State state) {
            return Combinators.combine(
                            Arbitraries.integers().between(0, 99_999_999),
                            Arbitraries.integers().between(0, 7))
                    .as((index, variant) -> PaymentModelProperties.step(
                            kind.name(),
                            kind + " index=" + index + " variant=" + variant,
                            s -> s.p3.execute(kind, index, variant)));
        }
    }

    final PaymentModelProperties.State state;
    final ModelApplication app;
    final RefundModel model;
    final Map<String, String> initialBodies = new LinkedHashMap<>();
    final Map<String, ModelApplication.ApiResponse> initialResponses = new LinkedHashMap<>();
    final List<byte[]> deliveries = new ArrayList<>();
    final Map<String, String> processor = new LinkedHashMap<>();
    int next;

    P3Scenario(PaymentModelProperties.State state) {
        this.state = state;
        app = state.app;
        model = new RefundModel(state.model.payments, state.model.settlement, state.baseline.get(2102));
    }

    List<RefundModel.Refund> waiting() {
        return model.refunds.values().stream()
                .filter(r -> r.status.equals("AWAITING_FUNDS"))
                .toList();
    }

    List<RefundModel.Refund> commands() {
        return model.refunds.values().stream()
                .filter(r -> r.command && !r.commandDone)
                .toList();
    }

    List<RefundModel.Refund> external() {
        return model.refunds.values().stream()
                .filter(r -> r.processorRef != null)
                .toList();
    }

    boolean eligible(Kind kind) {
        return switch (kind) {
            case ACCEPT_REFUND -> !state.model.payments.isEmpty();
            case CANCEL_REFUND -> !waiting().isEmpty();
            case COMMAND, RECOVER_EXPIRED -> !commands().isEmpty();
            case REFUND_WEBHOOK -> !external().isEmpty();
            case REPLAY_REFUND -> !deliveries.isEmpty();
            case API_REPLAY -> !initialBodies.isEmpty();
            case PROCESSOR_AVAILABLE -> external().stream().anyMatch(r -> r.status.equals("SUBMITTED"));
            case REFUND_BATCH -> batchCandidate() != null;
        };
    }

    void execute(Kind kind, int index, int variant) {
        state.drain("before-p3");
        switch (kind) {
            case REFUND_BATCH -> batch();
            case ACCEPT_REFUND -> {
                String payment =
                        pick(state.model.payments.keySet().stream().sorted().toList(), index);
                long remaining = model.remaining(payment);
                long amount = switch (variant % 4) {
                    case 0 -> Math.addExact(remaining, 1);
                    case 1 -> remaining == 0 ? 1 : remaining;
                    case 2 -> 1;
                    default -> remaining == 0 ? 1 : Math.addExact(index % remaining, 1);
                };
                accept(payment, amount);
            }
            case CANCEL_REFUND -> cancel(pick(waiting(), index));
            case COMMAND -> command(variant);
            case RECOVER_EXPIRED -> {
                app.advance(25 * 3600);
                command(0);
            }
            case REFUND_WEBHOOK -> {
                var r = pick(external(), index);
                // Late pending after a terminal result is legitimate out-of-order evidence.
                String status = variant % 3 == 0
                        ? "pending"
                        : List.of("SUCCEEDED", "FAILED").contains(r.status)
                                ? r.status.toLowerCase(java.util.Locale.ROOT)
                                : variant % 3 == 1 ? "succeeded" : "failed";
                webhook(r, status, variant % 3);
            }
            case PROCESSOR_AVAILABLE ->
                webhook(
                        pick(
                                external().stream()
                                        .filter(r -> r.status.equals("SUBMITTED"))
                                        .toList(),
                                index),
                        "succeeded",
                        1);
            case REPLAY_REFUND -> {
                app.advance(1);
                app.deliverRaw(pick(deliveries, index));
                app.drain("refund-duplicate");
            }
            case API_REPLAY -> {
                String key = pick(new ArrayList<>(initialBodies.keySet()), index);
                if (variant % 2 == 0) {
                    app.advance(25 * 3600);
                    app.expireApiKeys();
                }
                var response = app.api("/api/refunds", variant % 2 == 0 ? key + "-new" : key, initialBodies.get(key));
                assertThat(response).isEqualTo(initialResponses.get(key));
            }
        }
    }

    private RefundModel.Refund batchCandidate() {
        return external().stream()
                .filter(r -> r.batch == null
                        && List.of("SUBMITTED", "UNKNOWN", "SUCCEEDED").contains(r.status)
                        && state.p2.availablePayments().stream()
                                .anyMatch(p -> Math.subtractExact(
                                                state.model.payments.get(p),
                                                SettlementModel.fee(state.model.payments.get(p)))
                                        > r.amount))
                .findFirst()
                .orElse(null);
    }

    void batch() {
        var r = java.util.Objects.requireNonNull(batchCandidate());
        String payment = state.p2.availablePayments().stream()
                .filter(p -> Math.subtractExact(
                                state.model.payments.get(p), SettlementModel.fee(state.model.payments.get(p)))
                        > r.amount)
                .findFirst()
                .orElseThrow();
        long gross = state.model.payments.get(payment);
        String id = "po_" + state.prefix + "refundbatch" + ++next;
        var batch = new SettlementModel.Batch(
                id,
                List.of(
                        new SettlementModel.Line(
                                "txn_" + id + "p", "charge", payment, gross, SettlementModel.fee(gross)),
                        new SettlementModel.Line(
                                "txn_" + id + "r", "refund", r.processorRef, Math.negateExact(r.amount), 0)),
                app.clock.instant());
        state.model.settlement.batches.put(id, batch);
        model.observe(r, "succeeded", r.processorRef);
        r.batch = id;
        processor.put(r.id, "succeeded");
        state.p2.install(batch, false);
        state.p2.event(batch, "payout.reconciliation_completed");
    }

    void accept(String payment, long amount) {
        app.advance(1);
        String ref = state.prefix + "refund" + ++next;
        String body =
                "{\"payment_ref\":\"%s\",\"merchant_refund_ref\":\"%s\",\"amount\":%d}".formatted(payment, ref, amount);
        long remaining = model.remaining(payment);
        var response = app.api("/api/refunds", ref, body);
        if (amount > 99_999_999) {
            assertThat(response.status()).isEqualTo(400);
        } else if (state.model.voided.contains(payment)) {
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.body()).contains("PAYMENT_NOT_REFUNDABLE");
        } else if (amount > remaining) {
            assertThat(response.status()).as(response.body()).isEqualTo(409);
            assertThat(response.body()).contains("REFUND_EXCEEDS_REMAINING");
            PaymentModelProperties.feature(remaining == 0 ? "REFUND_ZERO_REMAINING" : "REFUND_EXCESS");
        } else {
            assertThat(response.status()).as(response.body()).isEqualTo(201);
            var json = new ObjectMapper().readTree(response.body());
            String id = json.path("refund_id").asString();
            model.accept(id, payment, ref, amount);
            PaymentModelProperties.feature("ACCEPTED_" + model.refunds.get(id).status);
            assertThat(json.path("status").asString()).isEqualTo(model.refunds.get(id).status);
            initialBodies.put(ref, body);
            initialResponses.put(ref, response);
        }
    }

    void cancel(RefundModel.Refund r) {
        assertThat(app.api("/api/refunds/" + r.id + "/cancel", "cancel-" + r.id, "{\"reason\":\"model cancellation\"}")
                        .status())
                .isEqualTo(200);
        model.cancel(r);
    }

    void command(int variant) {
        app.advance(3601); // Exceeds the configured retry cap; no wall-clock sleep.
        var pending = commands();
        // Read identities/order only, and assert the selected set against the oracle before execution.
        var ids = app.jdbc
                .sql("SELECT subject_id FROM io.outbound_command WHERE status<>'DONE' ORDER BY created_at,id")
                .query(String.class)
                .list();
        assertThat(ids)
                .containsExactlyInAnyOrderElementsOf(
                        pending.stream().map(r -> r.id).toList());
        var observations = new LinkedHashMap<RefundModel.Refund, String>();
        for (String id : ids) {
            var r = model.refunds.get(id);
            String known = processor.get(id);
            if (r.sent) {
                if (variant == 7) {
                    app.refunds.replies.add(new RefundReplay.Reply(
                            r.processorRef == null ? "LIST_ALL" : "RETRIEVE",
                            503,
                            "{\"error\":\"PAGE_TIMEOUT\"}",
                            "PAGE_TIMEOUT"));
                    observations.put(r, "timeout");
                    PaymentModelProperties.feature("QUERY_INCOMPLETE");
                    continue;
                }
                app.refunds.replies.add(new RefundReplay.Reply(
                        r.processorRef == null ? "LIST_ALL" : "RETRIEVE",
                        200,
                        r.processorRef == null ? pages(r, known) : receipt(r, known),
                        null));
                if (known != null) {
                    observations.put(r, known);
                    continue;
                }
            }
            String result = switch (variant) {
                case 0 -> "succeeded";
                case 1 -> "pending";
                case 2, 3 -> "failed";
                default -> "timeout";
            };
            ScenarioLog.Webhook early = null;
            if (variant == 6) {
                String event = state.nextEvent();
                byte[] raw = eventBody(event, r, "succeeded", 0);
                early = new ScenarioLog.Webhook(
                        app.clock.instant(),
                        raw,
                        dev.ledgerlab.testsupport.StripeSignatures.sign(
                                raw, app.clock.instant().getEpochSecond()));
                deliveries.add(raw);
                model.outcomes.put(
                        event, new PaymentModel.Outcome("PROCESSED", model.observe(r, "succeeded", externalRef(r))));
                processor.put(id, "succeeded");
                PaymentModelProperties.feature("WEBHOOK_BEFORE_RESPONSE");
            }
            if (variant == 5) processor.put(id, "succeeded"); // Created, but response was lost.
            else if (variant < 3) processor.put(id, result);
            app.refunds.replies.add(new RefundReplay.Reply(
                    "CREATE",
                    variant >= 4 ? null : variant == 3 ? 400 : 200,
                    variant >= 4 ? null : variant == 3 ? "{\"error\":{\"code\":\"declined\"}}" : receipt(r, result),
                    variant >= 4 ? "TIMEOUT" : null,
                    early));
            PaymentModelProperties.feature("COMMAND_RESULT_" + variant);
            observations.put(r, result);
        }
        app.outbound();
        assertThat(app.refunds.replies).isEmpty();
        observations.forEach((r, result) -> {
            if (!r.sent) r.firstSent = app.clock.instant();
            r.sent = true;
            r.commandDone = !result.equals("timeout");
            model.observe(r, result, processor.containsKey(r.id) && !result.equals("timeout") ? externalRef(r) : null);
        });
    }

    static String externalRef(RefundModel.Refund r) {
        return "re_" + r.merchantRef;
    }

    String receipt(RefundModel.Refund r, String status) {
        return """
                {"id":"%s","object":"refund","charge":"%s","amount":%d,"currency":"cad",
                "status":"%s","metadata":{"ledgerlab_refund_id":"%s"},"created":%d}
                """.formatted(
                        externalRef(r),
                        r.payment,
                        r.amount,
                        status,
                        r.id,
                        app.clock.instant().getEpochSecond());
    }

    private String pages(RefundModel.Refund r, String status) {
        String other = "{\"id\":\"re_unrelated\",\"metadata\":{\"ledgerlab_refund_id\":\"rfd_unrelated\"}}";
        return "[{\"data\":[" + other + "],\"has_more\":true},{\"data\":[" + (status == null ? "" : receipt(r, status))
                + "],\"has_more\":false}]";
    }

    void webhook(RefundModel.Refund r, String status, int type) {
        String event = state.nextEvent();
        byte[] raw = eventBody(event, r, status, type);
        deliveries.add(raw);
        app.deliverRaw(raw);
        app.drain("refund-webhook");
        if (!List.of("SUCCEEDED", "FAILED").contains(r.status) || !status.equals("pending"))
            processor.put(r.id, status);
        model.outcomes.put(event, new PaymentModel.Outcome("PROCESSED", model.observe(r, status, externalRef(r))));
    }

    private byte[] eventBody(String event, RefundModel.Refund r, String status, int type) {
        String name =
                List.of("refund.created", "refund.updated", "refund.failed").get(type);
        return """
                {"id":"%s","type":"%s","api_version":"2026-09-30.endive","livemode":false,
                "created":%d,"data":{"object":%s}}
                """.formatted(event, name, app.clock.instant().getEpochSecond(), receipt(r, status))
                .getBytes(StandardCharsets.UTF_8);
    }

    void finish() {
        while (!waiting().isEmpty()) {
            cancel(waiting().getFirst());
            state.check();
        }
        for (int i = 0; i < 30 && !commands().isEmpty(); i++) {
            command(0);
            state.check();
        }
        for (var r : external())
            if (r.status.equals("SUBMITTED")) {
                webhook(r, "succeeded", 1);
                state.check();
            }
        assertThat(commands()).isEmpty();
    }

    void check() {
        model.release();
        RefundAssertions.refundInvariants(app.jdbc);
        for (var r : model.refunds.values()) {
            var refundRow = app.jdbc
                    .sql("SELECT amount,processor_refund_ref,batch_id FROM core.refund WHERE id=:id")
                    .param("id", r.id)
                    .query()
                    .singleRow();
            assertThat(refundRow)
                    .containsEntry("amount", r.amount)
                    .containsEntry("processor_refund_ref", r.processorRef)
                    .containsEntry("batch_id", r.batch);
            assertThat(app.jdbc
                            .sql("SELECT status FROM core.refund WHERE id=:id")
                            .param("id", r.id)
                            .query(String.class)
                            .single())
                    .as("refund %s", r.merchantRef)
                    .isEqualTo(r.status);
            assertThat(app.jdbc
                            .sql("SELECT count(*) FROM io.outbound_command WHERE subject_id=:id")
                            .param("id", r.id)
                            .query(Long.class)
                            .single())
                    .isEqualTo(r.command ? 1 : 0);
            if (r.command)
                assertThat(app.jdbc
                                .sql("SELECT status FROM io.outbound_command WHERE subject_id=:id")
                                .param("id", r.id)
                                .query(String.class)
                                .single())
                        .isEqualTo(r.commandDone ? "DONE" : r.sent ? "UNKNOWN" : "PENDING");
            if (r.sent)
                assertThat(app.jdbc
                                .sql("SELECT first_sent_at FROM io.outbound_command WHERE subject_id=:id")
                                .param("id", r.id)
                                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                                .single())
                        .as("immutable first send across expired recovery")
                        .isEqualTo(r.firstSent);
        }
        for (var entry : model.entries.entrySet()) {
            var actual = app.jdbc
                    .sql("""
                        SELECT e.entry_type,p.account_code,p.side,p.amount FROM ledger.journal_entry e
                        JOIN ledger.posting p ON p.entry_id=e.id WHERE e.source_key=:key
                        """)
                    .param("key", entry.getKey())
                    .query((rs, n) -> {
                        assertThat(rs.getString(1)).isEqualTo(entry.getValue().type());
                        return new SettlementModel.Posting(rs.getInt(2), rs.getString(3), rs.getLong(4));
                    })
                    .list();
            assertThat(actual)
                    .containsExactlyInAnyOrderElementsOf(entry.getValue().postings());
        }
    }

    private static <T> T pick(List<T> items, int index) {
        return items.get(index % items.size());
    }
}
