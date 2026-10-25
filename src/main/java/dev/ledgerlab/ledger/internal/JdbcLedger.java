package dev.ledgerlab.ledger.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.AlreadyCorrectedException;
import dev.ledgerlab.ledger.CannotReverseReversalException;
import dev.ledgerlab.ledger.CorrectionDetails;
import dev.ledgerlab.ledger.CorrectionEntry;
import dev.ledgerlab.ledger.CorrectionReason;
import dev.ledgerlab.ledger.CorrectionRequest;
import dev.ledgerlab.ledger.CorrectionTarget;
import dev.ledgerlab.ledger.CorrectionType;
import dev.ledgerlab.ledger.DuplicateSourceKeyException;
import dev.ledgerlab.ledger.EntryNotFoundException;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.NegativeBalanceException;
import dev.ledgerlab.ledger.PostedEntry;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.RecordedCorrection;
import dev.ledgerlab.ledger.Side;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit SQL on the critical path (AGENTS.md). Joins the caller's Spring-managed transaction through the
 * {@link DataSource}; it never starts or commits one itself.
 */
@Component
public class JdbcLedger implements Ledger {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IdGenerator ids;
    private final Runnable firstPostingWritten;
    /** Bound to the transaction while it holds the funds lock; one key per ledger instance. */
    private final Object fundsLockKey = new Object();

    @Autowired
    public JdbcLedger(DataSource dataSource, Clock clock, IdGenerator ids) {
        this(dataSource, clock, ids, () -> {});
    }

    /** Package-local AC-1.10 fault injection; production always uses the no-op callback. */
    JdbcLedger(DataSource dataSource, Clock clock, IdGenerator ids, Runnable firstPostingWritten) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
        this.ids = ids;
        this.firstPostingWritten = firstPostingWritten;
    }

    @Override
    public void lockFunds() {
        requireTransaction();
        if (TransactionSynchronizationManager.hasResource(fundsLockKey)) {
            return;
        }
        jdbc.sql("SELECT id FROM ledger.funds_lock WHERE id = 1 FOR UPDATE")
                .query(Short.class)
                .single();
        TransactionSynchronizationManager.bindResource(fundsLockKey, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(fundsLockKey);
            }
        });
    }

    @Override
    public void assertFundsLocked() {
        requireTransaction();
        if (!TransactionSynchronizationManager.hasResource(fundsLockKey)) {
            throw new IllegalStateException("The caller must already hold the funds lock");
        }
    }

    @Override
    public long balance(Account account) {
        assertFundsLocked();
        long balance = jdbc.sql("SELECT balance FROM ledger.account WHERE code = :code")
                .param("code", account.code())
                .query(Long.class)
                .single();
        if (balance <= -9007199254740992L || balance >= 9007199254740992L) {
            throw new ArithmeticException("Account balance exceeds JSON safe integer range");
        }
        return balance;
    }

    @Override
    public PostedEntry post(EntryRequest request) {
        requireTransaction();
        if (!TransactionSynchronizationManager.hasResource(fundsLockKey)) {
            throw new IllegalStateException("lockFunds() must be the first lock of a money-moving transaction");
        }
        OffsetDateTime recordedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        return writeEntry(
                new WriteEntry(
                        request.type(),
                        request.sourceKey(),
                        request.occurredAt(),
                        request.subjectType(),
                        request.subjectId(),
                        request.lines(),
                        null,
                        null),
                recordedAt);
    }

    private PostedEntry writeEntry(WriteEntry request, OffsetDateTime recordedAt) {
        Map<Account, Balance> balances = lockAccounts(request.lines());
        List<PlannedPosting> planned = new ArrayList<>(request.lines().size());
        for (PostingLine line : request.lines()) {
            Balance before = Objects.requireNonNull(balances.get(line.account()), "account was locked above");
            Balance after = new Balance(
                    Math.addExact(before.amount(), line.balanceDelta()), Math.incrementExact(before.version()));
            balances.put(line.account(), after);
            planned.add(new PlannedPosting(line, after));
        }
        // Checked before anything is written; the CHECK on ledger.account is the backstop (INV-5).
        balances.forEach((account, balance) -> {
            if (!account.allowNegative() && balance.amount() < 0) {
                throw new NegativeBalanceException(account, balance.amount());
            }
        });

        String entryId = ids.next("je");
        long seq;
        try {
            seq = jdbc.sql("""
                            INSERT INTO ledger.journal_entry
                                (id, entry_type, source_key, posting_count, total_amount,
                                 occurred_at, recorded_at, subject_type, subject_id, reverses_entry_id, correction_id)
                            VALUES (:id, :type, :sourceKey, :count, :total, :occurredAt, :recordedAt,
                                    :subjectType, :subjectId, :reversesEntryId, :correctionId)
                            RETURNING seq
                            """)
                    .param("id", entryId)
                    .param("type", request.type().name())
                    .param("sourceKey", request.sourceKey())
                    .param("count", (short) planned.size())
                    .param("total", request.totalAmount())
                    .param("occurredAt", request.occurredAt().atOffset(ZoneOffset.UTC))
                    .param("recordedAt", recordedAt)
                    .param("subjectType", request.subjectType().dbValue())
                    .param("subjectId", request.subjectId())
                    .param("reversesEntryId", request.reversesEntryId(), java.sql.Types.VARCHAR)
                    .param("correctionId", request.correctionId(), java.sql.Types.VARCHAR)
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw new DuplicateSourceKeyException(request.sourceKey(), e);
        }

        short lineNo = 0;
        for (PlannedPosting posting : planned) {
            lineNo++;
            jdbc.sql("""
                            INSERT INTO ledger.posting
                                (id, entry_id, line_no, account_code, side, amount, account_version,
                                 balance_after, recorded_at)
                            VALUES (:id, :entryId, :lineNo, :account, :side, :amount, :version, :balanceAfter,
                                    :recordedAt)
                            """)
                    .param("id", ids.next("pst"))
                    .param("entryId", entryId)
                    .param("lineNo", lineNo)
                    .param("account", posting.line().account().code())
                    .param("side", posting.line().side().dbValue())
                    .param("amount", posting.line().amount())
                    .param("version", posting.after().version())
                    .param("balanceAfter", posting.after().amount())
                    .param("recordedAt", recordedAt)
                    .update();
            if (lineNo == 1) {
                firstPostingWritten.run();
            }
        }

        Map<Account, Long> balancesAfter = new EnumMap<>(Account.class);
        balances.forEach((account, balance) -> {
            jdbc.sql("UPDATE ledger.account SET balance = :balance, version = :version WHERE code = :code")
                    .param("balance", balance.amount())
                    .param("version", balance.version())
                    .param("code", account.code())
                    .update();
            balancesAfter.put(account, balance.amount());
        });
        return new PostedEntry(entryId, seq, balancesAfter);
    }

    @Override
    public RecordedCorrection recordCorrection(CorrectionRequest request) {
        assertFundsLocked();
        // Validate every target before writing any audit rows; the funds lock serializes other corrections.
        List<CorrectionEntry> reversed = new ArrayList<>();
        for (String id : request.reversedEntryIds()) {
            reversed.add(requireTarget(id, false));
        }
        for (String id : request.appliedEntryIds()) {
            requireTarget(id, true);
        }
        String correctionId = ids.next("cor");
        OffsetDateTime recordedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        List<WriteEntry> entries = new ArrayList<>();
        for (CorrectionEntry original : reversed) {
            List<PostingLine> mirror = original.postings().stream()
                    .map(p ->
                            new PostingLine(p.account(), p.side() == Side.DEBIT ? Side.CREDIT : Side.DEBIT, p.amount()))
                    .toList();
            entries.add(new WriteEntry(
                    EntryType.REVERSAL,
                    "reversal:" + original.entryId(),
                    request.occurredAt(),
                    original.subjectType(),
                    original.subjectId(),
                    mirror,
                    original.entryId(),
                    correctionId));
        }
        if (!request.correctionLines().isEmpty()) {
            entries.add(new WriteEntry(
                    EntryType.CORRECTION,
                    "correction:" + correctionId,
                    request.occurredAt(),
                    request.subjectType(),
                    request.subjectId(),
                    request.correctionLines(),
                    null,
                    correctionId));
        }
        // Lock the union before writing the first entry, including accounts used only by the replacement.
        lockAccounts(entries.stream().flatMap(e -> e.lines().stream()).toList());
        jdbc.sql("""
                INSERT INTO ledger.correction
                    (id, correction_type, reason_code, reason_note, evidence_refs, params,
                     subject_type, subject_id, requested_by, recorded_at)
                VALUES (:id, :type, :reason, :note, CAST(:evidence AS jsonb), CAST(:params AS jsonb),
                    :subjectType, :subjectId, :requestedBy, :recordedAt)
                """)
                .param("id", correctionId)
                .param("type", request.type().name())
                .param("reason", request.reasonCode().name())
                .param("note", request.reasonNote())
                .param("evidence", request.evidenceRefs())
                .param("params", request.params())
                .param("subjectType", request.subjectType().dbValue())
                .param("subjectId", request.subjectId())
                .param("requestedBy", request.requestedBy())
                .param("recordedAt", recordedAt)
                .update();
        for (String id : request.reversedEntryIds()) {
            insertTarget(correctionId, id, CorrectionTarget.Role.REVERSED, recordedAt);
        }
        for (String id : request.appliedEntryIds()) {
            insertTarget(correctionId, id, CorrectionTarget.Role.APPLIED, recordedAt);
        }
        List<PostedEntry> posted = new ArrayList<>();
        for (WriteEntry entry : entries) {
            posted.add(writeEntry(entry, recordedAt));
        }
        return new RecordedCorrection(correctionId, posted);
    }

    private CorrectionEntry requireTarget(String id, boolean applied) {
        CorrectionEntry entry = readEntry(id).orElseThrow(() -> new EntryNotFoundException(id));
        if (entry.type() == EntryType.REVERSAL) throw new CannotReverseReversalException(id);
        findCorrectionForEntry(id).ifPresent(target -> {
            throw new AlreadyCorrectedException(id, target.correctionId());
        });
        if (applied && entry.type() != EntryType.UNAPPLIED_CASH_RECEIVED) {
            throw new IllegalArgumentException("APPLIED target must be UNAPPLIED_CASH_RECEIVED: " + id);
        }
        return entry;
    }

    private void insertTarget(
            String correctionId, String entryId, CorrectionTarget.Role role, OffsetDateTime recordedAt) {
        jdbc.sql("""
                INSERT INTO ledger.correction_target (correction_id, entry_id, role, recorded_at)
                VALUES (:correctionId, :entryId, :role, :recordedAt)
                """)
                .param("correctionId", correctionId)
                .param("entryId", entryId)
                .param("role", role.name())
                .param("recordedAt", recordedAt)
                .update();
    }

    @Override
    public Optional<CorrectionTarget> findCorrectionForEntry(String entryId) {
        requireTransaction();
        return jdbc.sql(
                        "SELECT correction_id, entry_id, role, recorded_at FROM ledger.correction_target WHERE entry_id = :id")
                .param("id", entryId)
                .query((rs, row) -> new CorrectionTarget(
                        rs.getString("correction_id"),
                        rs.getString("entry_id"),
                        CorrectionTarget.Role.valueOf(rs.getString("role")),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public Optional<CorrectionDetails> findCorrection(String correctionId) {
        requireTransaction();
        return jdbc.sql("""
                SELECT id, correction_type, reason_code, reason_note, evidence_refs, params,
                    subject_type, subject_id, requested_by, recorded_at
                FROM ledger.correction WHERE id = :id
                """)
                .param("id", correctionId)
                .query((rs, row) -> new CorrectionDetails(
                        rs.getString("id"),
                        CorrectionType.valueOf(rs.getString("correction_type")),
                        CorrectionReason.valueOf(rs.getString("reason_code")),
                        rs.getString("reason_note"),
                        rs.getString("evidence_refs"),
                        rs.getString("params"),
                        SubjectType.valueOf(rs.getString("subject_type").toUpperCase(Locale.ROOT)),
                        rs.getString("subject_id"),
                        rs.getString("requested_by"),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        jdbc
                                .sql(
                                        "SELECT entry_id FROM ledger.correction_target WHERE correction_id = :id ORDER BY entry_id")
                                .param("id", correctionId)
                                .query(String.class)
                                .list()
                                .stream()
                                .map(id -> findCorrectionForEntry(Objects.requireNonNull(id))
                                        .orElseThrow())
                                .toList(),
                        jdbc
                                .sql("SELECT id FROM ledger.journal_entry WHERE correction_id = :id ORDER BY seq")
                                .param("id", correctionId)
                                .query(String.class)
                                .list()
                                .stream()
                                .map(id -> readEntry(Objects.requireNonNull(id)).orElseThrow())
                                .toList()))
                .optional();
    }

    private Optional<CorrectionEntry> readEntry(String entryId) {
        return jdbc.sql("""
                SELECT id, seq, entry_type, source_key, occurred_at, recorded_at, subject_type, subject_id,
                    reverses_entry_id, correction_id FROM ledger.journal_entry WHERE id = :id
                """)
                .param("id", entryId)
                .query((rs, row) -> new CorrectionEntry(
                        rs.getString("id"),
                        rs.getLong("seq"),
                        EntryType.valueOf(rs.getString("entry_type")),
                        rs.getString("source_key"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        SubjectType.valueOf(rs.getString("subject_type").toUpperCase(Locale.ROOT)),
                        rs.getString("subject_id"),
                        rs.getString("reverses_entry_id"),
                        rs.getString("correction_id"),
                        jdbc.sql("""
                                SELECT id, line_no, account_code, side, amount, account_version, balance_after, recorded_at
                                FROM ledger.posting WHERE entry_id = :id ORDER BY line_no
                                """)
                                .param("id", entryId)
                                .query((p, n) -> new CorrectionEntry.Posting(
                                        p.getString("id"),
                                        p.getInt("line_no"),
                                        Account.ofCode(p.getInt("account_code")),
                                        p.getString("side").equals("D") ? Side.DEBIT : Side.CREDIT,
                                        p.getLong("amount"),
                                        p.getLong("account_version"),
                                        p.getLong("balance_after"),
                                        p.getObject("recorded_at", OffsetDateTime.class)
                                                .toInstant()))
                                .list()))
                .optional();
    }

    private record WriteEntry(
            EntryType type,
            String sourceKey,
            Instant occurredAt,
            SubjectType subjectType,
            String subjectId,
            List<PostingLine> lines,
            @Nullable String reversesEntryId,
            @Nullable String correctionId) {
        long totalAmount() {
            return lines.stream()
                    .filter(line -> line.side() == Side.DEBIT)
                    .mapToLong(PostingLine::amount)
                    .reduce(0L, Math::addExact);
        }
    }

    /** Locks the touched accounts in ascending code order (ADR-0004) and returns their current balances. */
    private Map<Account, Balance> lockAccounts(List<PostingLine> lines) {
        TreeSet<Short> codes = new TreeSet<>();
        lines.forEach(line -> codes.add(line.account().code()));
        Map<Account, Balance> balances = new EnumMap<>(Account.class);
        for (short code : codes) {
            Balance balance = jdbc.sql("SELECT balance, version FROM ledger.account WHERE code = :code FOR UPDATE")
                    .param("code", code)
                    .query((rs, row) -> new Balance(rs.getLong("balance"), rs.getLong("version")))
                    .single();
            balances.put(Account.ofCode(code), balance);
        }
        return balances;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the ledger only posts inside the caller's transaction");
        }
    }

    private record Balance(long amount, long version) {}

    private record PlannedPosting(PostingLine line, Balance after) {}
}
