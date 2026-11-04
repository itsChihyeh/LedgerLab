package dev.ledgerlab.payouts.internal;

import dev.ledgerlab.payouts.BankPayoutGateway;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ledgerlab.bank.payments.enabled", havingValue = "true")
@EnableConfigurationProperties(BankPayoutProperties.class)
class BankPayoutConfiguration {
    @Bean(destroyMethod = "close")
    HttpClient bankPayoutHttpClient(BankPayoutProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    BankPayoutGateway bankPayoutGateway(
            BankPayoutProperties properties, HttpClient bankPayoutHttpClient, ObjectMapper mapper, Clock clock) {
        var factory = new JdkClientHttpRequestFactory(bankPayoutHttpClient);
        factory.setReadTimeout(properties.readTimeout());
        var rest = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .build();
        return new HttpBankPayoutGateway(rest, mapper, clock);
    }
}
