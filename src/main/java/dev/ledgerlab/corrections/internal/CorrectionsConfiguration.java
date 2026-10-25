package dev.ledgerlab.corrections.internal;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
class CorrectionsConfiguration {
    @Bean(defaultCandidate = false)
    DataSource correctionsDataSource(
            @Value("${ledgerlab.readonly-datasource.url}") String url,
            @Value("${ledgerlab.readonly-datasource.username}") String username,
            @Value("${ledgerlab.readonly-datasource.password}") String password) {
        return new DriverManagerDataSource(url, username, password);
    }
}
