package dev.ledgerlab.payouts.internal;

import static dev.ledgerlab.payouts.internal.BankHttpServer.payment;
import static dev.ledgerlab.payouts.internal.BankHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.payouts.BankPayoutGateway;
import dev.ledgerlab.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class BankPayoutGatewayTest {
    private static final String ID = "mpo_01JB0000000000000000000001";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T15:00:00Z"));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "requestId",
                "paymentId",
                "status",
                "executionDate",
                "reason",
                "reasonNull",
                "garbage",
                "trailing"
            })
    void ac_7_6_invalidOrMismatchedResponseIsUncertainNeverARejection(String field) throws Exception {
        try (var bank = new BankHttpServer()) {
            String valid = payment(ID, "REJECTED");
            String raw = switch (field) {
                case "requestId" -> valid.replace(ID, "mpo_other");
                case "paymentId" -> valid.replace("bpm_only", "wrong");
                case "status" -> valid.replace("REJECTED", "OTHER");
                case "executionDate" -> valid.replace("2026-11-03", "invalid");
                case "reason" -> valid.replace("ACCOUNT_CLOSED", "private raw bank message");
                case "reasonNull" -> valid.replace("\"ACCOUNT_CLOSED\"", "null");
                case "garbage" -> "not JSON";
                default -> valid + " {}";
            };
            bank.handler = e -> respond(e, 201, raw);
            var result = bank.gateway(clock).submit(ID, 9680);
            assertThat(result.value()).isInstanceOf(BankPayoutGateway.Unknown.class);
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body()).isEqualTo(raw);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 429, 500, 503})
    void ac_7_6_httpErrorAloneNeverProvesBankRefusal(int status) throws Exception {
        try (var bank = new BankHttpServer()) {
            bank.handler = e -> respond(e, status, "{\"code\":\"UNEXPECTED\"}");
            var result = bank.gateway(clock).submit(ID, 9680);
            assertThat(result.value()).isInstanceOf(BankPayoutGateway.Unknown.class);
            assertThat(result.status()).isEqualTo(status);
        }
    }

    @Test
    void ac_7_8_onlyExplicitQueryNotFoundPermitsResubmit() throws Exception {
        try (var bank = new BankHttpServer()) {
            bank.handler = e -> respond(e, 404, "{\"code\":\"NOT_FOUND\"}");
            var gateway = bank.gateway(clock);
            assertThat(gateway.query(ID).value()).isInstanceOf(BankPayoutGateway.NotFound.class);
            assertThat(gateway.submit(ID, 9680).value()).isInstanceOf(BankPayoutGateway.Unknown.class);
            bank.handler = e -> respond(e, 404, "{}");
            assertThat(gateway.query(ID).value()).isInstanceOf(BankPayoutGateway.Unknown.class);
        }
    }

    @Test
    void ac_7_1_configuredHttpBeanUsesBankEndpointTimeoutsAndInjectedClock() throws Exception {
        try (var bank = new BankHttpServer()) {
            runner().withPropertyValues(
                            "ledgerlab.bank.payments.enabled=true",
                            "ledgerlab.bank.payments.base-url=" + bank.url(),
                            "ledgerlab.bank.payments.connect-timeout=2s",
                            "ledgerlab.bank.payments.read-timeout=3s")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(BankPayoutGateway.class);
                        var settings = context.getBean(BankPayoutProperties.class);
                        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                        assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(3));
                        var found = (BankPayoutGateway.Found) context.getBean(BankPayoutGateway.class)
                                .submit(ID, 9680)
                                .value();
                        assertThat(found.receivedAt()).isEqualTo(clock.instant());
                        assertThat(found.requestId()).isEqualTo(ID);
                    });
        }
    }

    @Test
    void ac_7_16_httpAdapterIsAbsentUnlessEnabled() {
        runner().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BankPayoutGateway.class));
        runner().withPropertyValues("ledgerlab.bank.payments.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BankPayoutGateway.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "connect-timeout=0s",
                "read-timeout=-1s",
                "base-url=file:///tmp/bank",
                "base-url=http://user:pass@localhost"
            })
    void ac_7_6_invalidBankConfigurationFailsStartup(String property) {
        runner().withPropertyValues("ledgerlab.bank.payments.enabled=true", "ledgerlab.bank.payments." + property)
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(BankPayoutConfiguration.class)
                .withBean(java.time.Clock.class, () -> clock)
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }
}
