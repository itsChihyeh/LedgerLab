package dev.ledgerlab.ingestion.internal;

import dev.ledgerlab.ingestion.StripeReader;
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
    StripeReader stripeReader(
            StripeReaderProperties settings,
            StripeProperties credentials,
            HttpClient stripeReadHttpClient,
            JdbcClient jdbc,
            PlatformTransactionManager manager,
            ObjectMapper mapper,
            Clock clock) {
        var factory = new JdkClientHttpRequestFactory(stripeReadHttpClient);
        factory.setReadTimeout(settings.readTimeout());
        RestClient client = RestClient.builder()
                .baseUrl(settings.apiBaseUrl().toString())
                .requestFactory(factory)
                .build();
        return new RestStripeReader(client, credentials, jdbc, manager, mapper, clock);
    }
}
