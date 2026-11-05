package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The public gate on a running application and a real database (AC-9.3, AC-9.4): the write routes are closed in
 * the demo profile and unchanged in the default one, and refused requests leave the books and the inbox alone.
 */
class DemoPublicSurfaceTest {
    private static final List<String[]> WRITES = List.of(
            new String[] {"POST", "/api/corrections"},
            new String[] {"POST", "/api/refunds"},
            new String[] {"POST", "/api/refunds/00000000-0000-0000-0000-000000000000/cancel"},
            new String[] {"POST", "/api/payouts"},
            new String[] {"POST", "/api/payouts/00000000-0000-0000-0000-000000000000/query"},
            new String[] {"POST", "/api/batches/00000000-0000-0000-0000-000000000000/recheck"},
            new String[] {"POST", "/api/recon/runs"},
            new String[] {"POST", "/api/recon/processor-snapshots"},
            new String[] {"POST", "/api/recon/issues/1/accept"},
            new String[] {"POST", "/api/recon/issues/1/resolve"},
            new String[] {"POST", "/api/bank-statements"},
            // Fault hooks have no HTTP entry in any profile; in the demo nothing at all answers on these paths.
            new String[] {"POST", "/test/break-refund-lock"},
            new String[] {"POST", "/sim/faults"},
            new String[] {"POST", "/actuator/shutdown"},
            new String[] {"PUT", "/api/payments"},
            new String[] {"DELETE", "/api/payments/x"});

    private static final List<String> READS = List.of(
            "/api/overview",
            "/api/payments",
            "/api/payouts",
            "/api/batches",
            "/api/recon/issues",
            "/api/recon/runs",
            "/api/corrections",
            "/api/audit/invariants",
            "/api/demo/summary");

    @Test
    void ac_9_3_everyWriteRouteIsA404InTheDemoAndReadsStillAnswer() {
        try (var stack = new DemoStack(true, false)) {
            for (String[] write : WRITES) {
                assertThat(stack.call(write[0], write[1], Map.of(), "{}").status())
                        .as(write[0] + " " + write[1])
                        .isEqualTo(404);
            }
            for (String read : READS) {
                assertThat(stack.get(read).status()).as("GET " + read).isEqualTo(200);
            }
            // Spelling tricks do not reopen a closed route.
            assertThat(stack.call("POST", "/api/refunds;x=1", Map.of(), "{}").status())
                    .isEqualTo(404);
            assertThat(stack.call("POST", "/api//refunds", Map.of(), "{}").status())
                    .isEqualTo(404);
            assertThat(stack.call("POST", "/api/%72efunds", Map.of(), "{}").status())
                    .isEqualTo(404);
            // A forged internal header is not the process secret.
            assertThat(stack.call("POST", "/api/refunds", Map.of(DemoGateFilter.INTERNAL_HEADER, "guess"), "{}")
                            .status())
                    .isEqualTo(404);
        }
    }

    @Test
    void ac_9_3_theDefaultProfileKeepsItsWriteRoutes() {
        try (var stack = new DemoStack(false, false)) {
            // Without a valid body these are refused by the endpoint itself (400, 415, ...), which is the point:
            // the route exists, and nothing in the default profile turns it into a 404.
            for (String[] write : List.of(
                    new String[] {"POST", "/api/refunds"},
                    new String[] {"POST", "/api/payouts"},
                    new String[] {"POST", "/api/corrections"},
                    new String[] {"POST", "/api/recon/runs"},
                    new String[] {"POST", "/api/bank-statements"})) {
                assertThat(stack.call(write[0], write[1], Map.of(), "{}").status())
                        .as(write[0] + " " + write[1])
                        .isNotEqualTo(404);
            }
            assertThat(stack.get("/api/payments").status()).isEqualTo(200);
            assertThat(stack.call("POST", "/webhooks/stripe", Map.of(), "{}").status())
                    .isEqualTo(401);
        }
    }

    @Test
    void ac_9_3_theWebhookAnswersOnlyToTheConfiguredSources() {
        try (var stack = new DemoStack(true, false)) {
            // This test process is on loopback, which is an allowed source by default: the request reaches the
            // signature check, which refuses it for having none.
            assertThat(stack.call("POST", "/webhooks/stripe", Map.of(), "{}").status())
                    .isEqualTo(401);
        }
        try (var stack = new DemoStack(true, false, "--ledgerlab.demo.webhook-allowed-sources=10.99.0.0/16")) {
            assertThat(stack.call("POST", "/webhooks/stripe", Map.of(), "{}").status())
                    .isEqualTo(404);
            // The header cannot make a stranger look like the relay.
            assertThat(stack.call("POST", "/webhooks/stripe", Map.of("X-Forwarded-For", "10.99.0.5"), "{}")
                            .status())
                    .isEqualTo(404);
        }
    }

    @Test
    void ac_9_4_theTwentyFirstActionIsRefusedBeforeItCanWriteAnything() {
        try (var stack = new DemoStack(true, false)) {
            long entries = entries(stack);
            long events = events(stack);
            List<Integer> statuses = new ArrayList<>();
            // Unknown names: each is answered by the controller (404 UNKNOWN_ACTION) and writes nothing, so the
            // first twenty reach it and the twenty-first must be stopped in front of it.
            for (int i = 1; i <= 21; i++) {
                statuses.add(stack.post("/api/demo/actions/no-such-action").status());
            }
            assertThat(statuses.subList(0, 20)).containsOnly(404);
            DemoStack.Answer refused = stack.post("/api/demo/actions/no-such-action");
            assertThat(statuses.get(20)).isEqualTo(429);
            assertThat(refused.status()).isEqualTo(429);
            assertThat(refused.headers().get("retry-after")).isNotNull();
            assertThat(Long.parseLong(retryAfter(refused))).isBetween(1L, 3L);
            assertThat(refused.json().path("error").asString()).isEqualTo("RATE_LIMITED");
            assertThat(entries(stack)).isEqualTo(entries);
            assertThat(events(stack)).isEqualTo(events);
            // Reads are not actions.
            assertThat(stack.get("/api/overview").status()).isEqualTo(200);
        }
    }

    @Test
    void ac_9_4_theSiteWideLimitStopsManyAddressesBehindATrustedProxy() {
        try (var stack = new DemoStack(
                true, false, "--ledgerlab.demo.trusted-proxies=127.0.0.0/8", "--ledgerlab.demo.actions-per-minute=6")) {
            long entries = entries(stack);
            for (int i = 1; i <= 6; i++) {
                Map<String, String> from = Map.of("X-Forwarded-For", "198.51.100." + i);
                assertThat(stack.post("/api/demo/actions/no-such-action", from).status())
                        .as("caller " + i)
                        .isEqualTo(404);
            }
            DemoStack.Answer refused =
                    stack.post("/api/demo/actions/no-such-action", Map.of("X-Forwarded-For", "198.51.100.77"));
            assertThat(refused.status()).isEqualTo(429);
            assertThat(refused.headers().get("retry-after")).isNotNull();
            assertThat(entries(stack)).isEqualTo(entries);
        }
    }

    @Test
    void ac_9_4_aForgedForwardedForFromAnUntrustedSourceDoesNotBuyMoreActions() {
        try (var stack = new DemoStack(true, false, "--ledgerlab.demo.actions-per-ip-per-minute=3")) {
            for (int i = 1; i <= 3; i++) {
                assertThat(stack.post("/api/demo/actions/no-such-action", Map.of("X-Forwarded-For", "192.0.2." + i))
                                .status())
                        .isEqualTo(404);
            }
            for (int i = 4; i <= 8; i++) {
                assertThat(stack.post("/api/demo/actions/no-such-action", Map.of("X-Forwarded-For", "192.0.2." + i))
                                .status())
                        .as("forged caller " + i)
                        .isEqualTo(429);
            }
        }
    }

    @Test
    void ac_9_4_aFourthStreamFromOneAddressIsRefusedWith429() {
        try (var stack = new DemoStack(true, false)) {
            try (var one = new SseCollector(stack.appPort, null);
                    var two = new SseCollector(stack.appPort, null);
                    var three = new SseCollector(stack.appPort, null)) {
                assertThat(one.status).isEqualTo(200);
                assertThat(two.status).isEqualTo(200);
                assertThat(three.status).isEqualTo(200);
                try (var four = new SseCollector(stack.appPort, null)) {
                    assertThat(four.status).isEqualTo(429);
                }
            }
        }
    }

    private static String retryAfter(DemoStack.Answer answer) {
        List<String> values = answer.headers().get("retry-after");
        return Objects.requireNonNull(values).get(0);
    }

    private static long entries(DemoStack stack) {
        return stack.readonly()
                .sql("SELECT count(*) FROM ledger.v_journal_entry")
                .query(Long.class)
                .single();
    }

    private static long events(DemoStack stack) {
        return stack.readonly()
                .sql("SELECT count(*) FROM core.v_event")
                .query(Long.class)
                .single();
    }
}
