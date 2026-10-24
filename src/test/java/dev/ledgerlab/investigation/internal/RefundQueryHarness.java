package dev.ledgerlab.investigation.internal;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Assemble the real readonly query controller for refund API integration tests. */
public final class RefundQueryHarness {
    private RefundQueryHarness() {}

    public static MockMvc mvc(DataSource readonly, Clock clock) {
        return MockMvcBuilders.standaloneSetup(
                        new RefundQueryController(new RefundQueries(new QuerySnapshot(readonly, clock))))
                .build();
    }
}
