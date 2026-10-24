package dev.ledgerlab.reconciliation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ReconJsonTest {
    private final ReconJson json = new ReconJson(JsonMapper.builder().build());

    private Finding finding(String expected, String actual) {
        return new Finding("FEE_MISMATCH", "payment", "p2", expected, actual, "[]", null);
    }

    @Test
    void ac_5_21_fingerprintNormalizesNestedObjectsAndPreservesLongPrecision() {
        var first = finding("{\"b\":[{\"z\":9223372036854775807,\"a\":null}],\"a\":true}", "205");
        var second = finding("{ \"a\": true, \"b\": [ { \"a\": null, \"z\": 9223372036854775807 } ] }", "205");
        assertThat(json.fingerprint(first)).matches("[0-9a-f]{64}").isEqualTo(json.fingerprint(second));
        assertThat(json.fingerprint(first)).isNotEqualTo(json.fingerprint(finding(first.expected(), "206")));
        assertThat(json.fingerprint(finding("[1,2]", "null"))).isNotEqualTo(json.fingerprint(finding("[2,1]", "null")));
        assertThat(json.fingerprint(finding("\"1\"", "null"))).isNotEqualTo(json.fingerprint(finding("1", "null")));
        assertThat(json.fingerprint(first))
                .isNotEqualTo(json.fingerprint(
                        new Finding("AMOUNT_MISMATCH", "payment", "p2", first.expected(), "205", "[]", null)));
        assertThat(json.fingerprint(first))
                .isNotEqualTo(json.fingerprint(
                        new Finding("FEE_MISMATCH", "batch", "p2", first.expected(), "205", "[]", null)));
        assertThat(json.fingerprint(first))
                .isNotEqualTo(json.fingerprint(
                        new Finding("FEE_MISMATCH", "payment", "p3", first.expected(), "205", "[]", null)));
        assertThat(json.fingerprint(first))
                .isEqualTo(json.fingerprint(new Finding(
                        "FEE_MISMATCH", "payment", "p2", first.expected(), "205", "[\"new-evidence\"]", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1", "1.0", "9223372036854775808", "-9223372036854775809", "1e2"})
    void ac_5_21_fingerprintRejectsFractionalAndOverflowNumbers(String value) {
        assertThatThrownBy(() -> json.fingerprint(finding(value, "null"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ac_5_9_invalidRuleDataAndConfigurationAreRejected() {
        assertThatThrownBy(() -> json.array("{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconProperties(Duration.ZERO, "v1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconProperties(Duration.ofSeconds(-1), "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconProperties(Duration.ofSeconds(1), " "))
                .isInstanceOf(IllegalArgumentException.class);
        var instant = Instant.parse("2026-11-13T05:00:00Z");
        for (List<String> ids : List.of(List.of(""), List.of("file_x", "file_x"))) {
            assertThatThrownBy(() -> new RunRequest(RunRequest.Kind.BANK, instant, instant, "v1", ids, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RunRequest(RunRequest.Kind.BANK, instant, instant, " ", List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconRule.Explanation("", "[]")).isInstanceOf(IllegalArgumentException.class);
        for (Finding.Key key : List.of(
                new Finding.Key("", "p", "1"),
                new Finding.Key("FEE_MISMATCH", "", "1"),
                new Finding.Key("FEE_MISMATCH", "p", ""))) {
            assertThatThrownBy(
                            () -> new Finding(key.category(), key.subjectType(), key.subjectId(), "1", "2", "[]", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Finding("TIMING_DIFFERENCE", "payment", "p2", "1", "2", "[]", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding("FEE_MISMATCH", "payment", "p2", "1", "2", "[]", instant))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
