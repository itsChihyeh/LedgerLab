package dev.ledgerlab.sim.bank;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owns bank execution and statements. All reads use only bank-owned evidence. */
@Service
public final class Bank implements IncomingTransfers {
    private static final String ACCOUNT = "SIM-CAD-001";
    private static final long LINE_MAX = 99999999;
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final List<String> FAULTS = List.of(
            "NONE", "SHORT", "DUPLICATE", "SPLIT", "DELAY_DAYS", "PENDING_FIRST", "FOREIGN_CURRENCY", "UNKNOWN_CREDIT");
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final BankCalendar calendar = new BankCalendar();
    private final JsonMapper json = JsonMapper.builder().build();

    public Bank(JdbcClient jdbc, PlatformTransactionManager manager, @Qualifier("simulationClock") Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    private LocalDate today() {
        return clock.instant().atZone(TORONTO).toLocalDate();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private void lock() {
        jdbc.sql("insert into sim_bank.account values (?,'CAD',0,?) on conflict do nothing")
                .param(ACCOUNT)
                .param(now())
                .update();
        jdbc.sql("select account_ref from sim_bank.account where account_ref=? for update")
                .param(ACCOUNT)
                .query(String.class)
                .single();
    }

    public JsonNode plan(JsonNode request) {
        if (!request.isObject()
                || !List.of("payout_ref", "scenario", "fault", "parameter", "memo")
                        .containsAll(request.propertyNames())) throw invalid("plan fields");
        String target = text(request, "payout_ref", "*");
        String scenario = text(request, "scenario", "manual");
        String fault = text(request, "fault", "NONE");
        String memo = text(request, "memo", "Simulated bank transfer");
        if (!(target.equals("*") || target.startsWith("po_")) || scenario.isBlank() || !FAULTS.contains(fault))
            throw invalid("plan");
        JsonNode parameter = request.path("parameter");
        if (!parameter.isMissingNode() && (!parameter.isIntegralNumber() || !parameter.canConvertToLong()))
            throw invalid("parameter must be an integer");
        long value = parameter.isMissingNode() ? (fault.equals("UNKNOWN_CREDIT") ? 100 : 0) : parameter.asLong();
        if (value < 0
                || value > LINE_MAX
                || ((fault.equals("SHORT") || fault.equals("UNKNOWN_CREDIT")) && value == 0)
                || (fault.equals("DELAY_DAYS") && (value < 1 || value > 730))
                || (!List.of("SHORT", "UNKNOWN_CREDIT", "DELAY_DAYS").contains(fault) && value != 0))
            throw invalid("parameter range");
        var normalized = json.createObjectNode()
                .put("payout_ref", target)
                .put("scenario", scenario)
                .put("fault", fault)
                .put("parameter", value)
                .put("memo", memo);
        transaction.executeWithoutResult(status -> {
            lock();
            jdbc.sql("""
                    insert into sim_bank.fault_plan values (?,?,?,?,?,?)
                    on conflict (target_ref) do update set scenario=excluded.scenario, fault=excluded.fault,
                        parameter=excluded.parameter, memo=excluded.memo, recorded_at=excluded.recorded_at
                    """)
                    .param(target)
                    .param(scenario)
                    .param(fault)
                    .param(value)
                    .param(memo)
                    .param(now())
                    .update();
        });
        return normalized;
    }

    @Override
    public void receive(String payoutRef, long amount) {
        if (!payoutRef.startsWith("po_") || amount <= 0 || amount >= 9007199254740992L)
            throw invalid("incoming transfer");
        transaction.executeWithoutResult(status -> {
            lock();
            var existing = jdbc.sql("select claimed_amount from sim_bank.incoming_transfer where payout_ref=?")
                    .param(payoutRef)
                    .query(Long.class)
                    .optional();
            if (existing.isPresent()) {
                if (existing.get() != amount)
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Transfer changed");
                return;
            }
            JsonNode plan = jdbc.sql("""
                    select scenario, fault, parameter, memo from sim_bank.fault_plan
                    where target_ref in (?, '*') order by (target_ref = ?) desc limit 1
                    """)
                    .param(payoutRef)
                    .param(payoutRef)
                    .query((row, index) -> json.createObjectNode()
                            .put("scenario", row.getString(1))
                            .put("fault", row.getString(2))
                            .put("parameter", row.getLong(3))
                            .put("memo", row.getString(4)))
                    .optional()
                    .orElseGet(() -> json.createObjectNode()
                            .put("scenario", "default")
                            .put("fault", "NONE")
                            .put("parameter", 0)
                            .put("memo", "Simulated bank transfer"));
            String fault = plan.path("fault").asText();
            long parameter = plan.path("parameter").asLong();
            if ((fault.equals("SHORT") && parameter >= amount) || (fault.equals("SPLIT") && amount < 2))
                throw invalid("fault would produce a zero or negative bank line");
            LocalDate due =
                    calendar.addBusinessDays(today(), Math.addExact(1, fault.equals("DELAY_DAYS") ? parameter : 0));
            if (fault.equals("PENDING_FIRST")) BankCalendar.check(due.plusDays(1));
            jdbc.sql("insert into sim_bank.incoming_transfer values (?,?,?,?::jsonb,?,false,?)")
                    .param(payoutRef)
                    .param(ACCOUNT)
                    .param(amount)
                    .param(plan.toString())
                    .param(due)
                    .param(now())
                    .update();
            if (!fault.equals("NONE")) truth(plan.path("scenario").asText(), payoutRef, fault, category(fault));
        });
    }

    private static String category(String fault) {
        return switch (fault) {
            case "SHORT", "SPLIT" -> "DEPOSIT_AMOUNT_MISMATCH";
            case "DELAY_DAYS", "PENDING_FIRST" -> "TIMING_DIFFERENCE";
            default -> "UNMATCHED_BANK_LINE";
        };
    }

    private void truth(String scenario, String object, String fault, String category) {
        jdbc.sql(
                        "insert into sim_truth.injected_fault (scenario,object_ref,fault,expected_category,recorded_at) values (?,?,?,?,?)")
                .param(scenario)
                .param(object)
                .param(fault)
                .param(category)
                .param(now())
                .update();
    }

    /** Also called after clock advance; due work is persisted and survives a restart. */
    public void advance() {
        transaction.executeWithoutResult(status -> {
            lock();
            executeDue();
        });
    }

    private void executeDue() {
        var due = jdbc.sql(
                        "select payout_ref,claimed_amount,plan::text,due_date from sim_bank.incoming_transfer where not executed and due_date<=? order by due_date,payout_ref")
                .param(today())
                .query((row, index) -> new Transfer(
                        row.getString(1),
                        row.getLong(2),
                        json.readTree(row.getString(3)),
                        row.getObject(4, LocalDate.class)))
                .list();
        for (Transfer transfer : due) {
            execute(transfer);
            jdbc.sql("update sim_bank.incoming_transfer set executed=true where payout_ref=?")
                    .param(transfer.id())
                    .update();
        }
        jdbc.sql("update sim_bank.txn set status='BOOKED' where status='PENDING' and booking_date<=?")
                .param(today())
                .update();
        long balance = 0;
        for (Line line : rows()) {
            if (line.status().equals("BOOKED"))
                balance = line.direction().equals("CREDIT")
                        ? Math.addExact(balance, line.amount())
                        : Math.subtractExact(balance, line.amount());
        }
        jdbc.sql("update sim_bank.account set balance=? where account_ref=?")
                .param(balance)
                .param(ACCOUNT)
                .update();
    }

    private void execute(Transfer transfer) {
        String fault = transfer.plan().path("fault").asText();
        long parameter = transfer.plan().path("parameter").asLong();
        long amount = fault.equals("SHORT") ? Math.subtractExact(transfer.amount(), parameter) : transfer.amount();
        int next;
        if (fault.equals("SPLIT")) {
            next = add(transfer, amount / 2, transfer.id(), 1);
            next = add(transfer, Math.subtractExact(amount, amount / 2), transfer.id(), next);
        } else next = add(transfer, amount, transfer.id(), 1);
        if (fault.equals("DUPLICATE")) add(transfer, amount, transfer.id(), next);
        if (fault.equals("UNKNOWN_CREDIT")) add(transfer, parameter, "unknown_" + transfer.id(), next);
    }

    private int add(Transfer transfer, long amount, String reference, int sequence) {
        String fault = transfer.plan().path("fault").asText();
        boolean pending = fault.equals("PENDING_FIRST");
        LocalDate booked = pending ? transfer.due().plusDays(1) : transfer.due();
        while (amount > 0) {
            long part = Math.min(amount, LINE_MAX);
            jdbc.sql("""
                    insert into sim_bank.txn values (?,?,?,'CREDIT',?,?,?,?,?,?,?,?,?)
                    """)
                    .param("bt_" + transfer.id() + "_" + sequence)
                    .param(ACCOUNT)
                    .param(transfer.id())
                    .param(part)
                    .param(fault.equals("FOREIGN_CURRENCY") ? "USD" : "CAD")
                    .param(pending ? "PENDING" : "BOOKED")
                    .param(reference)
                    .param(booked)
                    .param(booked)
                    .param(transfer.due())
                    .param(transfer.plan().path("memo").asText())
                    .param(now())
                    .update();
            amount = Math.subtractExact(amount, part);
            sequence = Math.incrementExact(sequence);
        }
        return sequence;
    }

    private List<Line> rows() {
        return jdbc.sql("select * from sim_bank.txn where account_ref=? order by booking_date,bank_txn_id")
                .param(ACCOUNT)
                .query((row, index) -> new Line(
                        row.getString("bank_txn_id"),
                        row.getString("direction"),
                        row.getLong("amount"),
                        row.getString("currency"),
                        row.getString("status"),
                        row.getString("reference"),
                        row.getObject("booking_date", LocalDate.class),
                        row.getObject("value_date", LocalDate.class),
                        row.getObject("visible_date", LocalDate.class),
                        row.getString("memo")))
                .list();
    }

    public List<Line> transactions() {
        return Objects.requireNonNull(transaction.execute(status -> {
            lock();
            executeDue();
            return rows();
        }));
    }

    public String statement(LocalDate date, String corrupt) {
        BankCalendar.check(date);
        if (date.isAfter(today())) throw invalid("statement date is in the future");
        if (!List.of("", "trailer_count", "trailer_sum", "balance").contains(corrupt)) throw invalid("corrupt");
        return Objects.requireNonNull(transaction.execute(status -> {
            lock();
            executeDue();
            var totals = totals(date, rows());
            String body = totals.details() + totals.trailer("");
            var prior = jdbc.sql(
                            "select version,body,content from sim_bank.statement where account_ref=? and statement_date=? order by version desc limit 1")
                    .param(ACCOUNT)
                    .param(date)
                    .query((row, index) -> new Statement(row.getInt(1), row.getString(2), row.getString(3)))
                    .optional();
            int version = prior.map(Statement::version).orElse(0);
            String clean;
            if (prior.isPresent() && prior.get().body().equals(body))
                clean = prior.get().content();
            else {
                version = Math.incrementExact(version);
                clean = header(date, version) + body;
                jdbc.sql("insert into sim_bank.statement values (?,?,?,?,?,?)")
                        .param(ACCOUNT)
                        .param(date)
                        .param(version)
                        .param(clean)
                        .param(body)
                        .param(now())
                        .update();
            }
            if (corrupt.isEmpty()) return clean;
            truth(
                    "statement-corruption",
                    ACCOUNT + ":" + date + ":" + version,
                    corrupt,
                    corrupt.equals("balance") ? "BANK_BALANCE_MISMATCH" : "FILE_INCOMPLETE");
            return header(date, version) + totals.details() + totals.trailer(corrupt);
        }));
    }

    private static String header(LocalDate date, int version) {
        return "H,BANK_STATEMENT," + ACCOUNT + "," + date + "," + date + "," + version + ",CAD\r\n";
    }

    private static Totals totals(LocalDate date, List<Line> lines) {
        long opening = 0;
        long credit = 0;
        long debit = 0;
        long count = 0;
        StringBuilder details = new StringBuilder();
        for (Line line : lines) {
            if (line.status().equals("BOOKED") && line.booking_date().isBefore(date)) {
                opening = line.direction().equals("CREDIT")
                        ? Math.addExact(opening, line.amount())
                        : Math.subtractExact(opening, line.amount());
            }
            if (!line.visible_date().equals(date) && !line.booking_date().equals(date)) continue;
            String status = date.isBefore(line.booking_date()) ? "PENDING" : line.status();
            count = Math.incrementExact(count);
            details.append("D,")
                    .append(csv(line.bank_txn_id()))
                    .append(',')
                    .append(line.direction())
                    .append(',')
                    .append(line.amount())
                    .append(',')
                    .append(line.currency())
                    .append(',')
                    .append(status)
                    .append(',')
                    .append(csv(line.reference()))
                    .append(',')
                    .append(line.booking_date())
                    .append(',')
                    .append(line.value_date())
                    .append(',')
                    .append(csv(line.memo()))
                    .append("\r\n");
            if (status.equals("BOOKED")) {
                if (line.direction().equals("CREDIT")) credit = Math.addExact(credit, line.amount());
                else debit = Math.addExact(debit, line.amount());
            }
        }
        return new Totals(
                details.toString(),
                count,
                credit,
                debit,
                opening,
                Math.subtractExact(Math.addExact(opening, credit), debit));
    }

    private static String csv(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private static String text(JsonNode node, String name, String fallback) {
        JsonNode value = node.path(name);
        if (value.isMissingNode()) return fallback;
        if (!value.isString()) throw invalid(name);
        return value.asText();
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bank " + reason);
    }

    private record Transfer(String id, long amount, JsonNode plan, LocalDate due) {}

    private record Statement(int version, String body, String content) {}

    public record Line(
            String bank_txn_id,
            String direction,
            long amount,
            String currency,
            String status,
            String reference,
            LocalDate booking_date,
            LocalDate value_date,
            LocalDate visible_date,
            String memo) {}

    private record Totals(String details, long count, long credit, long debit, long opening, long closing) {
        String trailer(String corrupt) {
            return "T," + (corrupt.equals("trailer_count") ? Math.incrementExact(count) : count) + ","
                    + (corrupt.equals("trailer_sum") ? Math.incrementExact(credit) : credit) + "," + debit + ","
                    + opening + ","
                    + (corrupt.equals("balance") ? Math.incrementExact(closing) : closing) + "\r\n";
        }
    }
}
