package dev.ledgerlab.sim.relay;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sim.relay.outbound-enabled", havingValue = "true")
public class RelayWebConfiguration {
    @Bean
    OrderedFormContentFilter formContentFilter() {
        return new OrderedFormContentFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                // FormContentFilter otherwise consumes PUT/PATCH/DELETE bodies before the proxy sees them.
                return request.getRequestURI().startsWith(request.getContextPath() + "/relay/stripe-api/");
            }
        };
    }
}
