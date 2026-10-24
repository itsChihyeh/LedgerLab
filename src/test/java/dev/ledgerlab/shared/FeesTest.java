package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeesTest {
    @ParameterizedTest
    @CsvSource({
        "10000,320", "5000,175", "7000,233", "3000,117", "6000,204",
        "2500,103", "4000,146", "1234,66", "50,31", "99999999,2900030",
        "499,44", "500,45", "501,45"
    })
    void ac_5_7_contractFeeRoundsHalfUpInIntegerCents(long gross, long fee) {
        assertThat(Fees.contractFee(gross)).isEqualTo(fee);
    }

    @Test
    void ac_5_7_contractFeeRejectsMultiplicationOverflow() {
        assertThatThrownBy(() -> Fees.contractFee(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Fees.contractFee(Long.MIN_VALUE)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void ac_5_7_contractFeeRejectsRoundingAdditionOverflow() {
        assertThatThrownBy(() -> Fees.contractFee(Long.MAX_VALUE / 29)).isInstanceOf(ArithmeticException.class);
        assertThat(Fees.contractFee((Long.MAX_VALUE - 500) / 29)).isEqualTo(9_223_372_036_854_805L);
    }
}
