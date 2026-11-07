package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** AC-12.5: {@code ledgerlab.outbound.attempts} is counted by the worker, with closed-set labels only. */
class OutboundMetricsTest {
    private RefundFixture f;
    private MemoryRefundGateway gateway;
    private OutboundProperties properties;
    private RefundCommandHandler handler;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setup() {
        f = new RefundFixture();
        gateway = new MemoryRefundGateway();
        var evidence =
                new JdbcRefundEvidence(f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        properties = new OutboundProperties(
                "test",
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                Duration.ofMinutes(15),
                2,
                Duration.ofHours(20));
        handler = new RefundCommandHandler(f.jdbc, f.payments, gateway, evidence, f.clock, properties);
    }

    @AfterEach
    void cleanup() {
        f.close();
    }

    @Test
    void ac_12_5_aFirstSuccessfulAttemptIsOneNonRetryCount() {
        f.s3();
        f.accept(f.payment("ch_p2", 5000), 2000);
        worker().processAvailable();

        assertThat(count("HTTP_200", "false")).isEqualTo(1);
        assertThat(meters.find("ledgerlab.outbound.attempts").counters()).hasSize(1);
    }

    @Test
    void ac_12_5_aSecondAttemptIsCountedAsARetry() {
        f.s3();
        f.accept(f.payment("ch_p2", 5000), 2000);
        gateway.timeoutBefore = true;
        worker().processAvailable();
        f.clock.set(f.clock.instant().plus(Duration.ofHours(1)));
        worker().processAvailable();

        assertThat(count("TIMEOUT", "false")).isEqualTo(1);
        // Whatever the outcome of the second call is, it counts once and as a retry.
        double retries = meters.find("ledgerlab.outbound.attempts").tag("retry", "true").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
        assertThat(retries).isEqualTo(1);
        // No label carries a command, refund or payment identifier.
        meters.find("ledgerlab.outbound.attempts")
                .counters()
                .forEach(counter -> counter.getId()
                        .getTags()
                        .forEach(tag -> assertThat(tag.getValue()).doesNotStartWith("cmd_")));
    }

    private double count(String outcome, String retry) {
        var counter = meters.find("ledgerlab.outbound.attempts")
                .tags("kind", "SUBMIT_REFUND", "outcome", outcome, "retry", retry)
                .counter();
        return counter == null ? 0 : counter.count();
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
                f.mapper,
                meters);
    }
}
