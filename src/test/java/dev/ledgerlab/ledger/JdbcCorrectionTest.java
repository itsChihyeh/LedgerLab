package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static dev.ledgerlab.testsupport.LedgerFixture.only;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ledger.internal.JdbcLedger;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.TestDatabase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCorrectionTest {
    private LedgerFixture f;

    @BeforeEach
    void setup() {
        f = new LedgerFixture(TestDatabase.freshMigrated());
    }

    @AfterEach
    void cleanup() {
        f.db().drop();
    }

    static CorrectionRequest request(List<String> reversed, List<String> applied, List<PostingLine> lines) {
        return new CorrectionRequest(
                CorrectionType.RESTATE_CAPTURE_AMOUNT,
                CorrectionReason.OTHER,
                "Verified synthetic evidence",
                "[{\"type\":\"inbox_event\",\"id\":\"evt_test\"}]",
                "{\"oldAmount\":10000,\"newAmount\":12000}",
                SubjectType.PAYMENT,
                "pay_corrected",
                "test-operator",
                reversed,
                applied,
                lines,
                T0.minusSeconds(60));
    }

    static List<PostingLine> capture(long amount) {
        return List.of(
                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, amount),
                PostingLine.credit(Account.MERCHANT_PENDING, amount));
    }

    private RecordedCorrection correct(CorrectionRequest request) {
        return f.inMoneyTransaction(l -> l.recordCorrection(request));
    }

    private CorrectionDetails details(String id) {
        return f.inMoneyTransaction(l -> l.findCorrection(id).orElseThrow());
    }

    @Test
    void ac_6_3_voidMirrorsOriginalAndRetainsItsSubject() {
        var original = f.s1();
        var result = correct(request(List.of(original.entryId()), List.of(), List.of()));
        var details = details(result.correctionId());
        assertThat(result.correctionId()).startsWith("cor_");
        assertThat(details.targets()).singleElement().satisfies(target -> {
            assertThat(target.role()).isEqualTo(CorrectionTarget.Role.REVERSED);
            assertThat(target.entryId()).isEqualTo(original.entryId());
            assertThat(target.recordedAt()).isEqualTo(T0);
        });
        assertThat(details.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(EntryType.REVERSAL);
            assertThat(entry.sourceKey()).isEqualTo("reversal:" + original.entryId());
            assertThat(entry.subjectId()).isEqualTo("pay_P1");
            assertThat(entry.reversesEntryId()).isEqualTo(original.entryId());
            assertThat(entry.postings())
                    .extracting(CorrectionEntry.Posting::side)
                    .containsExactly(Side.CREDIT, Side.DEBIT);
            assertThat(entry.postings())
                    .extracting(CorrectionEntry.Posting::amount)
                    .containsExactly(10000L, 10000L);
            assertThat(entry.recordedAt()).isEqualTo(T0);
            assertThat(entry.occurredAt()).isEqualTo(T0.minusSeconds(60));
        });
        assertThat(f.balances()).isEqualTo(only());
        assertThat(f.audit().check()).isEmpty();
        assertThat(f.count("ledger.journal_entry")).isEqualTo(2);
    }

    @Test
    void ac_6_5_restateQueriesMetadataTargetsAndGeneratedPostings() {
        var original = f.s1();
        var result = correct(request(List.of(original.entryId()), List.of(), capture(12000)));
        var detail = details(result.correctionId());
        assertThat(detail.reasonCode()).isEqualTo(CorrectionReason.OTHER);
        assertThat(detail.reasonNote()).isEqualTo("Verified synthetic evidence");
        assertThat(detail.evidenceRefs()).contains("evt_test");
        assertThat(detail.params()).contains("12000");
        assertThat(detail.requestedBy()).isEqualTo("test-operator");
        assertThat(detail.subjectType()).isEqualTo(SubjectType.PAYMENT);
        assertThat(detail.recordedAt()).isEqualTo(T0);
        assertThat(detail.entries())
                .extracting(CorrectionEntry::type)
                .containsExactly(EntryType.REVERSAL, EntryType.CORRECTION);
        assertThat(detail.entries().get(1).sourceKey()).isEqualTo("correction:" + result.correctionId());
        assertThat(detail.entries().get(1).postings()).hasSize(2);
        assertThat(f.balances()).isEqualTo(only(Account.PROCESSOR_RECEIVABLE, 12000, Account.MERCHANT_PENDING, 12000));
        assertThat(f.audit().check()).isEmpty();
        assertThat(f.inMoneyTransaction(l -> l.findCorrectionForEntry(original.entryId()))
                        .orElseThrow()
                        .correctionId())
                .isEqualTo(result.correctionId());
        var generatedTarget = f.inMoneyTransaction(
                l -> l.findCorrectionForEntry(result.entries().getFirst().entryId()));
        var missing = f.inMoneyTransaction(l -> l.findCorrection("cor_missing"));
        assertThat(generatedTarget).isEmpty();
        assertThat(missing).isEmpty();
    }

    @Test
    void ac_6_6_secondTargetReportsExistingCorrectionWithoutWriting() {
        var original = f.s1();
        var result = correct(request(List.of(original.entryId()), List.of(), List.of()));
        assertThatThrownBy(() -> correct(request(List.of(original.entryId()), List.of(), capture(12000))))
                .isInstanceOfSatisfying(
                        AlreadyCorrectedException.class,
                        e -> assertThat(e.correctionId()).isEqualTo(result.correctionId()));
        assertThat(f.count("ledger.correction")).isEqualTo(1);
        assertThat(f.count("ledger.correction_target")).isEqualTo(1);
        assertThat(f.count("ledger.journal_entry")).isEqualTo(2);
        assertThat(f.balances()).isEqualTo(only());
    }

    @Test
    void ac_6_6_cannotTargetReversalOrMissingEntry() {
        var original = f.s1();
        var result = correct(request(List.of(original.entryId()), List.of(), List.of()));
        assertThatThrownBy(() ->
                        correct(request(List.of(result.entries().getFirst().entryId()), List.of(), List.of())))
                .isInstanceOf(CannotReverseReversalException.class);
        assertThatThrownBy(() -> correct(request(List.of("je_missing"), List.of(), List.of())))
                .isInstanceOf(EntryNotFoundException.class);
        assertThat(f.count("ledger.correction")).isEqualTo(1);
    }

    @Test
    void ac_6_6_replacementCanBeCorrectedAgain() {
        var original = f.s1();
        var first = correct(request(List.of(original.entryId()), List.of(), capture(12000)));
        correct(request(List.of(first.entries().get(1).entryId()), List.of(), capture(9000)));
        assertThat(f.balances()).isEqualTo(only(Account.PROCESSOR_RECEIVABLE, 9000, Account.MERCHANT_PENDING, 9000));
        assertThat(f.audit().check()).isEmpty();
    }

    @Test
    void ac_6_8_allTargetsValidatedBeforeAnyWrite() {
        var original = f.s1();
        f.inMoneyTransaction(l -> {
            assertThatThrownBy(() ->
                            l.recordCorrection(request(List.of(original.entryId(), "missing"), List.of(), List.of())))
                    .isInstanceOf(EntryNotFoundException.class);
            return null;
        });
        assertThat(f.count("ledger.correction")).isZero();
        assertThat(f.count("ledger.correction_target")).isZero();
        assertThat(f.count("ledger.journal_entry")).isEqualTo(1);
    }

    @Test
    void ac_6_8_multipleReversalsMirrorDuplicateLines() {
        var a = f.post(new EntryRequest(
                EntryType.PAYMENT_CAPTURED,
                "a",
                T0,
                SubjectType.PAYMENT,
                "pay_a",
                List.of(
                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 50),
                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 50),
                        PostingLine.credit(Account.MERCHANT_PENDING, 100))));
        var b = f.post(
                new EntryRequest(EntryType.PAYMENT_CAPTURED, "b", T0, SubjectType.PAYMENT, "pay_b", capture(50)));
        var result = correct(request(List.of(a.entryId(), b.entryId()), List.of(), capture(200)));
        assertThat(result.entries()).hasSize(3);
        assertThat(details(result.correctionId()).entries().getFirst().postings())
                .hasSize(3);
        assertThat(f.balances()).isEqualTo(only(Account.PROCESSOR_RECEIVABLE, 200, Account.MERCHANT_PENDING, 200));
        assertThat(f.audit().check()).isEmpty();
    }

    @Test
    void ac_6_10_appliedTargetsStayUnreversedAndCannotBeTargetedAgain() {
        var a = f.post(new EntryRequest(
                EntryType.UNAPPLIED_CASH_RECEIVED,
                "bank:a",
                T0,
                SubjectType.BANK_TXN,
                "a",
                List.of(PostingLine.debit(Account.BANK_CASH, 50), PostingLine.credit(Account.UNAPPLIED_CASH, 50))));
        var b = f.post(new EntryRequest(
                EntryType.UNAPPLIED_CASH_RECEIVED,
                "bank:b",
                T0,
                SubjectType.BANK_TXN,
                "b",
                List.of(PostingLine.debit(Account.BANK_CASH, 100), PostingLine.credit(Account.UNAPPLIED_CASH, 100))));
        // Ledger layer accepts caller-checked business postings; type-specific matching belongs to T02-T05.
        var result = correct(request(
                List.of(),
                List.of(a.entryId(), b.entryId()),
                List.of(
                        PostingLine.debit(Account.UNAPPLIED_CASH, 150),
                        PostingLine.credit(Account.MERCHANT_AVAILABLE, 150))));
        assertThat(details(result.correctionId()).targets())
                .hasSize(2)
                .allMatch(t -> t.role() == CorrectionTarget.Role.APPLIED);
        assertThat(details(result.correctionId()).entries())
                .singleElement()
                .extracting(CorrectionEntry::type)
                .isEqualTo(EntryType.CORRECTION);
        assertThatThrownBy(() -> correct(request(List.of(a.entryId()), List.of(), List.of())))
                .isInstanceOf(AlreadyCorrectedException.class);
        assertThat(f.balances()).isEqualTo(only(Account.BANK_CASH, 150, Account.MERCHANT_AVAILABLE, 150));
        assertThat(f.audit().check()).isEmpty();
    }

    @Test
    void ac_6_10_wrongAppliedTypeRejectedBeforeWriting() {
        var original = f.s1();
        assertThatThrownBy(() -> correct(request(List.of(), List.of(original.entryId()), capture(50))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(f.count("ledger.correction")).isZero();
    }

    @Test
    void ac_6_8_negativeBalanceRollsBackEntireCorrection() {
        var original = f.s1();
        f.post(new EntryRequest(
                EntryType.BATCH_SETTLED,
                "spend",
                T0,
                SubjectType.BATCH,
                "batch",
                List.of(
                        PostingLine.debit(Account.MERCHANT_PENDING, 10000),
                        PostingLine.credit(Account.MERCHANT_AVAILABLE, 10000))));
        var before = f.balances();
        assertThatThrownBy(() -> correct(request(List.of(original.entryId()), List.of(), List.of())))
                .isInstanceOf(NegativeBalanceException.class);
        assertThat(f.count("ledger.correction")).isZero();
        assertThat(f.count("ledger.correction_target")).isZero();
        assertThat(f.count("ledger.journal_entry")).isEqualTo(2);
        assertThat(f.balances()).isEqualTo(before);
        assertThat(f.audit().check()).isEmpty();
    }

    @Test
    void ac_6_8_requiresExistingTransactionAndFundsLock() {
        var req = request(List.of(), List.of(), capture(50));
        assertThatThrownBy(() -> f.ledger().recordCorrection(req)).isInstanceOf(IllegalStateException.class);
        var source = f.db().app();
        var clock = Clock.fixed(T0, ZoneOffset.UTC);
        var ledger = new JdbcLedger(source, clock, IdGenerator.seeded(clock, 42));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> tx.execute(s -> ledger.recordCorrection(req)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.count("ledger.correction")).isZero();
    }

    @Test
    void ac_6_8_failureAfterCompleteReversalBeforeCorrectionRollsBackEverything() {
        var original = f.s1();
        var before = f.balances();
        var headerCount = new AtomicInteger();
        DataSource realSource = f.db().app();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object value = invoke(realSource, method, args);
                    if (!(value instanceof Connection real)) return value;
                    return Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p, op, values) -> {
                                if (op.getName().equals("prepareStatement")
                                        && ((String) values[0]).contains("INSERT INTO ledger.journal_entry")
                                        && headerCount.incrementAndGet() == 2) {
                                    try (var statement = real.createStatement();
                                            var rows = statement.executeQuery("""
                                            SELECT (SELECT count(*) FROM ledger.correction),
                                                   (SELECT count(*) FROM ledger.correction_target),
                                                   (SELECT count(*) FROM ledger.journal_entry WHERE entry_type='REVERSAL'),
                                                   (SELECT balance FROM ledger.account WHERE code=2101)
                                            """)) {
                                        assertThat(rows.next()).isTrue();
                                        assertThat(rows.getLong(1)).isEqualTo(1);
                                        assertThat(rows.getLong(2)).isEqualTo(1);
                                        assertThat(rows.getLong(3)).isEqualTo(1);
                                        assertThat(rows.getLong(4)).isZero();
                                    }
                                    throw new IllegalStateException("Injected before CORRECTION");
                                }
                                return invoke(real, op, values);
                            });
                });
        var clock = Clock.fixed(T0, ZoneOffset.UTC);
        var ledger = new JdbcLedger(wrapped, clock, IdGenerator.seeded(clock, 97));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(wrapped));
        assertThatThrownBy(() -> tx.execute(s -> {
                    ledger.lockFunds();
                    return ledger.recordCorrection(request(List.of(original.entryId()), List.of(), capture(12000)));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Injected before CORRECTION");
        assertThat(headerCount).hasValue(2);
        assertThat(f.count("ledger.correction")).isZero();
        assertThat(f.count("ledger.correction_target")).isZero();
        assertThat(f.count("ledger.journal_entry")).isEqualTo(1);
        assertThat(f.count("ledger.posting")).isEqualTo(2);
        assertThat(f.balances()).isEqualTo(before);
        assertThat(f.audit().check()).isEmpty();
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
