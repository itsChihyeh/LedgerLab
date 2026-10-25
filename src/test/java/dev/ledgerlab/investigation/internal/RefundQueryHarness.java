package dev.ledgerlab.investigation.internal;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Assemble the real readonly query controller for refund API integration tests. */
public final class RefundQueryHarness {
    private RefundQueryHarness() {}

    public static MockMvc mvc(DataSource readonly, Clock clock) {
        var snapshot = new QuerySnapshot(readonly, clock);
        var json = new tools.jackson.databind.json.JsonMapper();
        var workbench = new WorkbenchQueries(snapshot, json);
        var refunds = new RefundQueries(snapshot, workbench, json);
        return MockMvcBuilders.standaloneSetup(
                        new RefundQueryController(refunds),
                        new PaymentQueryController(new PaymentQueries(snapshot, workbench, refunds)),
                        new WorkbenchQueryController(workbench))
                .build();
    }
}
