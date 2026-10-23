package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IssueAmountsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void ac_5_15_comparesEachMonetaryObservationWithoutInventingMissingAmounts() {
        var comparisons = IssueAmounts.compare(
                json.readTree("{\"gross\":10000,\"net\":9680}"),
                json.readTree(
                        "[{\"statement_line_id\":\"txn_a\",\"gross\":9999,\"net\":9679},{\"gross\":10000,\"net\":9680}]"));
        assertThat(comparisons).hasSize(4);
        assertThat(comparisons.get(0).difference()).isEqualTo(1);
        assertThat(comparisons.get(0).statementLineId()).isEqualTo("txn_a");
        assertThat(comparisons.get(3).difference()).isZero();
        assertThat(IssueAmounts.compare(json.readTree("{\"net\":9680}"), json.readTree("{\"amount\":9679}"))
                        .getFirst()
                        .difference())
                .isEqualTo(1);
        assertThat(IssueAmounts.compare(json.readTree("{\"closing_balance\":9680}"), json.readTree("{\"cash\":0}"))
                        .getFirst()
                        .difference())
                .isEqualTo(9680);
        assertThat(IssueAmounts.compare(json.readTree("{\"count\":1}"), json.readTree("{\"count\":2}")))
                .isEmpty();
        assertThat(IssueAmounts.compare(json.readTree("null"), json.readTree("{\"amount\":12}")))
                .isEmpty();
    }

    @Test
    void ac_5_15_heldBatchTotalsAndFeeLimitsKeepTheirDistinctComparisons() {
        var totals = IssueAmounts.compare(
                json.readTree("{\"payout_amount\":9680}"),
                json.readTree("{\"reason\":\"TOTAL_MISMATCH\",\"line\":{\"payout_amount\":9680,\"line_net\":9679}}"));
        assertThat(totals).hasSize(2);
        assertThat(totals.stream().map(IssueAmounts.Comparison::difference)).containsExactly(0L, 1L);
        assertThat(IssueAmounts.compare(
                                json.readTree("{\"max_fee\":50}"),
                                json.readTree("{\"line\":{\"gross\":50,\"fee\":60}}"))
                        .getFirst()
                        .difference())
                .isEqualTo(-10);
    }

    @Test
    void ac_5_15_rejectsEvidenceOutsideLongRangeInsteadOfTruncating() {
        assertThatThrownBy(() -> IssueAmounts.compare(
                        json.readTree("{\"amount\":18446744073709551617}"), json.readTree("{\"amount\":1}")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void ac_5_15_rejectsUnsafeDifferenceInsteadOfRounding() {
        assertThatThrownBy(() -> IssueAmounts.compare(
                        json.readTree("{\"amount\":9007199254740991}"), json.readTree("{\"amount\":-1}")))
                .isInstanceOf(ArithmeticException.class);
    }
}
