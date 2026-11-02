package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.Transformer;
import tools.jackson.databind.ObjectMapper;

/** Drives real HTTP APIs, outbound workers and bank import; the oracle predicts every effect. */
final class P5Scenario {
    enum Kind {
        PREPARE_PAYOUT,
        RESERVE_PAYOUT,
        PAYOUT_RECEIPT,
        PAYOUT_DEBIT,
        PAYOUT_RETURN,
        PAYOUT_CONFLICT,
        CONFIRM_FAILED_PAYOUT_PAID,
        QUERY_PAYOUT,
        REPLAY_PAYOUT_FILE
    }

    record Operation(Kind kind) implements Action.Dependent<PaymentModelProperties.State> {
        public boolean precondition(PaymentModelProperties.State s) {
            return s.p5.eligible(kind);
        }

        public Arbitrary<Transformer<PaymentModelProperties.State>> transformer(PaymentModelProperties.State s) {
            return Combinators.combine(
                            Arbitraries.integers().between(0, 999),
                            Arbitraries.integers().between(0, 5))
                    .as((index, variant) -> PaymentModelProperties.step(
                            kind.name(),
                            kind + " " + index + "/" + variant,
                            state -> state.p5.execute(kind, index, variant)));
        }
    }

    final PaymentModelProperties.State state;
    final ModelApplication app;
    final PayoutModel model;
    final ObjectMapper mapper = new ObjectMapper();
    final List<byte[]> files = new ArrayList<>();
    int next;

    P5Scenario(PaymentModelProperties.State state) {
        this.state = state;
        app = state.app;
        model = new PayoutModel(state.p4.model);
        state.p3.model.otherAvailable = () -> Math.addExact(
                model.journal.deltas().getOrDefault(2102, 0L),
                state.p4.model.journal.deltas().getOrDefault(2102, 0L));
    }

    List<PayoutModel.Payout> payouts(String... statuses) {
        return model.payouts.values().stream()
                .filter(p -> List.of(statuses).contains(p.status))
                .toList();
    }

    List<PayoutModel.Bank> conflicts() {
        return model.banks.values().stream()
                .filter(b -> b.debit
                        && b.match.equals("UNMATCHED_NO_POSTING")
                        && model.payouts.get(b.payout).status.equals("FAILED"))
                .toList();
    }

    boolean eligible(Kind k) {
        return switch (k) {
            case PREPARE_PAYOUT, RESERVE_PAYOUT -> true;
            case PAYOUT_RECEIPT -> model.commands.values().stream().anyMatch(c -> !c.done);
            case PAYOUT_DEBIT -> !payouts("RESERVED", "UNKNOWN", "ACCEPTED").isEmpty();
            case PAYOUT_RETURN -> !payouts("PAID").isEmpty();
            case PAYOUT_CONFLICT -> !payouts("FAILED").isEmpty();
            case CONFIRM_FAILED_PAYOUT_PAID -> !conflicts().isEmpty();
            case QUERY_PAYOUT -> !model.payouts.isEmpty();
            case REPLAY_PAYOUT_FILE -> !files.isEmpty();
        };
    }

    void execute(Kind kind, int index, int variant) {
        state.drain("before-p5");
        switch (kind) {
            case PREPARE_PAYOUT -> prepare(variant % 2 == 0);
            case RESERVE_PAYOUT -> {
                long maximum = Math.min(99_999_999, Math.min(model.balances().get(2102), model.freeCash()));
                long amount = switch (variant) {
                    case 0 -> 0;
                    case 1 -> 100_000_000;
                    case 2 ->
                        Math.min(
                                99_999_999,
                                Math.max(1, Math.addExact(model.balances().get(2102), 1)));
                    case 3 -> Math.min(99_999_999, Math.max(1, Math.addExact(model.freeCash(), 1)));
                    case 4 -> 1;
                    default -> Math.max(1, maximum);
                };
                reserve(amount);
            }
            case PAYOUT_RECEIPT -> receipts(variant % 4);
            case PAYOUT_DEBIT -> bank(pick(payouts("RESERVED", "UNKNOWN", "ACCEPTED"), index), true);
            case PAYOUT_RETURN -> bank(pick(payouts("PAID"), index), false);
            case PAYOUT_CONFLICT -> bank(pick(payouts("FAILED"), index), true);
            case CONFIRM_FAILED_PAYOUT_PAID -> correct(pick(conflicts(), index));
            case QUERY_PAYOUT -> query(pick(new ArrayList<>(model.payouts.values()), index));
            case REPLAY_PAYOUT_FILE -> app.bank(pick(files, index));
        }
    }

    void prepare(boolean banked) {
        String alias = "p5fund" + ++next;
        P3ScenarioTest.charge(state, alias, 10000);
        String id = "po_" + state.prefix + alias;
        var batch = new SettlementModel.Batch(
                id,
                List.of(new SettlementModel.Line("txn_" + id, "charge", "ch_" + state.prefix + alias, 10000, 320)),
                app.clock.instant());
        state.model.settlement.batches.put(id, batch);
        state.p2.install(batch, false);
        state.p2.event(batch, "payout.reconciliation_completed");
        state.check();
        if (banked) {
            state.p4.credit(batch, 9680);
            state.check();
        }
        PaymentModelProperties.feature(banked ? "P5_FUNDED" : "P5_CASH_IN_TRANSIT");
    }

    PayoutModel.Payout reserve(long amount) {
        app.advance(1);
        String ref = state.prefix + "payout" + ++next;
        String error = model.rejection(amount);
        var before = state.p4.databaseRows();
        var response = app.api(
                "/api/payouts", ref, mapper.writeValueAsString(Map.of("amount", amount, "merchantPayoutRef", ref)));
        var json = mapper.readTree(response.body());
        if (error != null) {
            assertThat(response.status()).as(response.body()).isEqualTo(error.equals("INVALID_AMOUNT") ? 400 : 409);
            assertThat(json.path(error.equals("INVALID_AMOUNT") ? "code" : "reason")
                            .asString())
                    .isEqualTo(error);
            assertThat(state.p4.databaseRows()).as("payout 4xx full snapshot").isEqualTo(before);
            PaymentModelProperties.feature("P5_REJECT_" + error);
            return null;
        }
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        String id = json.path("payoutId").asString();
        model.reserve(id, amount);
        String command = app.jdbc
                .sql("SELECT id FROM io.outbound_command WHERE subject_id=? AND kind='SUBMIT_PAYOUT'")
                .param(id)
                .query(String.class)
                .single();
        model.commands.put(command, new PayoutModel.Command(command, id, "SUBMIT_PAYOUT"));
        PaymentModelProperties.feature("P5_RESERVED");
        return model.payouts.get(id);
    }

    void receipts(int variant) {
        app.advance(3601);
        var ids = app.jdbc
                .sql(
                        "SELECT id FROM io.outbound_command WHERE kind IN ('SUBMIT_PAYOUT','QUERY_PAYOUT') AND status<>'DONE' ORDER BY created_at,id")
                .query(String.class)
                .list();
        assertThat(ids)
                .containsExactlyInAnyOrderElementsOf(model.commands.values().stream()
                        .filter(c -> !c.done)
                        .map(c -> c.id)
                        .toList());
        String outcome = List.of("ACCEPTED", "REJECTED", "TIMEOUT", "DROP").get(variant);
        for (String id : ids) {
            var c = model.commands.get(id);
            // A lost response means the bank retained the request. A plain timeout permits a 404 then resend.
            String operation = c.kind.equals("QUERY_PAYOUT") || c.sent ? "QUERY" : "SUBMIT";
            if (c.sent && !c.responseLost && c.kind.equals("SUBMIT_PAYOUT") && variant == 0) {
                app.payouts.replies.add(new PayoutReplay.Reply("QUERY", c.payout, "NOT_FOUND"));
                operation = "SUBMIT";
            }
            app.payouts.replies.add(new PayoutReplay.Reply(operation, c.payout, outcome));
        }
        app.payoutOutbound();
        assertThat(app.payouts.replies).isEmpty();
        ids.forEach(id -> model.receipt(model.commands.get(id), outcome));
        PaymentModelProperties.feature("P5_RECEIPT_" + outcome);
    }

    void query(PayoutModel.Payout p) {
        var before = state.p4.databaseRows();
        var response = app.api("/api/payouts/" + p.id + "/query", "operator", "{}");
        if (!List.of("UNKNOWN", "ACCEPTED", "SUBMITTED").contains(p.status)) {
            assertThat(response.status()).as(response.body()).isEqualTo(409);
            assertThat(response.body()).contains("PAYOUT_NOT_QUERYABLE");
            assertThat(state.p4.databaseRows()).isEqualTo(before);
            PaymentModelProperties.feature("P5_QUERY_REJECTED");
        } else {
            assertThat(response.status()).as(response.body()).isEqualTo(202);
            String id = app.jdbc
                    .sql(
                            "SELECT id FROM io.outbound_command WHERE subject_id=? AND kind='QUERY_PAYOUT' AND status<>'DONE'")
                    .param(p.id)
                    .query(String.class)
                    .single();
            model.commands.putIfAbsent(id, new PayoutModel.Command(id, p.id, "QUERY_PAYOUT"));
            PaymentModelProperties.feature("P5_QUERY_ACCEPTED");
        }
    }

    void bank(PayoutModel.Payout p, boolean debit) {
        String id = "bt_" + state.prefix + "p5" + ++next;
        String date =
                app.clock.instant().atZone(SettlementModel.ZONE).toLocalDate().toString();
        byte[] raw = ("H,BANK_STATEMENT,SIM-CAD-001,%s,%s,%d,CAD\r\n"
                        + "D,%s,%s,%d,CAD,BOOKED,%s,%s,%s,RETURN ACCOUNT_CLOSED\r\n"
                        + "T,1,%d,%d,%d,%d\r\n")
                .formatted(
                        date,
                        date,
                        10000 + next,
                        id,
                        debit ? "DEBIT" : "CREDIT",
                        p.amount,
                        p.id,
                        date,
                        date,
                        debit ? 0 : p.amount,
                        debit ? p.amount : 0,
                        debit ? p.amount : 0,
                        debit ? 0 : p.amount)
                .getBytes(StandardCharsets.UTF_8);
        files.add(raw);
        app.bank(raw);
        model.bank(id, p, debit);
        PaymentModelProperties.feature("P5_BANK_" + model.banks.get(id).match);
    }

    void correct(PayoutModel.Bank bank) {
        var p = model.payouts.get(bank.payout);
        var body = state.p4.request("CONFIRM_FAILED_PAYOUT_PAID", "bank_txn", bank.id);
        body.put("payoutId", p.id);
        body.put("bankTxnId", bank.id);
        if (model.balances().get(1103) < p.amount) {
            state.p4.rejected("/api/corrections", body, 409, "CASH_WOULD_GO_NEGATIVE");
            return;
        }
        String failed = state.p4.entryId("payout:" + p.id + ":failed");
        String id = state.p4.submit(body);
        model.correct(p, bank, id, failed);
        PaymentModelProperties.feature("P5_CORRECTION_SUCCESS");
    }

    void finish() {
        if (model.commands.values().stream().anyMatch(c -> !c.done)) {
            receipts(0);
            state.check();
        }
    }

    void check() {
        assertThat(app.jdbc
                        .sql("SELECT count(*) FROM core.payout")
                        .query(Long.class)
                        .single())
                .isEqualTo(model.payouts.size());
        for (var p : model.payouts.values()) {
            assertThat(app.jdbc
                            .sql("SELECT amount,status,bank_txn_id,return_bank_txn_id FROM core.payout WHERE id=?")
                            .param(p.id)
                            .query()
                            .singleRow())
                    .containsEntry("amount", p.amount)
                    .containsEntry("status", p.status)
                    .containsEntry("bank_txn_id", p.debit)
                    .containsEntry("return_bank_txn_id", p.returned);
            assertThat(app.jdbc
                            .sql("SELECT to_status FROM core.payout_status_history WHERE payout_id=? ORDER BY id")
                            .param(p.id)
                            .query(String.class)
                            .list())
                    .isEqualTo(p.history);
        }
        for (var c : model.commands.values())
            assertThat(app.jdbc
                            .sql("SELECT status FROM io.outbound_command WHERE id=?")
                            .param(c.id)
                            .query(String.class)
                            .single())
                    .isEqualTo(c.done ? "DONE" : c.sent ? "UNKNOWN" : "PENDING");
        for (var b : model.banks.values())
            assertThat(app.jdbc
                            .sql("SELECT match_status,matched_payout_id FROM recon.bank_line WHERE bank_txn_id=?")
                            .param(b.id)
                            .query()
                            .singleRow())
                    .containsEntry("match_status", b.match)
                    .containsEntry("matched_payout_id", b.payout);
        for (var e : model.journal.entries.entrySet()) {
            var actual = app.jdbc
                    .sql(
                            "SELECT e.entry_type,p.account_code,p.side,p.amount FROM ledger.journal_entry e JOIN ledger.posting p ON p.entry_id=e.id WHERE e.source_key=?")
                    .param(e.getKey())
                    .query((rs, n) -> {
                        assertThat(rs.getString(1)).isEqualTo(e.getValue().type());
                        return new SettlementModel.Posting(rs.getInt(2), rs.getString(3), rs.getLong(4));
                    })
                    .list();
            assertThat(actual).containsExactlyInAnyOrderElementsOf(e.getValue().postings());
        }
        long held = model.payouts.values().stream()
                .filter(PayoutModel.Payout::reserved)
                .map(p -> p.amount)
                .reduce(0L, Math::addExact);
        assertThat(app.balances().get(2104))
                .as("INV-9 independent payout obligations")
                .isEqualTo(held);
        assertThat(app.jdbc
                        .sql(
                                "SELECT balance=(SELECT coalesce(sum(amount),0) FROM core.payout WHERE status IN ('RESERVED','SUBMITTED','UNKNOWN','ACCEPTED')) FROM ledger.account WHERE code=2104")
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    private static <T> T pick(List<T> values, int index) {
        return values.get(index % values.size());
    }
}
