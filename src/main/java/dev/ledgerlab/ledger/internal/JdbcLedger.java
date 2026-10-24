package dev.ledgerlab.ledger.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.DuplicateSourceKeyException;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.NegativeBalanceException;
import dev.ledgerlab.ledger.PostedEntry;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.sql.DataSource;
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
    public PostedEntry post(EntryRequest request) {
        requireTransaction();
        if (!TransactionSynchronizationManager.hasResource(fundsLockKey)) {
            throw new IllegalStateException("lockFunds() must be the first lock of a money-moving transaction");
        }
        OffsetDateTime recordedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);

        Map<Account, Balance> balances = lockAccounts(request.lines());
        List<PlannedPosting> planned = new ArrayList<>(request.lines().size());
        for (PostingLine line : request.lines()) {
            Balance before = Objects.requireNonNull(balances.get(line.account()), "account was locked above");
            Balance after = new Balance(Math.addExact(before.amount(), line.balanceDelta()), before.version() + 1);
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
                                 occurred_at, recorded_at, subject_type, subject_id)
                            VALUES (:id, :type, :sourceKey, :count, :total, :occurredAt, :recordedAt,
                                    :subjectType, :subjectId)
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
