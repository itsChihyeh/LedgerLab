package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.shared.MetricsDatabase;
import dev.ledgerlab.shared.OutboundCommandGauges;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Unfinished and exhausted refund commands, read through {@code core.v_refund_command_summary}. */
@Component
final class RefundCommandMetrics implements MeterBinder {
    private final OutboundCommandGauges gauges;

    RefundCommandMetrics(
            @Qualifier(MetricsDatabase.DATA_SOURCE) DataSource metricsDataSource, OutboundProperties properties) {
        // The view is already limited to SUBMIT_REFUND and has no kind column.
        this.gauges = new OutboundCommandGauges(
                metricsDataSource,
                "core.v_refund_command_summary",
                "'SUBMIT_REFUND'",
                List.of("SUBMIT_REFUND"),
                properties.maxAttempts());
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauges.bindTo(registry);
    }
}
