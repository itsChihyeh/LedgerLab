package dev.ledgerlab.sim.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundRelayConfigurationTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.com",
                "http://127.example.com",
                "http://10.0.0.1",
                "http://[::2]",
                "ftp://localhost",
                "/relative",
                "https://user:password@example.com",
                "https://example.com?key=value",
                "https://example.com#fragment",
                "bad URI"
            })
    void ac_8_2_rejectsUnsafeUpstreamAtConstruction(String upstream) {
        assertThatThrownBy(() -> OutboundRelay.validateUpstream(upstream)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.stripe.com",
                "http://localhost:8081",
                "http://127.0.0.1:8081",
                "http://[::1]:8081",
                "http://[0:0:0:0:0:0:0:1]:8081"
            })
    void ac_8_2_acceptsHttpsAndLoopback(String upstream) {
        assertThat(OutboundRelay.validateUpstream(upstream + "/")).isEqualTo(upstream);
    }

    @Test
    void ac_8_6_outboundMatchingUsesMethodAndPathPrefix() throws Exception {
        var plan = RelayPlan.parse(new tools.jackson.databind.json.JsonMapper().readTree("""
                {"rules":[{"direction":"OUTBOUND","method":"POST","path_prefix":"/v1/refunds","action":"PASS","times":2}]}
                """));
        assertThat(plan.rules()).isEmpty();
        assertThat(plan.outboundRules()).singleElement().satisfies(rule -> {
            assertThat(rule.matches("POST", "/v1/refunds")).isTrue();
            assertThat(rule.matches("GET", "/v1/refunds")).isFalse();
            assertThat(rule.matches("POST", "/v1/payouts")).isFalse();
        });
    }
}
