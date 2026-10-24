package dev.ledgerlab.refunds.internal;

import static dev.ledgerlab.ingestion.internal.RefundHttpServer.await;
import static dev.ledgerlab.ingestion.internal.RefundHttpServer.page;
import static dev.ledgerlab.ingestion.internal.RefundHttpServer.refund;
import static dev.ledgerlab.ingestion.internal.RefundHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ingestion.internal.RefundHttpServer;
import dev.ledgerlab.outbound.CommandSnapshot;
import dev.ledgerlab.outbound.OutboundHooks;
import dev.ledgerlab.outbound.OutboundProperties;
import dev.ledgerlab.outbound.internal.OutboundWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundHttpOutboundTest {
    private RefundFixture f;
    private RefundHttpServer server;
    private RefundCommandHandler handler;
    private OutboundProperties properties;

    @BeforeEach
    void setup() throws Exception {
        f = new RefundFixture();
        server = new RefundHttpServer();
        var gateway = server.gateway(f.jdbc, f.manager, f.mapper, f.clock, f.issues);
        var evidence =
                new JdbcRefundEvidence(f.jdbc, f.ledger, f.payments, f.queue, f.history, f.issues, f.mapper, f.clock);
        properties = new OutboundProperties(
                "http-test",
                10,
                Duration.ofSeconds(60),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                30,
                Duration.ofHours(20));
        handler = new RefundCommandHandler(f.jdbc, f.payments, gateway, evidence, f.clock, properties);
    }

    @AfterEach
    void close() {
        server.close();
        try {
            f.invariants();
        } finally {
            f.close();
        }
    }

    @Test
    void ac_3_17_httpSucceededWithoutWebhookPostsExactlyOnce() {
        String id = ready(2000);
        Instant received = f.clock.instant().plusSeconds(15);
        server.handler = exchange -> {
            f.clock.set(received);
            respond(exchange, 200, refund("re_success", "ch_p1", id, 2000, "succeeded"));
        };
        worker(new OutboundHooks() {
                    @Override
                    public void beforeWriteback(CommandSnapshot command) {
                        f.clock.set(received.plusSeconds(40));
                    }
                })
                .processAvailable();
        assertThat(f.status(id)).isEqualTo("SUCCEEDED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(entries("REFUND_EXECUTED")).isEqualTo(1);
        assertThat(worker().processAvailable()).isZero();
        assertThat(server.posts()).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT occurred_at FROM ledger.journal_entry WHERE entry_type='REFUND_EXECUTED'")
                        .query((rs, row) -> rs.getTimestamp(1).toInstant())
                        .single())
                .isEqualTo(received);
        assertThat(f.jdbc.sql("SELECT evidence_source FROM core.refund_transition WHERE to_status='SUCCEEDED'")
                        .query(String.class)
                        .single())
                .isEqualTo("COMMAND_RESPONSE");
        var sent = server.requests.getFirst();
        assertThat(sent.form()).contains("charge=ch_p1", "amount=2000", "metadata[ledgerlab_refund_id]=" + id);
        assertThat(sent.key()).isEqualTo(id);
        assertThat(sent.authorization()).isEqualTo("Bearer " + RefundHttpServer.KEY);
        assertThat(sent.version()).isEqualTo("2026-09-30.endive");
        assertThat(sent.contentType()).startsWith("application/x-www-form-urlencoded");
        assertThat(f.number("SELECT count(*) FROM io.processor_fetch WHERE purpose='REFUND_COMMAND' AND complete"))
                .isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT request::text FROM io.processor_fetch WHERE purpose='REFUND_COMMAND'")
                        .query(String.class)
                        .single())
                .contains(id, "Idempotency-Key", "metadata[ledgerlab_refund_id]")
                .doesNotContain(RefundHttpServer.KEY, "Authorization");
    }

    @Test
    void ac_3_18_httpPendingKeepsPayableAndReceivable() {
        String id = ready(3000);
        long receivable = f.balance(1101);
        server.handler = e -> respond(e, 200, refund("re_pending", "ch_p1", id, 3000, "pending"));
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(f.balance(2103)).isEqualTo(3000);
        assertThat(f.balance(1101)).isEqualTo(receivable).isZero();
        assertThat(entries("REFUND_EXECUTED")).isZero();
    }

    @Test
    void ac_3_8_httpExplicitRejectionReleasesAndPreservesReason() {
        String id = ready(2000);
        server.handler =
                e -> respond(e, 400, "{\"error\":{\"type\":\"invalid_request_error\",\"code\":\"amount_too_large\"}}");
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("FAILED");
        assertThat(f.balance(2103)).isZero();
        assertThat(f.balance(2102)).isEqualTo(9680);
        assertThat(entries("REFUND_FAILED")).isEqualTo(1);
        assertThat(f.jdbc.sql("SELECT failure_reason FROM core.refund")
                        .query(String.class)
                        .single())
                .isEqualTo("amount_too_large");
    }

    @Test
    void ac_3_10_tenHttpTimeoutsKeepSameKeyAndNeverRelease() {
        String id = ready(2000);
        var attempts = new AtomicInteger();
        var created = new ConcurrentHashMap<String, String>();
        server.handler = e -> {
            if (e.getRequestMethod().equals("POST")) {
                if (attempts.incrementAndGet() == 10) {
                    created.putIfAbsent(
                            e.getRequestHeaders().getFirst("Idempotency-Key"),
                            refund("re_timeout", "ch_p1", id, 2000, "pending"));
                }
                server.stall(e);
            } else respond(e, 200, page(new ArrayList<>(created.values()), false));
        };
        for (int i = 0; i < 10; i++) {
            worker().processAvailable();
            assertThat(f.status(id)).isEqualTo("UNKNOWN");
            assertThat(commandStatus()).isEqualTo("UNKNOWN");
            advance(Duration.ofMinutes(1));
        }
        advance(Duration.ofDays(7));
        assertThat(f.status(id)).isEqualTo("UNKNOWN");
        assertThat(f.balance(2103)).isEqualTo(2000);
        assertThat(entries("REFUND_FAILED")).isZero();
        assertThat(server.posts()).isEqualTo(10);
        assertThat(server.requests.stream()
                        .filter(r -> r.method().equals("POST"))
                        .map(RefundHttpServer.Request::key))
                .containsOnly(id);
        assertThat(f.number("SELECT count(*) FROM io.processor_fetch WHERE purpose LIKE 'REFUND_%'"))
                .isEqualTo(19);
        // T06 semantics: nine timeouts before creation, then one timeout after creation.
        assertThat(f.number("SELECT count(*) FROM io.outbound_attempt WHERE error='TRANSPORT_FAILURE'"))
                .isEqualTo(10);
        assertThat(created).hasSize(1);
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(created).hasSize(1);
        assertThat(server.posts()).isEqualTo(10);
    }

    @Test
    void ac_3_11_disconnectAfterCreationAndCrashRecoversPending() {
        String id = ready(2000);
        var created = new ConcurrentHashMap<String, String>();
        server.handler = e -> {
            if (e.getRequestMethod().equals("POST")) {
                created.putIfAbsent(
                        e.getRequestHeaders().getFirst("Idempotency-Key"),
                        refund("re_only", "ch_p1", id, 2000, "pending"));
                e.close();
            } else respond(e, 200, page(new ArrayList<>(created.values()), false));
        };
        assertThatThrownBy(() -> worker(new OutboundHooks() {
                            @Override
                            public void beforeWriteback(CommandSnapshot command) {
                                throw new IllegalStateException("crash");
                            }
                        })
                        .processAvailable())
                .hasMessage("crash");
        assertThat(commandStatus()).isEqualTo("IN_FLIGHT");
        advance(Duration.ofSeconds(61));
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(commandStatus()).isEqualTo("DONE");
        assertThat(created).hasSize(1);
        assertThat(server.posts()).isEqualTo(1);
        assertThat(server.requests.getLast().method()).isEqualTo("GET");
        assertThat(f.jdbc.sql("SELECT evidence_source FROM core.refund_transition WHERE to_status='SUBMITTED'")
                        .query(String.class)
                        .single())
                .isEqualTo("COMMAND_QUERY");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ac_8_7_expiredKeyRequiresEveryPageAndNeverResendsWhenFoundOrIncomplete(boolean timeout) {
        String id = ready(2000);
        var keys = new ConcurrentHashMap<String, String>();
        List<String> objects = new ArrayList<>();
        server.handler = e -> {
            keys.put(id, "re_original");
            objects.add(refund("re_original", "ch_p1", id, 2000, "pending"));
            e.close();
        };
        worker().processAvailable();
        Instant first = firstSent();
        advance(Duration.ofHours(25));
        keys.clear();
        for (int i = 1; i < 12; i++) objects.addFirst(refund("re_new_" + i, "ch_p1", "rfd_other_" + i, 1, "pending"));
        assertThat(objects).hasSize(12);
        assertThat(keys).isEmpty();
        server.handler = e -> {
            assertThat(e.getRequestMethod()).isEqualTo("GET");
            if (e.getRequestURI().getRawQuery().contains("starting_after")) {
                if (timeout) server.stall(e);
                else respond(e, 200, page(objects.subList(10, 12), false));
            } else respond(e, 200, page(objects.subList(0, 10), true));
        };
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo(timeout ? "UNKNOWN" : "SUBMITTED");
        assertThat(commandStatus()).isEqualTo(timeout ? "UNKNOWN" : "DONE");
        assertThat(firstSent()).isEqualTo(first);
        assertThat(server.posts()).isEqualTo(1);
        assertThat(server.requests.stream()
                        .filter(r -> r.method().equals("GET"))
                        .map(RefundHttpServer.Request::query))
                .containsExactly("charge=ch_p1&limit=100", "charge=ch_p1&limit=100&starting_after=re_new_2");
        assertThat(f.number("SELECT count(*) FROM io.processor_fetch WHERE purpose='REFUND_QUERY'"))
                .isEqualTo(1);
    }

    @Test
    void ac_8_7_oldRecoveryWorkerCannotPostAfterLosingLeaseBetweenQueryAndSend() throws Exception {
        String id = ready(2000);
        server.handler = e -> respond(e, 503, "{}");
        worker().processAvailable();
        advance(Duration.ofHours(25));
        var created = new AtomicInteger();
        server.handler = e -> {
            if (e.getRequestMethod().equals("GET")) respond(e, 200, page(List.of(), false));
            else {
                created.incrementAndGet();
                respond(e, 200, refund("re_winner", "ch_p1", id, 2000, "pending"));
            }
        };
        var beforeSend = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var old = worker(new OutboundHooks() {
            @Override
            public void beforeHttp(CommandSnapshot command) {
                if (calls.incrementAndGet() == 2) {
                    beforeSend.countDown();
                    await(release);
                }
            }
        });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(old::processAvailable);
            try {
                await(beforeSend);
                assertThat(worker().processAvailable()).isZero();
                advance(Duration.ofSeconds(61));
                worker().processAvailable();
            } finally {
                release.countDown();
            }
            result.get(10, TimeUnit.SECONDS);
        }
        assertThat(created).hasValue(1);
        assertThat(server.posts()).isEqualTo(2); // Initial unknown attempt plus the current lease's resend.
        assertThat(f.status(id)).isEqualTo("SUBMITTED");
        assertThat(f.number("SELECT lease_epoch FROM io.outbound_command")).isEqualTo(3);
        assertThat(
                        f.number(
                                "SELECT count(*) FROM io.outbound_attempt WHERE request_summary->>'decision'='COMPLETE_NOT_FOUND_AFTER_THRESHOLD'"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "charge", "metadata"})
    void ac_3_17_mismatchedResponseCannotPostLocalAmount(String field) {
        String id = ready(2000);
        String body = refund(
                "re_wrong",
                field.equals("charge") ? "ch_other" : "ch_p1",
                field.equals("metadata") ? "rfd_other" : id,
                field.equals("amount") ? 1999 : 2000,
                "succeeded");
        server.handler = e -> respond(e, 200, body);
        worker().processAvailable();
        assertThat(f.status(id)).isEqualTo("UNKNOWN");
        assertThat(f.balance(2103)).isEqualTo(2000);
        assertThat(entries("REFUND_EXECUTED")).isZero();
    }

    private String ready(long amount) {
        return f.accept(f.s3(), amount).refundId();
    }

    private void advance(Duration duration) {
        f.clock.set(f.clock.instant().plus(duration));
    }

    private OutboundWorker worker() {
        return worker(new OutboundHooks() {});
    }

    private OutboundWorker worker(OutboundHooks hooks) {
        return new OutboundWorker(
                f.jdbc, f.manager, List.of(handler), f.clock, properties, new Random(7), hooks, f.mapper);
    }

    private String commandStatus() {
        return f.jdbc.sql("SELECT status FROM io.outbound_command")
                .query(String.class)
                .single();
    }

    private Instant firstSent() {
        return f.jdbc.sql("SELECT first_sent_at FROM io.outbound_command")
                .query((rs, row) -> rs.getTimestamp(1).toInstant())
                .single();
    }

    private long entries(String type) {
        return f.jdbc.sql("SELECT count(*) FROM ledger.journal_entry WHERE entry_type=:type")
                .param("type", type)
                .query(Long.class)
                .single();
    }
}
