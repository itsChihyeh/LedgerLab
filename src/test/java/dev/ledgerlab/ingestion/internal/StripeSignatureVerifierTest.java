package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StripeSignatureVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-21T14:00:00Z");
    private static final byte[] BODY = "{\"id\":\"evt_test\"}".getBytes(StandardCharsets.UTF_8);
    private final StripeSignatureVerifier verifier =
            new StripeSignatureVerifier(new StripeProperties(StripeSignatures.SECRET, ""), new MutableClock(NOW));

    @Test
    void ac_2_1_anyMatchingV1IsAccepted() {
        String valid = StripeSignatures.sign(BODY, NOW.getEpochSecond());
        assertThat(verifier.verify(BODY, valid + ",v1=" + "00".repeat(32))).isEqualTo(NOW);
        assertThat(verifier.verify(BODY, "v1=" + "00".repeat(32) + "," + valid)).isEqualTo(NOW);
        assertThat(verifier.verify(BODY, valid + ",v0=legacy")).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "t=abc,v1=00",
                "t=1",
                "v1=00",
                "t=1,t=2,v1=00",
                "t=1,v1=xyz",
                "t=1,v1=00,broken",
                "t=9223372036854775808,v1=00"
            })
    void ac_2_1_malformedHeadersAreRejected(String header) {
        assertThatThrownBy(() -> verifier.verify(BODY, header)).isInstanceOf(InvalidSignatureException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "9223372036854775808", "9223372036854775807", "0"})
    void ac_2_2_invalidAndExtremeTimestampsCannotBypassWindow(String timestamp) {
        assertThatThrownBy(() -> verifier.verify(BODY, "t=" + timestamp + ",v1=" + "00".repeat(32)))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void ac_2_1_signatureAndTimestampAreBothRequired() {
        assertThatThrownBy(() -> verifier.verify(BODY, "v1=" + "00".repeat(32)))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(BODY, "t=" + NOW.getEpochSecond() + ",v0=legacy"))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(BODY, "t=1,t=2,v1=" + "00".repeat(32)))
                .isInstanceOf(InvalidSignatureException.class);
    }
}
