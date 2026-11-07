package dev.ledgerlab.shared;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The one small read-only connection pool the business metrics share (ADR-0014: metrics read through the
 * {@code readonly} role and its views, never through {@code app}). Modules inject it by name; the bean is not a
 * default candidate, so Boot's {@code app} DataSource and transaction manager stay the defaults.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsDatabase {
    public static final String DATA_SOURCE = "metricsReadonlyDataSource";
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    @Bean(name = DATA_SOURCE, defaultCandidate = false)
    HikariDataSource metricsReadonlyDataSource(
            @Value("${ledgerlab.readonly-datasource.url}") String url,
            @Value("${ledgerlab.readonly-datasource.username}") String username,
            @Value("${ledgerlab.readonly-datasource.password}") String password) {
        var pool = new HikariDataSource();
        pool.setPoolName("metrics-readonly");
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(2);
        pool.setMinimumIdle(0);
        pool.setReadOnly(true);
        // A scrape must answer quickly when the database is away, and the application must start without it.
        pool.setConnectionTimeout(2000);
        pool.setInitializationFailTimeout(-1);
        return pool;
    }

    /** A client over the metrics pool whose statements give up after a couple of seconds. */
    public static JdbcClient client(DataSource metricsDataSource) {
        var template = new JdbcTemplate(metricsDataSource);
        template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        return JdbcClient.create(template);
    }
}
