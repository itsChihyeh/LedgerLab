package dev.ledgerlab.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;

/** Exercises the existing page query through its real readonly transaction. */
public final class PayoutCorrectionPageAssertions {
    private PayoutCorrectionPageAssertions() {}

    public static void assertDebt(DataSource readonly, Clock clock, ObjectMapper mapper, long debt) {
        var overview = new WorkbenchQueries(new QuerySnapshot(readonly, clock), mapper).overview();
        assertThat(overview.merchantAvailable()).isEqualTo(Math.negateExact(debt));
        assertThat(overview.merchantOwes()).isEqualTo(debt);
        assertThat(overview.merchant_debt()).isEqualTo(debt);
    }
}
