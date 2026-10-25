package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.Transformer;

/** External-input driver and assertions. Expected business effects live only in SettlementModel. */
final class P2Scenario {
    enum Kind {
        CREATE_BATCH,
        READY,
        REPLAY_PAYOUT,
        NEW_ID_READY,
        INTERRUPT_FETCH,
        FAIL,
        REISSUE,
        BANK_EXACT,
        BANK_SHORT,
        BANK_PENDING,
        BOOK_PENDING,
        BANK_DUPLICATE_FILE,
        BANK_UNKNOWN,
        ADVANCE_DAYS
    }

    record Operation(Kind kind) implements Action.Dependent<PaymentModelProperties.State> {
        @Override
        public boolean precondition(PaymentModelProperties.State state) {
            return state.p2.eligible(kind);
        }

        @Override
        public Arbitrary<Transformer<PaymentModelProperties.State>> transformer(PaymentModelProperties.State state) {
            return Combinators.combine(Arbitraries.integers().between(0, 100), Arbitraries.of(false, true))
                    .as((index, variant) -> PaymentModelProperties.step(
                            kind.name(),
                            kind + " index=" + index + " variant=" + variant,
                            s -> s.p2.execute(kind, index, variant)));
        }
    }

    private final PaymentModelProperties.State state;
    private final ModelApplication app;
    private final SettlementModel model;
    private final Map<String, byte[]> deliveries = new java.util.LinkedHashMap<>();
    private final List<byte[]> files = new ArrayList<>();
    private int next;

    P2Scenario(PaymentModelProperties.State state) {
        this.state = state;
        app = state.app;
        model = state.model.settlement;
    }

    List<String> availablePayments() {
        Set<String> assigned = new HashSet<>();
        model.batches.values().forEach(b -> b.lines.forEach(l -> assigned.add(l.reference())));
        return state.model.payments.keySet().stream()
                .filter(p -> !assigned.contains(p))
                .sorted()
                .toList();
    }

    private List<SettlementModel.Batch> batches(Kind kind) {
        return model.batches.values().stream()
                .filter(b -> switch (kind) {
                    case READY, INTERRUPT_FETCH ->
                        List.of("ABSENT", "ANNOUNCED").contains(b.status);
                    case NEW_ID_READY ->
                        List.of("CONFIRMED", "BANKED", "PAYOUT_FAILED").contains(b.status);
                    case FAIL -> List.of("CONFIRMED", "BANKED", "PAYOUT_FAILED").contains(b.status);
                    case REISSUE ->
                        b.status.equals("PAYOUT_FAILED")
                                && model.batches.values().stream()
                                        .noneMatch(other -> other.lines.stream()
                                                .anyMatch(l -> l.reference().equals(b.id)));
                    default -> true;
                })
                .toList();
    }

    boolean eligible(Kind kind) {
        return switch (kind) {
            case CREATE_BATCH -> !availablePayments().isEmpty();
            case REPLAY_PAYOUT -> !deliveries.isEmpty();
            case BOOK_PENDING ->
                model.banks.values().stream().anyMatch(b -> b.input.status().equals("PENDING"));
            case BANK_DUPLICATE_FILE -> !files.isEmpty();
            case BANK_UNKNOWN, ADVANCE_DAYS -> true;
            default -> !batches(kind).isEmpty();
        };
    }

    void execute(Kind kind, int index, boolean variant) {
        state.drain("before-p2");
        switch (kind) {
            case CREATE_BATCH -> create(index, variant);
            case READY, NEW_ID_READY -> event(pick(batches(kind), index), "payout.reconciliation_completed");
            case INTERRUPT_FETCH -> interrupt(pick(batches(kind), index));
            case FAIL -> event(pick(batches(kind), index), "payout.failed");
            case REISSUE -> {
                SettlementModel.Batch failed = pick(batches(kind), index);
                String id = "po_" + state.prefix + "b" + ++next;
                var batch = new SettlementModel.Batch(
                        id,
                        List.of(new SettlementModel.Line("txn_" + id, "payout_failure", failed.id, failed.net, 0)),
                        app.clock.instant());
                model.batches.put(id, batch);
                install(batch, false);
                event(batch, "payout.reconciliation_completed");
            }
            case REPLAY_PAYOUT -> {
                app.advance(1);
                app.deliverRaw(pick(new ArrayList<>(deliveries.values()), index));
                app.drain("duplicate-payout");
            }
            case BANK_EXACT, BANK_SHORT, BANK_PENDING -> {
                SettlementModel.Batch batch = pick(batches(kind), index);
                bank(new SettlementModel.BankInput(
                        "bank_" + state.prefix + ++next,
                        batch.id,
                        kind == Kind.BANK_SHORT ? Math.subtractExact(batch.net, 1) : batch.net,
                        kind == Kind.BANK_PENDING ? "PENDING" : "BOOKED",
                        date()));
            }
            case BOOK_PENDING ->
                bank(pick(
                                model.banks.values().stream()
                                        .filter(b -> b.input.status().equals("PENDING"))
                                        .toList(),
                                index)
                        .input
                        .booked());
            case BANK_DUPLICATE_FILE -> app.bank(pick(files, index));
            case BANK_UNKNOWN ->
                bank(new SettlementModel.BankInput(
                        "bank_" + state.prefix + ++next,
                        "po_" + state.prefix + "missing" + next,
                        50 + index,
                        "BOOKED",
                        date()));
            case ADVANCE_DAYS -> {
                app.advance((variant ? 7 : 1) * 86400);
                app.expireWaiting();
                model.expire(app.clock.instant());
            }
        }
    }

    private LocalDate date() {
        return app.clock.instant().atZone(SettlementModel.ZONE).toLocalDate();
    }

    private static <T> T pick(List<T> candidates, int index) {
        return candidates.get(index % candidates.size());
    }

    private void create(int index, boolean readyFirst) {
        String id = "po_" + state.prefix + "b" + ++next;
        List<String> candidates = availablePayments();
        List<SettlementModel.Line> lines = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < Math.min(1 + index % 3, candidates.size()); i++) {
            String payment = candidates.get((index + i) % candidates.size());
            long gross = state.model.payments.get(payment);
            long fee = SettlementModel.fee(gross);
            if (index % 3 == 0) fee = Math.addExact(fee, 1); // Actual fee differs from contract.
            if (index % 3 == 1) fee = 0; // Valid fee boundary, also exercises zero posting omission.
            var line = new SettlementModel.Line("txn_" + id + "x" + i, "charge", payment, gross, fee);
            if (Math.addExact(total, line.net()) > 99_999_999) break;
            lines.add(line);
            total = Math.addExact(total, line.net());
        }
        var batch = new SettlementModel.Batch(id, lines, app.clock.instant());
        if (lines.size() > 1) PaymentModelProperties.feature("MULTI_PAYMENT_BATCH");
        if (lines.stream().anyMatch(l -> l.fee() != SettlementModel.fee(l.gross())))
            PaymentModelProperties.feature("ACTUAL_FEE_DIFFERS");
        if (batch.fees == 0) PaymentModelProperties.feature("ZERO_FEE_BATCH");
        model.batches.put(id, batch);
        install(batch, false);
        if (!readyFirst) event(batch, "payout.created");
        // readyFirst leaves external evidence available before the created webhook is delivered.
    }

    void install(SettlementModel.Batch batch, boolean interrupted) {
        List<String> lines = batch.lines.stream()
                .map(l -> """
                {"id":"%s","type":"%s","source":"%s","amount":%d,"fee":%d,"net":%d,
                "currency":"cad","created":%d}
                """.formatted(
                        l.id(), l.type(), l.reference(), l.gross(), l.fee(), l.net(), batch.created.getEpochSecond()))
                .toList();
        String first = "{\"object\":\"list\",\"data\":[" + lines.getFirst() + "],\"has_more\":true}";
        String last = "{\"object\":\"list\",\"data\":[" + String.join(",", lines.subList(1, lines.size()))
                + "],\"has_more\":false}";
        app.processor(batch.id, new ProcessorReplay.Pages(first, last, interrupted));
    }

    private byte[] body(SettlementModel.Batch batch, String event, String type) {
        return """
                {"id":"%s","type":"%s","api_version":"2026-09-30.endive","livemode":false,
                "created":%d,"data":{"object":{"id":"%s","amount":%d,"currency":"cad",
                "automatic":true,"method":"standard","status":"%s","reconciliation_status":"%s",
                "arrival_date":%d,"created":%d}}}
                """.formatted(
                        event,
                        type,
                        batch.created.getEpochSecond(),
                        batch.id,
                        batch.net,
                        type.equals("payout.failed") ? "failed" : "paid",
                        type.equals("payout.created") ? "in_progress" : "completed",
                        batch.created.plusSeconds(86400).getEpochSecond(),
                        batch.created.getEpochSecond())
                .getBytes(StandardCharsets.UTF_8);
    }

    void event(SettlementModel.Batch batch, String type) {
        boolean wasAbsent = batch.status.equals("ABSENT");
        String event = state.nextEvent();
        byte[] bytes = body(batch, event, type);
        deliveries.put(event, bytes);
        app.deliverRaw(bytes);
        app.drain("payout-worker");
        String result = model.event(batch, type, app.clock.instant());
        state.p3.model.release();
        model.outcomes.put(
                event, new PaymentModel.Outcome(result.equals("BUSINESS_CONFLICT") ? "REJECTED" : "PROCESSED", result));
        if (wasAbsent && type.equals("payout.reconciliation_completed")) {
            PaymentModelProperties.feature("READY_BEFORE_CREATED");
            state.check();
            event(batch, "payout.created"); // Check readiness-first separately before late created.
        }
    }

    private void interrupt(SettlementModel.Batch batch) {
        install(batch, true);
        String event = state.nextEvent();
        byte[] bytes = body(batch, event, "payout.reconciliation_completed");
        deliveries.put(event, bytes);
        app.deliverRaw(bytes);
        model.outcomes.put(event, new PaymentModel.Outcome("RECEIVED", null));
        app.drain("interrupted-fetch");
        assertThat(app.jdbc
                        .sql("SELECT complete FROM io.processor_fetch WHERE event_id=:id")
                        .param("id", event)
                        .query(Boolean.class)
                        .list())
                .containsExactly(false);
        state.check(); // No partial batch version, state change or postings before retry.
        install(batch, false);
        app.advance(2);
        app.drain("fetch-recovery");
        model.outcomes.put(
                event,
                new PaymentModel.Outcome(
                        "PROCESSED", model.event(batch, "payout.reconciliation_completed", app.clock.instant())));
    }

    private void bank(SettlementModel.BankInput line) {
        long booked = line.status().equals("BOOKED") ? line.amount() : 0;
        byte[] raw = ("H,BANK_STATEMENT,SIM-CAD-001,%s,%s,%d,CAD\r\n"
                        + "D,%s,CREDIT,%d,CAD,%s,%s,%s,%s,model\r\nT,1,%d,0,0,%d\r\n")
                .formatted(
                        line.date(),
                        line.date(),
                        files.size() + 1,
                        line.id(),
                        line.amount(),
                        line.status(),
                        line.reference(),
                        line.date(),
                        line.date(),
                        booked,
                        booked)
                .getBytes(StandardCharsets.UTF_8);
        files.add(raw);
        app.bank(raw);
        model.bank(line, app.clock.instant());
        PaymentModelProperties.feature("BANK_" + model.banks.get(line.id()).match);
    }

    void finish() {
        app.advance(7 * 86400);
        app.expireWaiting();
        model.expire(app.clock.instant());
    }

    void check() {
        var jdbc = app.jdbc;
        Map<String, String> actualBatches = new HashMap<>();
        jdbc.sql(
                        "SELECT batch_id, status, payout_amount, gross, fees, refunds, payout_failures_returned, net FROM core.settlement_batch WHERE batch_id LIKE :prefix")
                .param("prefix", "po_" + state.prefix + "%")
                .query((rs, n) -> {
                    String id = rs.getString(1);
                    SettlementModel.Batch batch = model.batches.get(id);
                    assertThat(batch).as("model batch %s", id).isNotNull();
                    assertThat(rs.getLong(3)).isEqualTo(batch.net);
                    if (List.of("CONFIRMED", "BANKED", "PAYOUT_FAILED").contains(batch.status)) {
                        assertThat(rs.getLong(4)).isEqualTo(batch.gross);
                        assertThat(rs.getLong(5)).isEqualTo(batch.fees);
                        assertThat(rs.getLong(6)).isEqualTo(batch.refunds);
                        assertThat(rs.getLong(7)).isEqualTo(batch.returned);
                        assertThat(rs.getLong(8)).isEqualTo(batch.net);
                    } else assertThat(rs.getObject(8)).isNull();
                    return Map.entry(id, rs.getString(2));
                })
                .list()
                .forEach(e -> actualBatches.put(e.getKey(), e.getValue()));
        Map<String, String> expectedBatches = new HashMap<>();
        model.batches.values().stream()
                .filter(b -> !b.status.equals("ABSENT"))
                .forEach(b -> expectedBatches.put(b.id, b.status));
        assertThat(actualBatches).as("batch states").isEqualTo(expectedBatches);

        Map<String, SettlementModel.Line> actualLines = new HashMap<>();
        jdbc.sql("""
                SELECT batch_id, balance_txn_id, line_type, processor_ref, gross, fee, net
                FROM core.settlement_batch_line WHERE batch_id LIKE :prefix
                """)
                .param("prefix", "po_" + state.prefix + "%")
                .query((rs, n) -> {
                    var line = new SettlementModel.Line(
                            rs.getString(2),
                            switch (rs.getString(3)) {
                                case "PAYMENT" -> "charge";
                                case "REFUND" -> "refund";
                                default -> "payout_failure";
                            },
                            rs.getString(4),
                            rs.getLong(5),
                            rs.getLong(6));
                    assertThat(rs.getLong(7)).isEqualTo(line.net());
                    actualLines.put(rs.getString(1) + ":" + rs.getString(2), line);
                    return 1;
                })
                .list();
        Map<String, SettlementModel.Line> expectedLines = new HashMap<>();
        model.batches.values().stream()
                .filter(b -> List.of("CONFIRMED", "BANKED", "PAYOUT_FAILED").contains(b.status))
                .forEach(b -> b.lines.forEach(l -> expectedLines.put(b.id + ":" + l.id(), l)));
        assertThat(actualLines).as("confirmed processor lines").isEqualTo(expectedLines);

        Map<String, List<SettlementModel.Posting>> postings = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        jdbc.sql("""
                SELECT e.source_key, e.entry_type, p.account_code, p.side, p.amount
                FROM ledger.journal_entry e JOIN ledger.posting p ON p.entry_id=e.id
                WHERE e.source_key LIKE :batch OR e.source_key LIKE :bank
                """)
                .param("batch", "batch:po_" + state.prefix + "%")
                .param("bank", "bank_txn:bank_" + state.prefix + "%")
                .query((rs, n) -> {
                    types.put(rs.getString(1), rs.getString(2));
                    postings.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                            .add(new SettlementModel.Posting(rs.getInt(3), rs.getString(4), rs.getLong(5)));
                    return 1;
                })
                .list();
        assertThat(postings.keySet()).as("P2 source keys").isEqualTo(model.entries.keySet());
        model.entries.forEach((key, entry) -> {
            assertThat(types.get(key)).isEqualTo(entry.type());
            assertThat(postings.get(key))
                    .as("posting multiset %s", key)
                    .containsExactlyInAnyOrderElementsOf(entry.postings());
        });
        Map<String, String> banks = new HashMap<>();
        jdbc.sql(
                        "SELECT bank_txn_id, version, match_status, matched_batch_id, wait_until FROM recon.bank_line WHERE bank_txn_id LIKE :prefix")
                .param("prefix", "bank_" + state.prefix + "%")
                .query((rs, n) -> {
                    String key = rs.getString(1) + ":" + rs.getInt(2);
                    banks.put(key, rs.getString(3));
                    SettlementModel.Bank bank = model.bankVersions.stream()
                            .filter(b -> (b.input.id() + ":" + b.version).equals(key))
                            .findFirst()
                            .orElseThrow();
                    assertThat(rs.getString(4)).isEqualTo(bank.matchedBatch);
                    assertThat(
                                    rs.getTimestamp(5) == null
                                            ? null
                                            : rs.getTimestamp(5).toInstant())
                            .isEqualTo(bank.due);
                    return 1;
                })
                .list();
        Map<String, String> expectedBanks = new HashMap<>();
        model.bankVersions.forEach(b -> expectedBanks.put(b.input.id() + ":" + b.version, b.match));
        assertThat(banks).as("all bank line versions").isEqualTo(expectedBanks);
        Set<SettlementModel.Issue> issues = new HashSet<>(jdbc.sql("""
                SELECT category, subject_type, subject_id FROM recon.issue
                WHERE subject_id LIKE :batch OR subject_id LIKE :bank
                """)
                .param("batch", "po_" + state.prefix + "%")
                .param("bank", "bank_" + state.prefix + "%")
                .query((rs, n) -> new SettlementModel.Issue(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list());
        assertThat(issues).isEqualTo(model.issues);
        var balances = app.balances();
        for (int code : List.of(1102, 1103, 2105))
            assertThat(balances.get(code)).as("INV-5 %s", code).isNotNegative();
        assertThat(balances.get(2101))
                .as("INV-9 CAPTURED gross, whole database")
                .isEqualTo(total("SELECT coalesce(sum(amount),0)::bigint FROM core.payment WHERE status='CAPTURED'"));
        assertThat(balances.get(1102))
                .as("INV-9 CONFIRMED net, whole database")
                .isEqualTo(total(
                        "SELECT coalesce(sum(net),0)::bigint FROM core.settlement_batch WHERE status='CONFIRMED'"));
        assertThat(balances.get(1103))
                .as("INV-11 unique BOOKED credits minus WAITING, including unapplied cash")
                .isEqualTo(total("""
                        SELECT coalesce(sum(CASE WHEN match_status='WAITING' THEN 0 ELSE amount END),0)::bigint
                        FROM (SELECT DISTINCT ON (bank_txn_id) * FROM recon.bank_line
                              ORDER BY bank_txn_id, version DESC) b WHERE status='BOOKED'
                        """));
    }

    private long total(String sql) {
        return app.jdbc.sql(sql).query(Long.class).single();
    }
}
