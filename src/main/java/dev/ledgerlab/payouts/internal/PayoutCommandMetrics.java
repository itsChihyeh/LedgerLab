package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.shared.MetricsDatabase;
import dev.ledgerlab.shared.OutboundCommandGauges;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Unfinished and exhausted payout commands, read through {@code core.v_payout_command_summary}. */
@Component
final class PayoutCommandMetrics implements MeterBinder {
    private final OutboundCommandGauges gauges;

    PayoutCommandMetrics(
            @Qualifier(MetricsDatabase.DATA_SOURCE) DataSource metricsDataSource, OutboundProperties properties) {
        this.gauges = new OutboundCommandGauges(
                metricsDataSource,
                "core.v_payout_command_summary",
                "kind",
                List.of("SUBMIT_PAYOUT", "QUERY_PAYOUT"),
                properties.maxAttempts());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauges.bindTo(registry);
    }
}
