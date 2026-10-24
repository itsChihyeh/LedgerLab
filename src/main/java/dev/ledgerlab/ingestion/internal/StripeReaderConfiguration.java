package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
import dev.ledgerlab.reconciliation.IngestionIssues;
import dev.ledgerlab.refunds.ProcessorRefundGateway;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StripeReaderProperties.class)
class StripeReaderConfiguration {
    @Bean(destroyMethod = "close")
    HttpClient stripeReadHttpClient(StripeReaderProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    RestClient stripeRestClient(StripeReaderProperties settings, HttpClient stripeReadHttpClient) {
        var factory = new JdkClientHttpRequestFactory(stripeReadHttpClient);
        factory.setReadTimeout(settings.readTimeout());
        return RestClient.builder()
                .baseUrl(settings.apiBaseUrl().toString())
                .requestFactory(factory)
                .build();
    }

    @Bean
    StripeReader stripeReader(
            RestClient stripeRestClient,
            StripeProperties credentials,
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock) {
        return new RestStripeReader(stripeRestClient, credentials, jdbc, manager, mapper, clock);
    }

    @Bean
    ProcessorRefundGateway processorRefundGateway(
            RestClient stripeRestClient,
            StripeProperties credentials,
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock,
            IngestionIssues issues) {
        return new StripeRefundAdapter(stripeRestClient, credentials, jdbc, manager, mapper, clock, issues);
    }
}
