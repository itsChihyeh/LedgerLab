package dev.ledgerlab.sim.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RecordingRedactorTest {
    private final JsonMapper mapper = new JsonMapper();
    private final RecordingRedactor redactor = new RecordingRedactor();

    @Test
    void ac_8_3_removesUnusedRiskAndNetworkKeysBeforeStrictScan() {
        var input = mapper.readTree(
                "{\"outcome\":{\"risk_level\":\"normal\",\"risk_score\":12,\"network_status\":\"approved_by_network\"}}");
        assertThat(redactor.redact(input).toString()).isEqualTo("{\"outcome\":{}}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "email",
                "receipt_email",
                "name",
                "first_name",
                "last_name",
                "address",
                "phone",
                "billing_details",
                "shipping",
                "receipt_url",
                "client_secret",
                "fingerprint"
            })
    void ac_8_3_removesPrivateFieldsAtEveryDepth(String field) {
        var raw = mapper.createObjectNode();
        raw.putArray("nested").addObject().putObject(field).put("private", "sensitive");
        assertThat(redactor.redact(raw).toString()).isEqualTo("{\"nested\":[{}]}");
        assertThat(raw.toString()).contains("sensitive");
    }

    @Test
    void ac_8_3_scrubsEmbeddedAccountsAndEmailsAndPreservesFacts() {
        var raw = mapper.createObjectNode();
        raw.put("description", "connected " + "acct_" + "synthetic" + " received");
        raw.putArray("values")
                .add("person" + "@" + "example.invalid")
                .add(1234)
                .add(false)
                .addNull();
        raw.put("id", "ch_synthetic")
                .put("created", 1793577600)
                .put("currency", "cad")
                .put("status", "succeeded");
        var result = redactor.redact(raw);
        assertThat(result.path("description").asString()).isEqualTo("connected acct_" + "REDACTED received");
        assertThat(result.path("values").toString()).isEqualTo("[null,1234,false,null]");
        for (String field : new String[] {"id", "created", "currency", "status"}) {
            assertThat(result.path(field)).isEqualTo(raw.path(field));
        }
        RecordingRedactor.scan(result.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk_", "rk_", "whsec_", "_secret_", "@", "acct_", "acct_" + "REDACTEDx", "ACCT_"})
    void ac_8_3_scannerFailsClosedWithoutEchoingSecrets(String prefix) {
        assertThatThrownBy(() -> RecordingRedactor.scan(prefix + "private"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsafe recording export");
    }

    @Test
    void ac_8_3_parserErrorsAndUnknownSecretsNeverEscape() {
        for (String raw : new String[] {"not json private", "{} {}", "{\"unknown\":\"sk_" + "private\"}"}) {
            assertThatThrownBy(() -> redactor.parse(raw.getBytes(StandardCharsets.UTF_8), mapper))
                    .hasMessage("Recording cannot be safely exported")
                    .hasNoCause();
        }
    }
}
