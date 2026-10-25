package dev.ledgerlab.settlement;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.shared.BusinessCalendar;
import dev.ledgerlab.shared.IdGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Cash application belongs to settlement. All writes use the app role and the funds lock. */
@Service
public final class BankStatements {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final IngestionIssues issues;
    private final Clock clock;
    private final IdGenerator ids;
    private final BusinessCalendar calendar;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final int waitDays;

    public BankStatements(
            JdbcClient jdbc,
            Ledger ledger,
            IngestionIssues issues,
            Clock clock,
            IdGenerator ids,
            BusinessCalendar calendar,
            ObjectMapper mapper,
            PlatformTransactionManager manager,
            @Value("${ledgerlab.bank.wait-business-days:2}") int waitDays) {
        if (waitDays <= 0) throw new IllegalArgumentException("Bank wait days must be positive");
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.issues = issues;
        this.clock = clock;
        this.ids = ids;
        this.calendar = calendar;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
        this.waitDays = waitDays;
    }

    public ImportResult importBankStatement(String sourceName, byte[] content) {
        byte[] raw = content.clone();
        String hash = hash(raw);
        Instant received = clock.instant();
        BankCsv.Statement parsed = null;
        String rejection = null;
        try {
            parsed = BankCsv.parse(raw);
        } catch (BankCsv.InvalidFile e) {
            rejection = e.getMessage();
        }
        BankCsv.Statement statement = parsed;
        String reason = rejection;
        return Objects.requireNonNull(tx.execute(ignored -> {
            ledger.lockFunds();
            var existing = jdbc.sql("SELECT id, status, reject_reason FROM io.imported_file WHERE sha256 = :hash")
                    .param("hash", hash)
                    .query((rs, row) -> new ImportResult(rs.getString(1), rs.getString(2), rs.getString(3)))
                    .optional();
            if (existing.isPresent()) return existing.get();
            Instant now = clock.instant();
            String file = ids.next("file");
            String status = statement == null ? "REJECTED" : "ACCEPTED";
            jdbc.sql("""
                    INSERT INTO io.imported_file
                        (id, kind, source_name, sha256, statement_date, version, status, reject_reason,
                         raw, received_at, recorded_at)
                    VALUES (:id, 'BANK_STATEMENT', :source, :hash, :date, :version, :status, :reason,
                            :raw, :received, :now)
                    """)
                    .param("id", file)
                    .param("source", sourceName)
                    .param("hash", hash)
                    .param("date", statement == null ? null : statement.date())
                    .param("version", statement == null ? null : statement.version())
                    .param("status", status)
                    .param("reason", reason)
                    .param("raw", raw)
                    .param("received", utc(received))
                    .param("now", utc(now))
                    .update();
            if (statement != null) {
                jdbc.sql("""
                        INSERT INTO recon.bank_statement
                            (file_id, account_ref, currency, opening_balance, closing_balance,
                             period_start, period_end, version, recorded_at)
                        VALUES (:file, :account, 'CAD', :opening, :closing, :date, :date, :version, :now)
                        """)
                        .param("file", file)
                        .param("account", statement.account())
                        .param("opening", statement.opening())
                        .param("closing", statement.closing())
                        .param("date", statement.date())
                        .param("version", statement.version())
                        .param("now", utc(now))
                        .update();
                for (BankCsv.Line line : statement.lines()) importLine(file, line, now);
            }
            return new ImportResult(file, status, reason);
        }));
    }

    private void importLine(String file, BankCsv.Line line, Instant now) {
        var old = jdbc.sql("SELECT * FROM recon.bank_line WHERE bank_txn_id = :id ORDER BY version DESC LIMIT 1")
                .param("id", line.id())
                .query(BankStatements::stored)
                .optional();
        int version = 1;
        if (old.isPresent()) {
            Stored previous = old.get();
            if (previous.line().equals(line)) return;
            if (!previous.line().status().equals("PENDING")
                    || !line.status().equals("BOOKED")
                    || !previous.line().sameExceptStatus(line)) {
                issue("STATEMENT_VERSION_CONFLICT", line, previous.line(), file, previous.version(), null);
                return;
            }
            version = Math.incrementExact(previous.version());
        }
        Decision decision = decide(line, now, null);
        jdbc.sql("""
                INSERT INTO recon.bank_line
                    (bank_txn_id, version, file_id, direction, currency, status, amount, reference,
                     booking_date, value_date, memo, match_status, matched_batch_id, wait_until, recorded_at)
                VALUES (:id, :version, :file, :direction, :currency, :status, :amount, :reference,
                        :booking, :value, :memo, :match, :batch, :wait, :now)
                """)
                .param("id", line.id())
                .param("version", version)
                .param("file", file)
                .param("direction", line.direction())
                .param("currency", line.currency())
                .param("status", line.status())
                .param("amount", line.amount())
                .param("reference", line.reference())
                .param("booking", line.bookingDate())
                .param("value", line.valueDate())
                .param("memo", line.memo())
                .param("match", decision.status())
                .param("batch", decision.matchedBatch())
                .param("wait", decision.due() == null ? null : utc(decision.due()))
                .param("now", utc(now))
                .update();
        apply(new Stored(line, version, file, decision.due()), decision, now);
    }

    /** Called synchronously after BATCH_SETTLED, in the confirming transaction. */
    void wakeWaiting(String batchId) {
        ledger.lockFunds();
        rematch(jdbc.sql("""
                SELECT * FROM recon.bank_line WHERE match_status = 'WAITING' AND reference = :batch
                ORDER BY import_order
                """)
                .param("batch", batchId)
                .query(BankStatements::stored)
                .list());
    }

    /** Scheduling can be disabled; tests call this entry point with an injected clock. */
    public void expireWaiting() {
        tx.executeWithoutResult(ignored -> {
            ledger.lockFunds();
            rematch(jdbc.sql("""
                    SELECT * FROM recon.bank_line WHERE match_status = 'WAITING' AND wait_until <= :now
                    ORDER BY import_order
                    """)
                    .param("now", utc(clock.instant()))
                    .query(BankStatements::stored)
                    .list());
        });
    }

    private void rematch(List<Stored> waiting) {
        Instant now = clock.instant();
        for (Stored row : waiting) {
            Decision decision = decide(row.line(), now, row.due());
            if (decision.status().equals("WAITING")) continue;
            jdbc.sql("""
                    UPDATE recon.bank_line SET match_status = :status, matched_batch_id = :batch
                    WHERE bank_txn_id = :id AND version = :version
                    """)
                    .param("status", decision.status())
                    .param("batch", decision.matchedBatch())
                    .param("id", row.line().id())
                    .param("version", row.version())
                    .update();
            apply(row, decision, now);
        }
    }

    private Decision decide(BankCsv.Line line, Instant now, @Nullable Instant storedDeadline) {
        if (line.status().equals("PENDING")) return new Decision("IGNORED_PENDING", null, null, null, null);
        if (line.direction().equals("DEBIT") && line.currency().equals("CAD")) {
            var credit = jdbc.sql("""
                    SELECT * FROM recon.bank_line WHERE bank_txn_id=:reference
                    ORDER BY version DESC LIMIT 1 FOR UPDATE
                    """)
                    .param("reference", line.reference())
                    .query((rs, n) -> rs.getString("match_status").equals("UNAPPLIED")
                            && rs.getString("direction").equals("CREDIT")
                            && rs.getString("status").equals("BOOKED")
                            && rs.getString("currency").equals("CAD")
                            && rs.getLong("amount") == line.amount())
                    .optional();
            if (credit.orElse(false)) return new Decision("RETURN_POSTED", null, null, null, null);
        }
        var batch = jdbc.sql("SELECT batch_id, status, net FROM core.settlement_batch WHERE batch_id = :ref")
                .param("ref", line.reference())
                .query((rs, row) -> new Batch(rs.getString(1), rs.getString(2), rs.getLong(3)))
                .optional();
        Batch candidate = batch.orElse(null);
        if (line.direction().equals("DEBIT") || !line.currency().equals("CAD"))
            return new Decision("UNMATCHED_NO_POSTING", null, "UNMATCHED_BANK_LINE", candidate, null);
        if (candidate != null && candidate.status().equals("CONFIRMED")) {
            boolean used = jdbc.sql("SELECT count(*) FROM recon.bank_line WHERE matched_batch_id = :batch")
                            .param("batch", candidate.id())
                            .query(Long.class)
                            .single()
                    > 0;
            if (used) return new Decision("UNAPPLIED", null, "UNMATCHED_BANK_LINE", candidate, null);
            if (line.amount() == candidate.net()) return new Decision("MATCHED", candidate.id(), null, candidate, null);
            return new Decision("UNAPPLIED", null, "DEPOSIT_AMOUNT_MISMATCH", candidate, null);
        }
        if (candidate != null && candidate.status().equals("PAYOUT_FAILED"))
            return new Decision("UNAPPLIED", null, "BATCH_STATE_CONFLICT", candidate, null);
        boolean early = line.reference().startsWith("po_")
                && line.reference().length() > 3
                && (candidate == null
                        || List.of("ANNOUNCED", "RECEIVED", "HELD").contains(candidate.status()));
        if (early) {
            Instant due = storedDeadline != null
                    ? storedDeadline
                    : calendar.dueAt(calendar.startOfDay(line.bookingDate()), waitDays);
            if (now.isBefore(due)) return new Decision("WAITING", null, null, candidate, due);
        }
        return new Decision("UNAPPLIED", null, "UNMATCHED_BANK_LINE", candidate, null);
    }

    private void apply(Stored row, Decision decision, Instant now) {
        BankCsv.Line line = row.line();
        if (decision.status().equals("RETURN_POSTED")) {
            jdbc.sql("""
                    UPDATE recon.bank_line SET match_status='RETURNED'
                    WHERE bank_txn_id=:credit AND version=(
                        SELECT max(version) FROM recon.bank_line WHERE bank_txn_id=:credit)
                    """).param("credit", line.reference()).update();
            ledger.post(new EntryRequest(
                    EntryType.UNAPPLIED_CASH_RETURNED,
                    "bank_txn:" + line.id(),
                    calendar.startOfDay(line.bookingDate()),
                    SubjectType.BANK_TXN,
                    line.id(),
                    List.of(
                            PostingLine.debit(Account.UNAPPLIED_CASH, line.amount()),
                            PostingLine.credit(Account.BANK_CASH, line.amount()))));
            issues.resolveBankReturn(
                    line.reference(),
                    line.id(),
                    mapper.writeValueAsString(List.of(
                            Map.of("table", "io.imported_file", "id", row.file()),
                            Map.of("table", "recon.bank_line", "bank_txn_id", line.id(), "version", row.version()))));
        }
        boolean matched = decision.status().equals("MATCHED");
        if (matched || decision.status().equals("UNAPPLIED")) {
            ledger.post(new EntryRequest(
                    matched ? EntryType.BANK_DEPOSIT_RECEIVED : EntryType.UNAPPLIED_CASH_RECEIVED,
                    "bank_txn:" + line.id(),
                    calendar.startOfDay(line.bookingDate()),
                    SubjectType.BANK_TXN,
                    line.id(),
                    List.of(
                            PostingLine.debit(Account.BANK_CASH, line.amount()),
                            PostingLine.credit(
                                    matched ? Account.SETTLEMENT_IN_TRANSIT : Account.UNAPPLIED_CASH, line.amount()))));
        }
        if (matched) {
            jdbc.sql("UPDATE core.settlement_batch SET status = 'BANKED', updated_at = :now WHERE batch_id = :batch")
                    .param("now", utc(now))
                    .param("batch", decision.matchedBatch())
                    .update();
            jdbc.sql("""
                    INSERT INTO core.batch_transition
                        (subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at)
                    VALUES (:batch, 'CONFIRMED', 'BANKED', 'bank_txn', :id, :occurred, :now)
                    """)
                    .param("batch", decision.matchedBatch())
                    .param("id", line.id())
                    .param("occurred", utc(calendar.startOfDay(line.bookingDate())))
                    .param("now", utc(now))
                    .update();
        }
        if (decision.category() != null)
            issue(
                    decision.category(),
                    line,
                    decision.candidate() == null ? Map.of() : decision.candidate(),
                    row.file(),
                    row.version(),
                    decision.candidate());
    }

    private void issue(
            String category, BankCsv.Line line, Object expected, String file, int version, @Nullable Batch candidate) {
        var refs = new ArrayList<Map<String, Object>>();
        refs.add(Map.of("table", "io.imported_file", "id", file));
        refs.add(Map.of("table", "recon.bank_line", "bank_txn_id", line.id(), "version", version));
        if (candidate != null) refs.add(Map.of("table", "core.settlement_batch", "id", candidate.id()));
        issues.open(
                category,
                "bank_txn",
                line.id(),
                mapper.writeValueAsString(expected),
                mapper.writeValueAsString(line),
                mapper.writeValueAsString(refs));
    }

    private static Stored stored(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var deadline = rs.getTimestamp("wait_until");
        return new Stored(
                new BankCsv.Line(
                        rs.getString("bank_txn_id"),
                        rs.getString("direction"),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getString("reference"),
                        rs.getDate("booking_date").toLocalDate(),
                        rs.getDate("value_date").toLocalDate(),
                        rs.getString("memo")),
                rs.getInt("version"),
                rs.getString("file_id"),
                deadline == null ? null : deadline.toInstant());
    }

    private static String hash(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    public record ImportResult(
            String fileId, String status, @Nullable String rejectReason) {}

    private record Stored(
            BankCsv.Line line,
            int version,
            String file,
            @Nullable Instant due) {}

    private record Batch(String id, String status, long net) {}

    private record Decision(
            String status,
            @Nullable String matchedBatch,
            @Nullable String category,
            @Nullable Batch candidate,
            @Nullable Instant due) {}
}
