package dev.ledgerlab.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fail-fast validation in the posting interface; the database enforces the same rules again. */
class EntryRequestTest {

    private static final Instant T = Instant.parse("2026-10-21T14:00:00Z");

    @Test
    void balancedEntryReportsItsTotal() {
        EntryRequest request = request(
                EntryType.PAYMENT_CAPTURED,
                List.of(
                        PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 10000),
                        PostingLine.credit(Account.MERCHANT_PENDING, 10000)));

        assertThat(request.totalAmount()).isEqualTo(10000);
    }

    @Test
    void unbalancedEntryIsRejected() {
        assertThatThrownBy(() -> request(
                        EntryType.PAYMENT_CAPTURED,
                        List.of(
                                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 10000),
                                PostingLine.credit(Account.MERCHANT_PENDING, 9999))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    void singlePostingIsRejected() {
        assertThatThrownBy(() -> request(
                        EntryType.PAYMENT_CAPTURED, List.of(PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> PostingLine.debit(Account.BANK_CASH, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostingLine.credit(Account.BANK_CASH, -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void correctionsUseTheirOwnPath() {
        List<PostingLine> lines =
                List.of(PostingLine.debit(Account.BANK_CASH, 1), PostingLine.credit(Account.UNAPPLIED_CASH, 1));

        assertThatThrownBy(() -> request(EntryType.REVERSAL, lines)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(EntryType.CORRECTION, lines)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankSourceKeyIsRejected() {
        assertThatThrownBy(() -> new EntryRequest(
                        EntryType.PAYMENT_CAPTURED,
                        " ",
                        T,
                        SubjectType.PAYMENT,
                        "pay_1",
                        List.of(
                                PostingLine.debit(Account.PROCESSOR_RECEIVABLE, 1),
                                PostingLine.credit(Account.MERCHANT_PENDING, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void balanceDeltaFollowsTheNormalSide() {
        assertThat(PostingLine.debit(Account.BANK_CASH, 5).balanceDelta()).isEqualTo(5);
        assertThat(PostingLine.credit(Account.BANK_CASH, 5).balanceDelta()).isEqualTo(-5);
        assertThat(PostingLine.credit(Account.MERCHANT_AVAILABLE, 5).balanceDelta())
                .isEqualTo(5);
        assertThat(PostingLine.debit(Account.MERCHANT_AVAILABLE, 5).balanceDelta())
                .isEqualTo(-5);
    }

    @Test
    void accountCodesRoundTrip() {
        for (Account account : Account.values()) {
            assertThat(Account.ofCode(account.code())).isEqualTo(account);
        }
        assertThatThrownBy(() -> Account.ofCode(9999)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Side.ofDbValue("D")).isEqualTo(Side.DEBIT);
        assertThat(Side.ofDbValue("C")).isEqualTo(Side.CREDIT);
        assertThatThrownBy(() -> Side.ofDbValue("X")).isInstanceOf(IllegalArgumentException.class);
    }

    private static EntryRequest request(EntryType type, List<PostingLine> lines) {
        return new EntryRequest(type, "key:1", T, SubjectType.PAYMENT, "pay_1", lines);
    }
}
