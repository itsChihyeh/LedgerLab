package dev.ledgerlab.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Independent P2 oracle: JDK only; no production calculations, parsers or database reads. */
final class SettlementModel {
    static final ZoneId ZONE = ZoneId.of("America/Toronto");
    // Generated scenarios start in October/November 2026 and advance at most 24 weeks.
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.parse("2026-10-12"), LocalDate.parse("2026-11-11"),
            LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-28"),
            LocalDate.parse("2027-01-01"), LocalDate.parse("2027-03-26"));

    record Line(String id, String type, String reference, long gross, long fee) {
        long net() {
            return Math.subtractExact(gross, fee);
        }
    }

    static final class Batch {
        final String id;
        final List<Line> lines;
        final Instant created;
        final long gross;
        final long fees;
        final long returned;
        final long net;
        String status = "ABSENT";

        Batch(String id, List<Line> lines, Instant created) {
            this.id = id;
            this.lines = List.copyOf(lines);
            this.created = created;
            gross = sum(lines.stream()
                    .filter(l -> l.type().equals("charge"))
                    .map(Line::gross)
                    .toList());
            fees = sum(lines.stream().map(Line::fee).toList());
            returned = sum(lines.stream()
                    .filter(l -> l.type().equals("payout_failure"))
                    .map(Line::gross)
                    .toList());
            net = Math.addExact(Math.subtractExact(gross, fees), returned);
        }
    }

    record Posting(int account, String side, long amount) {}

    record Entry(String type, List<Posting> postings) {}

    record Issue(String category, String subjectType, String subjectId) {}

    record BankInput(String id, String reference, long amount, String status, LocalDate date) {
        BankInput booked() {
            return new BankInput(id, reference, amount, "BOOKED", date);
        }
    }

    static final class Bank {
        final BankInput input;
        final int version;
        String match;
        String matchedBatch;
        Instant due;

        Bank(BankInput input, int version) {
            this.input = input;
            this.version = version;
        }
    }

    final Map<String, Batch> batches = new LinkedHashMap<>();
    final Map<String, String> paymentBatches = new TreeMap<>();
    final Map<String, Long> actualFees = new TreeMap<>();
    final Map<String, Entry> entries = new TreeMap<>();
    final Map<String, Bank> banks = new LinkedHashMap<>();
    final List<Bank> bankVersions = new ArrayList<>();
    final Set<Issue> issues = new java.util.HashSet<>();
    final Map<String, PaymentModel.Outcome> outcomes = new TreeMap<>();

    static long fee(long amount) {
        return Math.addExact(30, Math.addExact(Math.multiplyExact(amount, 29), 500) / 1000);
    }

    static long sum(Iterable<Long> values) {
        long total = 0;
        for (long value : values) total = Math.addExact(total, value);
        return total;
    }

    String event(Batch batch, String type, Instant now) {
        if (type.equals("payout.created")) {
            if (!batch.status.equals("ABSENT")) return "DUPLICATE_FACT";
            batch.status = "ANNOUNCED";
            return "APPLIED";
        }
        if (type.equals("payout.failed")) {
            if (batch.status.equals("PAYOUT_FAILED")) return "DUPLICATE_FACT";
            if (batch.status.equals("BANKED")) {
                issues.add(new Issue("BATCH_STATE_CONFLICT", "batch", batch.id));
                return "BUSINESS_CONFLICT";
            }
            if (batch.status.equals("CONFIRMED")) {
                batch.status = "PAYOUT_FAILED";
                post(
                        "batch:" + batch.id + ":payout_failed",
                        "BATCH_PAYOUT_FAILED",
                        new Posting(1101, "D", batch.net),
                        new Posting(1102, "C", batch.net));
            }
            return "APPLIED";
        }
        if (!List.of("ABSENT", "ANNOUNCED").contains(batch.status)) return "DUPLICATE_FACT";
        batch.status = "CONFIRMED";
        for (Line line : batch.lines) {
            if (line.type().equals("charge")) {
                paymentBatches.put(line.reference(), batch.id);
                actualFees.put(line.reference(), line.fee());
            }
        }
        long available = Math.subtractExact(batch.gross, batch.fees);
        post(
                "batch:" + batch.id + ":settled",
                "BATCH_SETTLED",
                new Posting(1102, "D", batch.net),
                new Posting(2101, "D", batch.fees),
                new Posting(1101, "C", Math.addExact(batch.net, batch.fees)),
                new Posting(2101, "D", available),
                new Posting(2102, "C", available));
        bankVersions.stream()
                .filter(b -> "WAITING".equals(b.match) && b.input.reference().equals(batch.id))
                .forEach(b -> match(b, now));
        return "APPLIED";
    }

    void post(String key, String type, Posting... postings) {
        List<Posting> nonzero =
                java.util.Arrays.stream(postings).filter(p -> p.amount() != 0).toList();
        if (entries.putIfAbsent(key, new Entry(type, nonzero)) != null)
            throw new AssertionError("Oracle posted twice: " + key);
    }

    void bank(BankInput input, Instant now) {
        Bank previous = banks.get(input.id());
        if (previous != null && previous.input.equals(input)) return;
        if (previous != null
                && !(previous.input.status().equals("PENDING")
                        && previous.input.booked().equals(input)))
            throw new AssertionError("Generator supplied an unsupported bank version");
        Bank next = new Bank(input, previous == null ? 1 : Math.incrementExact(previous.version));
        banks.put(input.id(), next);
        bankVersions.add(next);
        match(next, now);
    }

    void expire(Instant now) {
        bankVersions.stream()
                .filter(b -> "WAITING".equals(b.match) && !now.isBefore(b.due))
                .forEach(b -> match(b, now));
    }

    private void match(Bank bank, Instant now) {
        BankInput line = bank.input;
        if (line.status().equals("PENDING")) {
            bank.match = "IGNORED_PENDING";
            return;
        }
        Batch batch = batches.get(line.reference());
        String status = batch == null ? "ABSENT" : batch.status;
        String category = "UNMATCHED_BANK_LINE";
        if (status.equals("CONFIRMED")) {
            if (line.amount() == batch.net) {
                bank.match = "MATCHED";
                bank.matchedBatch = batch.id;
                batch.status = "BANKED";
                post(
                        "bank_txn:" + line.id(),
                        "BANK_DEPOSIT_RECEIVED",
                        new Posting(1103, "D", line.amount()),
                        new Posting(1102, "C", line.amount()));
                return;
            }
            category = "DEPOSIT_AMOUNT_MISMATCH";
        } else if (status.equals("PAYOUT_FAILED")) category = "BATCH_STATE_CONFLICT";
        else if (List.of("ABSENT", "ANNOUNCED").contains(status)) {
            Instant deadline = due(line.date());
            if (now.isBefore(deadline)) {
                bank.match = "WAITING";
                bank.due = deadline;
                return;
            }
        }
        bank.match = "UNAPPLIED";
        issues.add(new Issue(category, "bank_txn", line.id()));
        post(
                "bank_txn:" + line.id(),
                "UNAPPLIED_CASH_RECEIVED",
                new Posting(1103, "D", line.amount()),
                new Posting(2105, "C", line.amount()));
    }

    static Instant due(LocalDate booking) {
        LocalDate date = booking;
        int remaining = 2;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !HOLIDAYS.contains(date)) remaining--;
        }
        return date.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    Map<Integer, Long> deltas() {
        Map<Integer, Long> values = new TreeMap<>();
        entries.values()
                .forEach(e -> e.postings().forEach(p -> {
                    boolean positive =
                            p.account() < 2000 ? p.side().equals("D") : p.side().equals("C");
                    values.merge(p.account(), positive ? p.amount() : Math.negateExact(p.amount()), Math::addExact);
                }));
        return values;
    }
}
