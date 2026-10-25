package dev.ledgerlab.reconciliation.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Historical facts only: never read a mutable bank matching status or account balance. */
@Component
public final class BankEvidence {
    private final JdbcClient jdbc;

    public BankEvidence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Batch(String id, long net, Instant occurred, String status, long transition) {}

    record Line(
            String id,
            int version,
            String file,
            String direction,
            String currency,
            String status,
            long amount,
            String reference,
            LocalDate booking,
            @Nullable Instant waitUntil) {}

    record Statement(String file, String account, LocalDate start, LocalDate end, long closing) {}

    record Posting(String entry, String type, String subject, int account, String side, long amount) {}

    record Snapshot(
            List<Batch> batches,
            List<Line> lines,
            List<Line> allLines,
            List<Statement> statements,
            List<Posting> postings) {
        boolean deposited(Line line) {
            return postings.stream()
                    .anyMatch(p -> p.subject().equals(line.id()) && p.type().equals("BANK_DEPOSIT_RECEIVED"));
        }

        boolean posted(Line line) {
            return postings.stream().anyMatch(p -> p.subject().equals(line.id()));
        }

        boolean waiting(Line line) {
            return line.status().equals("BOOKED")
                    && line.direction().equals("CREDIT")
                    && line.currency().equals("CAD")
                    && line.waitUntil() != null
                    && !posted(line);
        }

        boolean matched(Batch batch) {
            return lines.stream()
                    .anyMatch(l -> l.reference().equals(batch.id())
                            && l.amount() == batch.net()
                            && l.status().equals("BOOKED")
                            && l.direction().equals("CREDIT")
                            && l.currency().equals("CAD")
                            && deposited(l));
        }

        long unapplied(Line line) {
            long total = 0;
            for (Posting p : postings)
                if (p.account() == 2105
                        && (p.type().equals("UNAPPLIED_CASH_RETURNED")
                                ? returnedTo(p, line)
                                : p.subject().equals(line.id())))
                    total = Math.addExact(total, p.side().equals("C") ? p.amount() : Math.negateExact(p.amount()));
            return bounded(total);
        }

        private boolean returnedTo(Posting posting, Line credit) {
            return allLines.stream()
                    .anyMatch(debit -> debit.id().equals(posting.subject())
                            && debit.direction().equals("DEBIT")
                            && debit.status().equals("BOOKED")
                            && debit.reference().equals(credit.id()));
        }

        long cash(LocalDate end) {
            Map<String, LocalDate> dates = new LinkedHashMap<>();
            allLines.forEach(l -> dates.put(l.id(), l.booking()));
            long total = 0;
            for (Posting p : postings) {
                LocalDate date = dates.get(p.subject());
                if (p.account() == 1103
                        && date != null
                        && !date.isAfter(end)
                        && List.of("BANK_DEPOSIT_RECEIVED", "UNAPPLIED_CASH_RECEIVED", "UNAPPLIED_CASH_RETURNED")
                                .contains(p.type()))
                    total = Math.addExact(total, p.side().equals("D") ? p.amount() : Math.negateExact(p.amount()));
            }
            return bounded(total);
        }

        List<Map<String, Object>> refs(Batch batch) {
            var refs = new java.util.ArrayList<Map<String, Object>>();
            refs.add(Map.of("batch_id", batch.id(), "transition_id", batch.transition()));
            lines.stream().filter(l -> l.reference().equals(batch.id())).forEach(l -> refs.addAll(refs(l)));
            return refs;
        }

        List<Map<String, Object>> refs(Line line) {
            var refs = new java.util.ArrayList<Map<String, Object>>();
            refs.add(lineRef(line));
            allLines.stream()
                    .filter(debit -> debit.reference().equals(line.id())
                            && postings.stream()
                                    .anyMatch(p -> p.subject().equals(debit.id())
                                            && p.type().equals("UNAPPLIED_CASH_RETURNED")))
                    .forEach(debit -> refs.add(lineRef(debit)));
            return refs;
        }

        private Map<String, Object> lineRef(Line line) {
            return Map.of(
                    "bank_txn_id",
                    line.id(),
                    "version",
                    line.version(),
                    "file_id",
                    line.file(),
                    "entry_ids",
                    postings.stream()
                            .filter(p -> p.subject().equals(line.id()))
                            .map(Posting::entry)
                            .distinct()
                            .toList());
        }
    }

    Snapshot read(ReconRule.Context context) {
        OffsetDateTime asOf = context.request().evidenceAsOf().atOffset(ZoneOffset.UTC);
        List<Batch> batches = jdbc.sql("""
                SELECT b.batch_id, b.net, b.occurred_at, h.to_status, h.id
                FROM core.settlement_batch b
                JOIN LATERAL (SELECT to_status, id FROM core.batch_transition
                    WHERE subject_id=b.batch_id AND recorded_at < :asOf
                    ORDER BY recorded_at DESC, id DESC LIMIT 1) h ON true
                WHERE b.recorded_at < :asOf AND b.occurred_at < :cutoff
                  AND EXISTS (SELECT 1 FROM core.batch_transition c WHERE c.subject_id=b.batch_id
                    AND c.to_status='CONFIRMED' AND c.recorded_at < :asOf)
                ORDER BY b.batch_id
                """)
                .param("asOf", asOf)
                .param("cutoff", context.request().cutoffAt().atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Batch(
                        rs.getString(1),
                        rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class).toInstant(),
                        rs.getString(4),
                        rs.getLong(5)))
                .list();
        List<Line> all = jdbc.sql("""
                SELECT DISTINCT ON (l.bank_txn_id) l.* FROM recon.bank_line l
                JOIN io.imported_file f ON f.id=l.file_id
                WHERE l.recorded_at < :asOf AND f.received_at < :asOf AND f.recorded_at < :asOf
                ORDER BY l.bank_txn_id, l.version DESC
                """)
                .param("asOf", asOf)
                .query((rs, n) -> {
                    var due = rs.getObject("wait_until", OffsetDateTime.class);
                    return new Line(
                            rs.getString("bank_txn_id"),
                            rs.getInt("version"),
                            rs.getString("file_id"),
                            rs.getString("direction"),
                            rs.getString("currency"),
                            rs.getString("status"),
                            rs.getLong("amount"),
                            rs.getString("reference"),
                            rs.getObject("booking_date", LocalDate.class),
                            due == null ? null : due.toInstant());
                })
                .list();
        // Rank within the selected inputs, so an explicitly pinned older PENDING version stays PENDING.
        List<Line> selected = context.inputHashes().isEmpty()
                ? List.of()
                : jdbc.sql("""
                SELECT DISTINCT ON (l.bank_txn_id) l.* FROM recon.bank_line l
                WHERE l.file_id IN (:files) AND l.recorded_at < :asOf
                ORDER BY l.bank_txn_id, l.version DESC
                """)
                        .param("files", context.inputHashes().keySet())
                        .param("asOf", asOf)
                        .query((rs, n) -> {
                            var due = rs.getObject("wait_until", OffsetDateTime.class);
                            return new Line(
                                    rs.getString("bank_txn_id"),
                                    rs.getInt("version"),
                                    rs.getString("file_id"),
                                    rs.getString("direction"),
                                    rs.getString("currency"),
                                    rs.getString("status"),
                                    rs.getLong("amount"),
                                    rs.getString("reference"),
                                    rs.getObject("booking_date", LocalDate.class),
                                    due == null ? null : due.toInstant());
                        })
                        .list();
        List<Statement> statements = context.inputHashes().isEmpty()
                ? List.of()
                : jdbc.sql("""
                SELECT DISTINCT ON (account_ref, period_start, period_end) * FROM recon.bank_statement
                WHERE file_id IN (:files) AND recorded_at < :asOf
                ORDER BY account_ref, period_start, period_end, version DESC, recorded_at DESC, file_id DESC
                """)
                        .param("files", context.inputHashes().keySet())
                        .param("asOf", asOf)
                        .query((rs, n) -> new Statement(
                                rs.getString("file_id"),
                                rs.getString("account_ref"),
                                rs.getObject("period_start", LocalDate.class),
                                rs.getObject("period_end", LocalDate.class),
                                rs.getLong("closing_balance")))
                        .list();
        List<Posting> postings = jdbc.sql("""
                SELECT e.id, e.entry_type, e.subject_id, p.account_code, p.side, p.amount
                FROM ledger.journal_entry e JOIN ledger.posting p ON p.entry_id=e.id
                WHERE e.recorded_at < ? AND e.subject_type='bank_txn' AND p.account_code IN (1103,2105)
                ORDER BY e.id, p.line_no
                """)
                .param(asOf)
                .query((rs, n) -> new Posting(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getString(5),
                        rs.getLong(6)))
                .list();
        return new Snapshot(batches, selected, all, statements, postings);
    }

    static long bounded(long value) {
        if (value <= -9007199254740992L || value >= 9007199254740992L)
            throw new ArithmeticException("Reconciliation amount out of range");
        return value;
    }
}
