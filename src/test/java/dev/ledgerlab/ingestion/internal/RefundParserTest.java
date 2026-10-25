package dev.ledgerlab.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.refunds.ProcessorRefundFact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RefundParserTest {
    @Test
    void ac_2_9_fakeStripeContractMapsAllRefundFieldsAndUsesEventCreated() throws Exception {
        Path fixture = Path.of("src/test/resources/contracts/refund-updated-failed.json");
        byte[] payload = Files.readAllBytes(fixture);
        assertThat(Files.readAllBytes(Path.of("simulator/src/test/resources/contracts/refund-updated-failed.json")))
                .isEqualTo(payload);
        var mapper = new JsonMapper();
        var event = new StripeEventParser(mapper).parse(payload);
        var refund = new RefundParser(mapper).parse(payload);
        assertThat(refund.fact(event.id()))
                .isEqualTo(new ProcessorRefundFact(
                        "rfd_01K8BPR0000000000000000000",
                        "re_contract_refund",
                        ProcessorRefundFact.Status.failed,
                        "lost_or_stolen_card",
                        ProcessorRefundFact.Source.WEBHOOK,
                        "evt_contract_refund_failed",
                        Instant.ofEpochSecond(1792764000),
                        2000L,
                        "ch_contract_payment"));
    }
}
