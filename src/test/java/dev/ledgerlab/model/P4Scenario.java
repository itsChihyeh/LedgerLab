package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.Transformer;
import tools.jackson.databind.ObjectMapper;

/** External operator inputs; all eligibility and effects are predicted by the JDK-only oracle. */
final class P4Scenario {
    enum Kind {
        PREPARE_CORRECTION,
        VOID_CAPTURE,
        RESTATE_CAPTURE_AMOUNT,
        APPLY_UNAPPLIED_CASH,
        RESTORE_FAILED_PAYOUT,
        RETURN_UNAPPLIED_CASH,
        RECHECK_HELD,
        INVALID_CORRECTION
    }

    record Operation(Kind kind) implements Action.Dependent<PaymentModelProperties.State> {
        @Override
        public boolean precondition(PaymentModelProperties.State state) {
            return state.p4.eligible(kind);
        }

        @Override
        public Arbitrary<Transformer<PaymentModelProperties.State>> transformer(PaymentModelProperties.State state) {
            return Combinators.combine(
                            Arbitraries.integers().between(0, 9999),
                            Arbitraries.integers().between(0, 7))
                    .as((index, variant) -> PaymentModelProperties.step(
                            kind.name(), kind + " " + index + ":" + variant, s -> s.p4.execute(kind, index, variant)));
        }
    }

    final PaymentModelProperties.State state;
    final ModelApplication app;
    final CorrectionModel model;
    private final ObjectMapper mapper = new ObjectMapper();
    private int next;

    P4Scenario(PaymentModelProperties.State state) {
        this.state = state;
        app = state.app;
        model = new CorrectionModel(state.model, state.p3.model);
    }

    List<String> captures(boolean voiding) {
        return state.model.payments.keySet().stream()
                .sorted()
                .filter(p -> !state.model.voided.contains(p) && !model.settlement.paymentBatches.containsKey(p))
                .filter(p -> voiding || state.model.payments.get(p) < 99_999_999 || model.refunds.remaining(p) > 0)
                .filter(p -> !voiding || model.refunds.remaining(p) == state.model.payments.get(p))
                .toList();
    }

    List<SettlementModel.Bank> unapplied() {
        return model.settlement.banks.values().stream()
                .filter(b -> "UNAPPLIED".equals(b.match))
                .toList();
    }

    List<SettlementModel.Bank> credits(SettlementModel.Batch batch) {
        return unapplied().stream()
                .filter(b -> b.input.reference().equals(batch.id))
                .toList();
    }

    List<SettlementModel.Batch> bankCandidates(boolean restore) {
        return model.settlement.batches.values().stream()
                .filter(b -> !credits(b).isEmpty() && credits(b).size() <= (restore ? 1 : 10))
                .filter(b -> model.bankError(b, credits(b), restore) == null)
                .toList();
    }

    List<SettlementModel.Batch> held() {
        return model.settlement.batches.values().stream()
                .filter(b -> b.status.equals("HELD"))
                .toList();
    }

    boolean eligible(Kind kind) {
        return switch (kind) {
            case PREPARE_CORRECTION -> true;
            case VOID_CAPTURE -> !captures(true).isEmpty();
            case RESTATE_CAPTURE_AMOUNT -> !captures(false).isEmpty();
            case APPLY_UNAPPLIED_CASH -> !bankCandidates(false).isEmpty();
            case RESTORE_FAILED_PAYOUT -> !bankCandidates(true).isEmpty();
            case RETURN_UNAPPLIED_CASH -> !unapplied().isEmpty();
            case RECHECK_HELD -> !held().isEmpty();
            case INVALID_CORRECTION -> !state.model.payments.isEmpty();
        };
    }

    void execute(Kind kind, int index, int variant) {
        state.drain("before-p4");
        switch (kind) {
            case PREPARE_CORRECTION -> prepare(variant % 5);
            case VOID_CAPTURE -> capture(pick(captures(true), index), null);
            case RESTATE_CAPTURE_AMOUNT -> {
                String payment = pick(captures(false), index);
                long old = state.model.payments.get(payment);
                long amount = nextAmount(payment, index, variant);
                capture(payment, amount);
                state.check();
                if (variant % 2 == 0 && captures(false).contains(payment)) {
                    capture(payment, old);
                    PaymentModelProperties.feature("CONSECUTIVE_RESTATEMENT");
                }
            }
            case APPLY_UNAPPLIED_CASH, RESTORE_FAILED_PAYOUT -> {
                boolean restore = kind == Kind.RESTORE_FAILED_PAYOUT;
                var batch = pick(bankCandidates(restore), index);
                bankCorrection(batch, credits(batch), restore);
            }
            case RETURN_UNAPPLIED_CASH -> returnCash(pick(unapplied(), index), false);
            case RECHECK_HELD -> recheck(pick(held(), index));
            case INVALID_CORRECTION -> invalid(index, variant);
        }
    }

    private long nextAmount(String payment, int index, int variant) {
        long old = state.model.payments.get(payment);
        long reserved = Math.subtractExact(old, model.refunds.remaining(payment));
        long candidate = switch (variant % 4) {
            case 1 ->
                held().stream()
                        .flatMap(b -> b.lines.stream())
                        .filter(l -> l.reference().equals(payment))
                        .mapToLong(SettlementModel.Line::gross)
                        .findFirst()
                        .orElse(old);
            case 2 -> Math.max(50, reserved);
            case 3 -> Math.addExact(50, index);
            default -> old;
        };
        if (candidate == old || candidate < reserved)
            return old == 99_999_999 ? Math.subtractExact(old, 1) : Math.addExact(old, 1);
        return candidate;
    }

    // Real inputs create useful states, avoiding sequences consisting mostly of rejected actions.
    // 0: HELD mismatch; 1: split deposit; 2: failed payout + cash; 3: expired early cash; 4: unassigned capture.
    SettlementModel.Batch prepare(int variant) {
        String suffix = "p4" + ++next;
        P3ScenarioTest.charge(state, suffix, variant == 0 ? 4500 : 10000);
        if (variant == 4) return null;
        String payment = "ch_" + state.prefix + suffix;
        long gross = variant == 0 ? 5400 : 10000;
        var batch = new SettlementModel.Batch(
                "po_" + state.prefix + suffix,
                List.of(new SettlementModel.Line(
                        "txn_" + state.prefix + suffix, "charge", payment, gross, SettlementModel.fee(gross))),
                app.clock.instant());
        model.settlement.batches.put(batch.id, batch);
        state.p2.install(batch, false);
        state.p2.event(batch, "payout.created");
        state.check();
        if (variant == 3) {
            credit(batch, batch.net);
            state.check();
            state.p2.finish();
            state.check();
        }
        state.p2.event(batch, "payout.reconciliation_completed");
        state.check();
        if (variant == 1) {
            credit(batch, 5000);
            state.check();
            credit(batch, Math.subtractExact(batch.net, 5000));
        } else if (variant == 2) {
            state.p2.event(batch, "payout.failed");
            state.check();
            credit(batch, batch.net);
        }
        state.check();
        return batch;
    }

    void credit(SettlementModel.Batch batch, long amount) {
        state.p2.bank(new SettlementModel.BankInput(
                "bank_" + state.prefix + "p4" + ++next,
                batch.id,
                amount,
                "BOOKED",
                app.clock.instant().atZone(SettlementModel.ZONE).toLocalDate()));
    }

    String entryId(String sourceKey) {
        return app.jdbc
                .sql("SELECT id FROM ledger.journal_entry WHERE source_key=?")
                .param(sourceKey)
                .query(String.class)
                .single();
    }

    Map<String, Object> request(String type, String evidenceType, String evidenceId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("reasonCode", "OTHER");
        body.put("reasonNote", "Synthetic model operator evidence");
        body.put("requestedBy", "model-operator");
        body.put("evidence", List.of(Map.of("type", evidenceType, "id", evidenceId)));
        return body;
    }

    Map<String, Object> captureRequest(String payment, String target, Long amount) {
        String event = state.model.events.values().stream()
                .map(PaymentModel.Received::input)
                .filter(i -> i.charge().equals(payment))
                .findFirst()
                .orElseThrow()
                .id();
        var body = request(amount == null ? "VOID_CAPTURE" : "RESTATE_CAPTURE_AMOUNT", "inbox_event", event);
        body.put("originalEntryId", entryId(target));
        if (amount != null) body.put("newAmount", amount);
        return body;
    }

    void capture(String payment, Long amount) {
        String target = model.current(payment);
        String targetId = entryId(target);
        var body = captureRequest(payment, target, amount);
        String error = model.captureError(payment, target, amount);
        if (error != null) {
            rejected("/api/corrections", body, error.equals("INVALID_REQUEST") ? 400 : 409, error);
            return;
        }
        String id = submit(body);
        var beforeHeld = held();
        model.capture(payment, target, targetId, amount, id, app.clock.instant());
        beforeHeld.stream()
                .filter(b -> !b.status.equals("HELD"))
                .forEach(b -> PaymentModelProperties.feature("AUTO_RECHECK_" + b.status));
        PaymentModelProperties.feature(amount == null ? "VOID_SUCCESS" : "RESTATE_SUCCESS");
    }

    Map<String, Object> bankRequest(SettlementModel.Batch batch, List<SettlementModel.Bank> banks, boolean restore) {
        var body = request(
                restore ? "RESTORE_FAILED_PAYOUT" : "APPLY_UNAPPLIED_CASH",
                "bank_txn",
                banks.getFirst().input.id());
        body.put("batchId", batch.id);
        if (restore) body.put("bankTxnId", banks.getFirst().input.id());
        else body.put("bankTxnIds", banks.stream().map(b -> b.input.id()).toList());
        return body;
    }

    void bankCorrection(SettlementModel.Batch batch, List<SettlementModel.Bank> banks, boolean restore) {
        var body = bankRequest(batch, banks, restore);
        String error = model.bankError(batch, banks, restore);
        if (error != null) {
            rejected("/api/corrections", body, error.equals("BATCH_NOT_FOUND") ? 404 : 409, error);
            return;
        }
        String failureId = restore ? entryId("batch:" + batch.id + ":payout_failed") : null;
        String id = submit(body);
        model.bank(batch, banks, restore, failureId, id);
        PaymentModelProperties.feature(restore ? "RESTORE_SUCCESS" : "APPLY_" + banks.size() + "_CREDITS");
    }

    String submit(Map<String, Object> body) {
        var response = app.api("/api/corrections", "operator", mapper.writeValueAsString(body));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return mapper.readTree(response.body()).path("correction").path("id").asString();
    }

    void recheck(SettlementModel.Batch batch) {
        if (!batch.status.equals("HELD")) {
            rejected("/api/batches/" + batch.id + "/recheck", Map.of(), 409, "BATCH_NOT_HELD");
            return;
        }
        var response = app.api("/api/batches/" + batch.id + "/recheck", "operator", "{}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        model.settlement.recheck(batch, app.clock.instant(), "");
        model.refunds.release();
        PaymentModelProperties.feature("RECHECK_" + batch.status);
    }

    void returnCash(SettlementModel.Bank credit, boolean wrongAmount) {
        state.p2.bank(new SettlementModel.BankInput(
                "bank_" + state.prefix + "return" + ++next,
                credit.input.id(),
                wrongAmount ? Math.addExact(credit.input.amount(), 1) : credit.input.amount(),
                "BOOKED",
                app.clock.instant().atZone(SettlementModel.ZONE).toLocalDate(),
                "DEBIT"));
        PaymentModelProperties.feature(wrongAmount ? "RETURN_REJECTED_MATCH" : "RETURN_SUCCESS");
    }

    void invalid(int index, int variant) {
        // One low-weight action exercises false preconditions instead of filtering all of them.
        switch (variant) {
            case 0 -> {
                String target = model.targets.keySet().stream()
                        .filter(k -> k.startsWith("payment:"))
                        .findFirst()
                        .orElse(null);
                if (target != null) {
                    String payment = target.substring(8, target.length() - 9);
                    rejected("/api/corrections", captureRequest(payment, target, null), 409, "ALREADY_REVERSED");
                    return;
                }
            }
            case 1 -> {
                for (var batch : bankCandidates(false)) {
                    var banks = credits(batch);
                    if (banks.size() > 1) {
                        bankCorrection(batch, List.of(banks.getFirst()), false);
                        return;
                    }
                }
            }
            case 2 -> {
                var batch = model.settlement.batches.values().stream()
                        .filter(b -> !List.of("ABSENT", "HELD").contains(b.status))
                        .findFirst()
                        .orElse(null);
                if (batch != null) {
                    recheck(batch);
                    return;
                }
            }
            case 3 -> {
                for (String payment :
                        state.model.payments.keySet().stream().sorted().toList()) {
                    if (model.captureError(payment, model.current(payment), null) != null) {
                        capture(payment, null);
                        return;
                    }
                }
            }
            case 4 -> {
                for (var batch : model.settlement.batches.values()) {
                    var banks = model.settlement.banks.values().stream()
                            .filter(b -> b.input.direction().equals("CREDIT")
                                    && b.input.reference().equals(batch.id))
                            .limit(1)
                            .toList();
                    if (banks.isEmpty()) continue;
                    boolean restore = !batch.status.equals("PAYOUT_FAILED");
                    if (model.bankError(batch, banks, restore) != null) {
                        bankCorrection(batch, banks, restore);
                        return;
                    }
                }
            }
            case 5, 6 -> {
                if (!captures(false).isEmpty()) {
                    String payment = pick(captures(false), index);
                    long reserved =
                            Math.subtractExact(state.model.payments.get(payment), model.refunds.remaining(payment));
                    capture(payment, variant == 5 && reserved > 50 ? Math.subtractExact(reserved, 1) : 49L);
                    return;
                }
            }
            case 7 -> {
                if (!unapplied().isEmpty()) {
                    returnCash(pick(unapplied(), index), true);
                    return;
                }
            }
            default -> throw new IllegalArgumentException("Unknown invalid variant");
        }
        String payment = pick(state.model.payments.keySet().stream().sorted().toList(), index);
        capture(payment, state.model.payments.get(payment));
    }

    void rejected(String path, Map<String, Object> body, int status, String code) {
        var before = databaseRows();
        var response = app.api(path, "operator", mapper.writeValueAsString(body));
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertThat(databaseRows())
                .as("4xx leaves every business and audit row unchanged")
                .isEqualTo(before);
        PaymentModelProperties.feature("REJECTED_" + code);
    }

    List<String> databaseRows() {
        return List.of(
                        "ledger.correction",
                        "ledger.correction_target",
                        "ledger.journal_entry",
                        "ledger.posting",
                        "ledger.account",
                        "core.payment",
                        "core.payment_transition",
                        "core.refund",
                        "core.refund_transition",
                        "core.settlement_batch",
                        "core.settlement_batch_line",
                        "core.batch_transition",
                        "recon.bank_line",
                        "recon.issue",
                        "recon.issue_transition",
                        "io.outbound_command",
                        "io.idempotency_record")
                .stream()
                .map(table -> app.jdbc
                        .sql("SELECT coalesce(jsonb_agg(r ORDER BY r::text),'[]'::jsonb)::text "
                                + "FROM (SELECT to_jsonb(t) r FROM " + table + " t) s")
                        .query(String.class)
                        .single())
                .toList();
    }

    void check() {
        var actual = new LinkedHashMap<String, String>();
        app.jdbc
                .sql("SELECT id, correction_type FROM ledger.correction")
                .query((rs, n) -> {
                    actual.put(rs.getString(1), rs.getString(2));
                    return 1;
                })
                .list();
        assertThat(actual).as("correction identities/types/count").isEqualTo(model.corrections);
        var targets = new LinkedHashMap<String, CorrectionModel.Target>();
        app.jdbc
                .sql(
                        "SELECT e.source_key,t.correction_id,t.role FROM ledger.correction_target t JOIN ledger.journal_entry e ON e.id=t.entry_id")
                .query((rs, n) -> {
                    targets.put(rs.getString(1), new CorrectionModel.Target(rs.getString(2), rs.getString(3)));
                    return 1;
                })
                .list();
        assertThat(targets).as("target identities/roles/count").isEqualTo(model.targets);
        for (var entry : model.journal.entries.entrySet()) {
            var postings = app.jdbc
                    .sql(
                            "SELECT e.entry_type,p.account_code,p.side,p.amount FROM ledger.journal_entry e JOIN ledger.posting p ON p.entry_id=e.id WHERE e.source_key=?")
                    .param(entry.getKey())
                    .query((rs, n) -> {
                        assertThat(rs.getString(1)).isEqualTo(entry.getValue().type());
                        return new SettlementModel.Posting(rs.getInt(2), rs.getString(3), rs.getLong(4));
                    })
                    .list();
            assertThat(postings)
                    .containsExactlyInAnyOrderElementsOf(entry.getValue().postings());
        }
        var expectedIssues = new java.util.HashSet<SettlementModel.Issue>(model.settlement.issues);
        state.model.issues.forEach((category, events) ->
                events.forEach(event -> expectedIssues.add(new SettlementModel.Issue(category, "event", event))));
        var actualIssues = app.jdbc
                .sql("SELECT category,subject_type,subject_id FROM recon.issue")
                .query((rs, n) -> new SettlementModel.Issue(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();
        assertThat(actualIssues)
                .as("all issue identities and counts")
                .containsExactlyInAnyOrderElementsOf(expectedIssues);
        for (var issue : expectedIssues) {

            var row = app.jdbc
                    .sql(
                            "SELECT status,resolution_correction_id FROM recon.issue WHERE category=? AND subject_type=? AND subject_id=?")
                    .params(issue.category(), issue.subjectType(), issue.subjectId())
                    .query()
                    .singleRow();
            String resolution = model.settlement.resolutions.get(issue);
            assertThat(row)
                    .containsEntry("status", resolution == null ? "OPEN" : "RESOLVED")
                    .containsEntry(
                            "resolution_correction_id", resolution == null || resolution.isEmpty() ? null : resolution);
        }
        long statementCash = 0;
        long waitingCash = 0;
        long unmatchedDebits = 0;
        for (var bank : model.settlement.banks.values()) {
            if (!bank.input.status().equals("BOOKED")) continue;
            boolean debit = bank.input.direction().equals("DEBIT");
            statementCash =
                    Math.addExact(statementCash, debit ? Math.negateExact(bank.input.amount()) : bank.input.amount());
            if ("WAITING".equals(bank.match)) waitingCash = Math.addExact(waitingCash, bank.input.amount());
            if (debit && "UNMATCHED_NO_POSTING".equals(bank.match)) {
                unmatchedDebits = Math.addExact(unmatchedDebits, bank.input.amount());
                assertThat(model.settlement.issues)
                        .contains(new SettlementModel.Issue("UNMATCHED_BANK_LINE", "bank_txn", bank.input.id()));
            }
        }
        assertThat(app.balances().get(1103))
                .as("INV-11 bank evidence, waiting credits and explained unmatched debits")
                .isEqualTo(Math.addExact(Math.subtractExact(statementCash, waitingCash), unmatchedDebits));
        long unapplied = SettlementModel.sum(
                unapplied().stream().map(b -> b.input.amount()).toList());
        assertThat(app.balances().get(2105))
                .as("INV-12 independent current UNAPPLIED credits")
                .isEqualTo(unapplied);
        assertThat(app.jdbc
                        .sql(
                                "SELECT balance=(SELECT coalesce(sum(amount),0) FROM recon.bank_line b WHERE match_status='UNAPPLIED' AND version=(SELECT max(version) FROM recon.bank_line v WHERE v.bank_txn_id=b.bank_txn_id)) FROM ledger.account WHERE code=2105")
                        .query(Boolean.class)
                        .single())
                .as("INV-12 database current versions")
                .isTrue();
        assertThat(app.balances().get(2104))
                .as("INV-9 no P5 obligations in this generator")
                .isZero();
        assertThat(app.balances().get(2102))
                .as("INV-6 settlement proceeds minus reserved refunds")
                .isEqualTo(Math.addExact(
                        model.settlement.deltas().getOrDefault(2102, 0L),
                        model.refunds.deltas().getOrDefault(2102, 0L)));
        for (var payment : state.model.payments.entrySet()) {
            var row = app.jdbc
                    .sql(
                            "SELECT expected_fee,amount-refunded_amount AS remaining FROM core.payment WHERE processor_payment_ref=?")
                    .param(payment.getKey())
                    .query()
                    .singleRow();
            assertThat(row)
                    .containsEntry("expected_fee", SettlementModel.fee(payment.getValue()))
                    .containsEntry("remaining", model.refunds.remaining(payment.getKey()));
        }
    }

    private static <T> T pick(List<T> values, int index) {
        return values.get(index % values.size());
    }
}
