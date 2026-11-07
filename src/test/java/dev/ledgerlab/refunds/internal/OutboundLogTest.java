package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import dev.ledgerlab.testsupport.LogEvents;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** AC-12.2 and AC-12.3 for the outbound worker: every attempt line names its command, and nothing else leaks. */
class OutboundLogTest {
    private RefundFixture f;
    private MemoryRefundGateway gateway;
    private OutboundProperties properties;
    private RefundCommandHandler handler;
    private LogEvents events;

    @BeforeEach
    void setup() {
        f = new RefundFixture();
        gateway = new MemoryRefundGateway();
        var evidence =
                new JdbcRefundEvidence(f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        properties = properties(2);
        handler = new RefundCommandHandler(f.jdbc, f.payments, gateway, evidence, f.clock, properties);
        events = LogEvents.attach();
    }

    @AfterEach
    void cleanup() {
        events.close();
        f.close();
    }

    @Test
    void ac_12_2_attemptLineCarriesTheCommandIdAndKind() {
        f.s3();
        f.accept(f.payment("ch_p2", 5000), 2000);
        assertThat(worker().processAvailable()).isEqualTo(1);
        String commandId = f.jdbc.sql("SELECT id FROM io.outbound_command")
                .query(String.class)
                .single();

        List<ILoggingEvent> attempts = events.action("outbound.attempt");
        assertThat(attempts).hasSize(1);
        ILoggingEvent attempt = attempts.getFirst();
        assertThat(attempt.getMDCPropertyMap())
                .containsEntry("commandId", commandId)
                .containsEntry("commandKind", "SUBMIT_REFUND");
        assertThat(LogEvents.keyValues(attempt)).containsEntry("attempt", 1).containsEntry("outcome", "HTTP_200");
        // The worker thread is clean again for the next job.
        assertThat(MDC.get("commandId")).isNull();
        assertThat(MDC.get("commandKind")).isNull();
    }

    @Test
    void ac_12_2_reachingTheAttemptLimitIsOneExhaustedLineWithTheCommandId() {
        f.s3();
        f.accept(f.payment("ch_p2", 5000), 2000);
        gateway.timeoutBefore = true;
        worker().processAvailable();
        assertThat(events.action("outbound.exhausted")).isEmpty();
        f.clock.set(f.clock.instant().plus(Duration.ofHours(1)));
        worker().processAvailable();
        String commandId = f.jdbc.sql("SELECT id FROM io.outbound_command")
                .query(String.class)
                .single();

        assertThat(events.action("outbound.attempt")).hasSize(2);
        assertThat(LogEvents.keyValues(events.action("outbound.attempt").getFirst()))
                .containsEntry("outcome", "TIMEOUT");
        List<ILoggingEvent> exhausted = events.action("outbound.exhausted");
        assertThat(exhausted).hasSize(1);
        assertThat(exhausted.getFirst().getMDCPropertyMap()).containsEntry("commandId", commandId);
        assertThat(LogEvents.keyValues(exhausted.getFirst())).containsEntry("attempts", 2);
        assertThat(exhausted.getFirst().getLevel().toString()).isEqualTo("WARN");
    }

    @Test
    void ac_12_3_attemptLinesDoNotCarryTheRequestOrResponseBody() {
        f.s3();
        f.accept(f.payment("ch_p2", 5000), 2000);
        worker().processAvailable();
        String body = f.jdbc.sql("SELECT response_body FROM io.outbound_attempt")
                .query(String.class)
                .optional()
                .orElse("");
        String summary = f.jdbc.sql("SELECT request_summary::text FROM io.outbound_attempt")
                .query(String.class)
                .single();
        String text = events.allText();
        assertThat(summary).isNotBlank();
        assertThat(text).doesNotContain(summary);
        if (!body.isBlank()) assertThat(text).doesNotContain(body);
    }

    private OutboundProperties properties(int max) {
        return new OutboundProperties(
                "test",
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                max,
                Duration.ofHours(20));
    }

    private OutboundWorker worker() {
        return new OutboundWorker(
                f.jdbc,
                f.manager,
                List.of(handler),
                f.clock,
                properties,
                new Random(7),
                new OutboundHooks() {},
                f.mapper);
    }
}
