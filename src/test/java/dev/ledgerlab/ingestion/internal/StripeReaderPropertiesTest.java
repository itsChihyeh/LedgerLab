package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class StripeReaderPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StripeReaderProperties.class)
    static class Config {}

    @Test
    void ac_5_17_readerDefaultsUseStripeAndFiniteTimeouts() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            StripeReaderProperties settings = context.getBean(StripeReaderProperties.class);
            assertThat(settings.apiBaseUrl()).isEqualTo(URI.create("https://api.stripe.com"));
            assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void ac_5_17_fakeBaseUrlAndTimeoutsAreConfigurableAndRedirectsDisabled() {
        runner.withPropertyValues(
                        "ledgerlab.stripe.api-base-url=http://localhost:9090",
                        "ledgerlab.stripe.connect-timeout=120ms",
                        "ledgerlab.stripe.read-timeout=250ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(StripeReaderProperties.class);
                    assertThat(settings.apiBaseUrl()).isEqualTo(URI.create("http://localhost:9090"));
                    assertThat(settings.readTimeout()).isEqualTo(Duration.ofMillis(250));
                    try (HttpClient client = new StripeReaderConfiguration().stripeReadHttpClient(settings)) {
                        assertThat(client.connectTimeout()).contains(Duration.ofMillis(120));
                        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "connect-timeout=0s",
                "connect-timeout=-1s",
                "read-timeout=0s",
                "read-timeout=-1s",
                "api-base-url=ftp://localhost",
                "api-base-url=/relative",
                "api-base-url=http://user@localhost",
                "api-base-url=http://localhost?key=value",
                "api-base-url=http://localhost#fragment"
            })
    void ac_5_17_invalidConnectionSettingsPreventStartup(String property) {
        runner.withPropertyValues("ledgerlab.stripe." + property)
                .run(context -> assertThat(context).hasFailed());
    }
}
