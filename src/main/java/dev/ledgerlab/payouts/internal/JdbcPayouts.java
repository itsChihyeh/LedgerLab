package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.ledger.Account;
import dev.ledgerlab.ledger.EntryRequest;
import dev.ledgerlab.ledger.EntryType;
import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.ledger.PostingLine;
import dev.ledgerlab.ledger.SubjectType;
import dev.ledgerlab.payouts.PayoutException;
import dev.ledgerlab.payouts.PayoutResult;
import dev.ledgerlab.payouts.Payouts;
import dev.ledgerlab.shared.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
final class JdbcPayouts implements Payouts {
    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final Clock clock;
    private final IdGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    JdbcPayouts(
            JdbcClient jdbc,
            Ledger ledger,
            Clock clock,
            IdGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
        this.ids = ids;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public PayoutResult reserve(long amount, String merchantPayoutRef, String fingerprint) {
        if (amount < 1 || amount > 99999999) throw new PayoutException("INVALID_AMOUNT");
        if (!merchantPayoutRef.matches("[!-~]{1,64}")) throw new PayoutException("INVALID_MERCHANT_PAYOUT_REF");
        return Objects.requireNonNull(transaction.execute(tx -> {
            ledger.lockFunds();
            var existing = jdbc.sql(
                            "SELECT amount, initial_result::text FROM core.payout WHERE merchant_payout_ref=:ref FOR UPDATE")
                    .param("ref", merchantPayoutRef)
                    .query((rs, row) -> {
                        if (rs.getLong(1) != amount) throw new PayoutException("PAYOUT_REF_CONFLICT");
                        var body = mapper.readTree(rs.getString(2)).path("body");
                        return new PayoutResult(
                                body.path("payoutId").asString(),
                                body.path("status").asString(),
                                body.path("amount").asLong(),
                                body.path("merchantPayoutRef").asString(),
                                Instant.parse(body.path("reservedAt").asString()),
                                body.path("bankRequestId").asString());
                    })
                    .optional();
            if (existing.isPresent()) return existing.orElseThrow();
            var balances = ledger.lockBalances(List.of(
                    Account.PROCESSOR_RECEIVABLE,
                    Account.BANK_CASH,
                    Account.MERCHANT_AVAILABLE,
                    Account.PAYOUT_IN_TRANSIT,
                    Account.UNAPPLIED_CASH));
            if (amount > Objects.requireNonNull(balances.get(Account.MERCHANT_AVAILABLE)))
                throw new PayoutException("INSUFFICIENT_FUNDS", "MERCHANT_BALANCE");
            long freeCash = Math.subtractExact(
                    Math.subtractExact(
                            Math.subtractExact(
                                    Objects.requireNonNull(balances.get(Account.BANK_CASH)),
                                    Objects.requireNonNull(balances.get(Account.PAYOUT_IN_TRANSIT))),
                            Objects.requireNonNull(balances.get(Account.UNAPPLIED_CASH))),
                    Math.max(0, Math.negateExact(Objects.requireNonNull(balances.get(Account.PROCESSOR_RECEIVABLE)))));
            if (amount > freeCash) throw new PayoutException("INSUFFICIENT_FUNDS", "FREE_CASH");
            Instant now = clock.instant();
            String id = ids.next("mpo");
            var result = new PayoutResult(id, "RESERVED", amount, merchantPayoutRef, now, id);
            var body = Map.of(
                    "payoutId",
                    id,
                    "status",
                    "RESERVED",
                    "amount",
                    amount,
                    "merchantPayoutRef",
                    merchantPayoutRef,
                    "reservedAt",
                    now.toString(),
                    "bankRequestId",
                    id);
            jdbc.sql("""
                    INSERT INTO core.payout(id, merchant_payout_ref, amount, currency, status,
                        initial_result, request_fingerprint, reserved_at, updated_at, recorded_at)
                    VALUES (:id, :ref, :amount, 'CAD', 'RESERVED', CAST(:result AS jsonb), :fingerprint, :now, :now, :now)
                    """)
                    .param("id", id)
                    .param("ref", merchantPayoutRef)
                    .param("amount", amount)
                    .param("result", mapper.writeValueAsString(Map.of("status", 201, "body", body)))
                    .param("fingerprint", fingerprint)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
            jdbc.sql("""
                    INSERT INTO core.payout_status_history(payout_id, from_status, to_status, evidence_type,
                        evidence_id, occurred_at, recorded_at)
                    VALUES (:id, NULL, 'RESERVED', 'api', :id, :now, :now)
                    """)
                    .param("id", id)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
            ledger.post(new EntryRequest(
                    EntryType.PAYOUT_RESERVED,
                    "payout:" + id + ":reserved",
                    now,
                    SubjectType.PAYOUT,
                    id,
                    List.of(
                            PostingLine.debit(Account.MERCHANT_AVAILABLE, amount),
                            PostingLine.credit(Account.PAYOUT_IN_TRANSIT, amount))));
            jdbc.sql("""
                    INSERT INTO io.outbound_command(id, kind, subject_id, request_id, status, next_attempt_at, created_at, recorded_at)
                    VALUES (:id, 'SUBMIT_PAYOUT', :payout, :payout, 'PENDING', :now, :now, :now)
                    """)
                    .param("id", ids.next("cmd"))
                    .param("payout", id)
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
            return result;
        }));
    }
}
