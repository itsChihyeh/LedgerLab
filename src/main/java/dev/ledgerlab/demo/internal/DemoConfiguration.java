package dev.ledgerlab.demo.internal;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** Wiring that exists only in the {@code demo} profile (AC-9.1). */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@EnableScheduling
@EnableConfigurationProperties(DemoProperties.class)
class DemoConfiguration {
    /** The port this server actually listens on, learned when the web server starts (it may be random). */
    static final class LocalPort implements ApplicationListener<WebServerInitializedEvent> {
        private volatile int port;

        @Override
        public void onApplicationEvent(WebServerInitializedEvent event) {
            port = event.getWebServer().getPort();
        }

        int port() {
            return port;
        }
    }

    @Bean
    LocalPort demoLocalPort() {
        return new LocalPort();
    }

    @Bean
    DemoClients demoClients(DemoProperties properties, LocalPort localPort, ObjectMapper mapper) {
        String configured = properties.appBaseUrl();
        JsonClient app = new JsonClient(
                () -> configured != null && !configured.isBlank() ? configured : "http://127.0.0.1:" + localPort.port(),
                properties.httpTimeout(),
                mapper);
        JsonClient simulator = new JsonClient(properties::simulatorBaseUrl, properties.httpTimeout(), mapper);
        return new DemoClients(app, simulator);
    }

    /** The readonly role for the page's own queries; Boot's {@code app} DataSource stays the default. */
    @Bean(defaultCandidate = false)
    DataSource demoReadonlyDataSource(
            @Value("${ledgerlab.readonly-datasource.url}") String url,
            @Value("${ledgerlab.readonly-datasource.username}") String username,
            @Value("${ledgerlab.readonly-datasource.password}") String password) {
        return new DriverManagerDataSource(url, username, password);
    }
}
