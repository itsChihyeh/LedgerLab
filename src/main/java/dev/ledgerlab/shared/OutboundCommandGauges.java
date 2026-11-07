package dev.ledgerlab.shared;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code ledgerlab.outbound.unfinished} and {@code ledgerlab.outbound.exhausted} for the commands of one module.
 * The command table belongs to {@code outbound}, which depends on nothing, so the module that owns the redacted
 * summary view ({@code refunds}, {@code payouts}) registers its own kinds and hands this class the view name, the
 * SQL expression that gives the kind, the kinds and the attempt limit. Names, labels and statuses are closed sets
 * written in code; none of them comes from data.
 */
public final class OutboundCommandGauges {
    private static final List<String> UNFINISHED = List.of("PENDING", "UNKNOWN", "IN_FLIGHT");

    private final JdbcClient jdbc;
    private final String view;
    private final String kindExpression;
    private final List<String> kinds;
    private final int maxAttempts;
    private final ScrapeSnapshot<Map<String, double[]>> snapshot;

    /**
     * @param view a {@code core.v_*_command_summary} view; a constant from the calling module, never user input
     * @param kindExpression a column name or a SQL literal that yields the command kind in that view
     */
    public OutboundCommandGauges(
            DataSource metricsDataSource, String view, String kindExpression, List<String> kinds, int maxAttempts) {
        this.jdbc = MetricsDatabase.client(metricsDataSource);
        this.view = view;
        this.kindExpression = kindExpression;
        this.kinds = List.copyOf(kinds);
        this.maxAttempts = maxAttempts;
        this.snapshot = new ScrapeSnapshot<>("outbound:" + view, this::load);
    }

    public void bindTo(MeterRegistry registry) {
        for (String kind : kinds) {
            for (String status : UNFINISHED) {
                String key = kind + "/" + status;
                Gauge.builder(
                                "ledgerlab.outbound.unfinished",
                                () -> snapshot.read(key, counts -> value(counts, key, 0)))
                        .tag("kind", kind)
                        .tag("status", status)
                        .description("Outbound commands not finished yet")
                        .register(registry);
            }
            String key = kind + "/exhausted";
            Gauge.builder("ledgerlab.outbound.exhausted", () -> snapshot.read(key, counts -> value(counts, kind, 1)))
                    .tag("kind", kind)
                    .description("Unfinished outbound commands that reached the attempt limit")
                    .register(registry);
        }
    }

    // Counts are keyed "kind/status" (unfinished, slot 0) and "kind" (exhausted, slot 1).
    private static double value(Map<String, double[]> counts, String key, int slot) {
        double[] found = counts.get(key);
        return found == null ? 0 : found[slot];
    }

    private Map<String, double[]> load() {
        Map<String, double[]> counts = new HashMap<>();
        // `view` and `kindExpression` are constants chosen by the calling module.
        jdbc.sql("SELECT " + kindExpression + " AS kind, status, count(*) AS n, "
                        + "count(*) FILTER (WHERE attempts >= :max) AS exhausted FROM " + view
                        + " WHERE status <> 'DONE' GROUP BY 1, 2")
                .param("max", maxAttempts)
                .query((rs, row) -> {
                    String kind = rs.getString("kind");
                    counts.computeIfAbsent(kind + "/" + rs.getString("status"), k -> new double[2])[0] =
                            rs.getLong("n");
                    counts.computeIfAbsent(kind, k -> new double[2])[1] += rs.getLong("exhausted");
                    return kind;
                })
                .list();
        return counts;
    }
}
