package dev.ledgerlab.investigation.internal;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
class ReadonlyConfiguration {
    // Keep Boot's app DataSource and transaction manager as the default candidates.
    @Bean(defaultCandidate = false)
    DataSource investigationDataSource(
            @Value("${ledgerlab.readonly-datasource.url}") String url,
            @Value("${ledgerlab.readonly-datasource.username}") String username,
            @Value("${ledgerlab.readonly-datasource.password}") String password) {
        return new DriverManagerDataSource(url, username, password);
    }
}
