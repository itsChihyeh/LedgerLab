package dev.ledgerlab.ledger;

import static dev.ledgerlab.testsupport.LedgerFixture.T0;
import static dev.ledgerlab.testsupport.LedgerFixture.only;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.ledgerlab.testsupport.LedgerFixture;
import dev.ledgerlab.testsupport.Sql;
import dev.ledgerlab.testsupport.TestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC-1.9: accounts that may not go negative, checked by the posting interface and backed by a CHECK. */
class NonNegativeAccountTest {

    private static final String CHECK_VIOLATION = "23514";

    private LedgerFixture fixture;
    private Map<Account, Long> before;

    @BeforeEach
    void givenCashAndAvailable9680() {
        fixture = new LedgerFixture(TestDatabase.freshMigrated());
        // Ledger-level fixture, independent of the P2 settlement flow: bank cash 9680, merchant available 9680.
        fixture.post(new EntryRequest(
                EntryType.PAYOUT_RETURNED,
                "bank_txn:btx_fixture",
                T0,
                SubjectType.BANK_TXN,
                "btx_fixture",
                List.of(
                        PostingLine.debit(Account.BANK_CASH, 9680),
                        PostingLine.credit(Account.MERCHANT_AVAILABLE, 9680))));
        before = only(Account.BANK_CASH, 9680, Account.MERCHANT_AVAILABLE, 9680);
        assertThat(fixture.balances()).isEqualTo(before);
    }

    @AfterEach
    void tearDown() {
        fixture.db().drop();
    }

    @Test
    void ac_1_9_postingThatTakesBankCashBelowZeroRollsBackTheWholeTransaction() {
        EntryRequest overdraw = new EntryRequest(
                EntryType.PAYOUT_PAID,
                "bank_txn:btx_overdraw",
                T0,
                SubjectType.BANK_TXN,
                "btx_overdraw",
                List.of(
                        PostingLine.debit(Account.MERCHANT_AVAILABLE, 9681),
                        PostingLine.credit(Account.BANK_CASH, 9681)));

        NegativeBalanceException thrown = catchThrowableOfType(
                NegativeBalanceException.class,
                () -> fixture.inMoneyTransaction(ledger -> {
                    // An earlier, valid entry in the same transaction must roll back with it.
                    ledger.post(new EntryRequest(
                            EntryType.PAYMENT_CAPTURED,
                            "payment:ch_same_txn:captured",
                            T0,
                            SubjectType.PAYMENT,
                            "pay_same_txn",
                            List.of(
                                    PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 500),
                                    PostingLine.credit(Account.MERCHANT_PENDING, 500))));
                    return ledger.post(overdraw);
                }));

        assertThat(thrown.account()).isEqualTo(Account.BANK_CASH);
        assertThat(thrown.resultingBalance()).isEqualTo(-1);
        assertThat(fixture.balances()).isEqualTo(before);
        assertThat(fixture.count("ledger.journal_entry")).isEqualTo(1);
        assertThat(fixture.audit().check()).isEmpty();
    }

    @Test
    void ac_1_9_directUpdateToMinusOneIsRejectedByTheCheckConstraint() {
        Sql.assertRejected(
                CHECK_VIOLATION, fixture.db().app(), "UPDATE ledger.account SET balance = -1 WHERE code = 1103");

        assertThat(fixture.balances()).isEqualTo(before);
    }

    @Test
    void everyNonNegativeAccountHasTheDatabaseBackstop() {
        // 1101 and 2102 may go negative (money-model section 2); every other account may not.
        for (Account account : Account.values()) {
            if (!account.allowNegative()) {
                Sql.assertRejected(
                        CHECK_VIOLATION,
                        fixture.db().app(),
                        "UPDATE ledger.account SET balance = -1 WHERE code = " + account.code());
            }
        }
        assertThat(fixture.balances()).isEqualTo(before);
    }

    @Test
    void merchantAvailableMayGoNegative() {
        fixture.post(new EntryRequest(
                EntryType.REFUND_ACCEPTED,
                "refund:rfd_1:accepted",
                T0,
                SubjectType.REFUND,
                "rfd_1",
                List.of(
                        PostingLine.debit(Account.MERCHANT_AVAILABLE, 10000),
                        PostingLine.credit(Account.REFUND_PAYABLE, 10000))));

        assertThat(fixture.balances())
                .isEqualTo(
                        only(Account.BANK_CASH, 9680, Account.MERCHANT_AVAILABLE, -320, Account.REFUND_PAYABLE, 10000));
        assertThat(fixture.audit().check()).isEmpty();
    }
}
