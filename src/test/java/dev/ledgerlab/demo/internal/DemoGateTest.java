package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.demo.internal.DemoPathWhitelist.Kind;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The public gate in isolation, with an injected clock (AC-9.3, AC-9.4): which routes are open, who a request is
 * attributed to, and when the token buckets refuse. The same rules against a running application are in
 * {@link DemoPublicSurfaceTest}.
 */
class DemoGateTest {
    private static final String TOKEN = "unit-test-internal-token";

    /** A clock the test moves by hand; nothing here sleeps. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static DemoProperties properties(List<String> trusted, int perIp, int site, int streams) {
        return properties(trusted, List.of("127.0.0.1/32"), perIp, site, streams);
    }

    private static DemoProperties properties(
            List<String> trusted, List<String> admin, int perIp, int site, int streams) {
        return new DemoProperties(
                "http://127.0.0.1:1",
                null,
                200,
                Duration.ofMillis(500),
                Duration.ofSeconds(60),
                Duration.ofSeconds(20),
                trusted,
                List.of("10.1.0.0/16"),
                admin,
                perIp,
                site,
                streams);
    }

    private static java.net.InetAddress ip(String text) {
        return Objects.requireNonNull(Cidr.literal(text));
    }

    private record Gate(DemoGateFilter filter, DemoRateLimiter limiter, ManualClock clock) {}

    private static Gate gate(List<String> trusted, int perIp, int site, int streams) {
        ManualClock clock = new ManualClock();
        DemoProperties properties = properties(trusted, perIp, site, streams);
        DemoRateLimiter limiter = new DemoRateLimiter(clock, properties);
        return new Gate(new DemoGateFilter(limiter, properties, new DemoInternalToken(TOKEN)), limiter, clock);
    }

    private static Gate gate(List<String> trusted, List<String> admin, int perIp) {
        ManualClock clock = new ManualClock();
        DemoProperties properties = properties(trusted, admin, perIp, 300, 3);
        DemoRateLimiter limiter = new DemoRateLimiter(clock, properties);
        return new Gate(new DemoGateFilter(limiter, properties, new DemoInternalToken(TOKEN)), limiter, clock);
    }

    private static MockHttpServletRequest request(String method, String path, String remote) {
        var request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(remote);
        request.setAsyncSupported(true);
        return request;
    }

    /** Runs one request through the filter; the returned count says whether the application was reached. */
    private static int[] pass(DemoGateFilter filter, MockHttpServletRequest request, MockHttpServletResponse response)
            throws ServletException, IOException {
        AtomicInteger reached = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> reached.incrementAndGet());
        return new int[] {reached.get(), response.getStatus()};
    }

    @Test
    void ac_9_3_onlyTheListedRoutesAreOpen() {
        assertThat(DemoPathWhitelist.classify("GET", "/")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/index.html")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/assets/app-3f2a.js")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/favicon.svg")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/api/payments")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/api/payments/pi_1")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/api/audit/invariants")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("POST", "/api/demo/actions/pay")).isEqualTo(Kind.ACTION);
        assertThat(DemoPathWhitelist.classify("POST", "/api/demo/seed")).isEqualTo(Kind.ADMIN);
        assertThat(DemoPathWhitelist.classify("POST", "/api/demo/visit")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/api/demo/summary")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("GET", "/api/demo/archive")).isEqualTo(Kind.OPEN);
        assertThat(DemoPathWhitelist.classify("POST", "/api/demo/archive")).isEqualTo(Kind.BLOCKED);
        assertThat(DemoPathWhitelist.classify("GET", "/api/demo/stream")).isEqualTo(Kind.STREAM);
        assertThat(DemoPathWhitelist.classify("POST", "/webhooks/stripe")).isEqualTo(Kind.WEBHOOK);
    }

    @Test
    void ac_9_3_everythingElseIsBlockedIncludingOddSpellingsOfOpenRoutes() {
        String[][] blocked = {
            {"POST", "/api/refunds"},
            {"POST", "/api/refunds/r1/cancel"},
            {"POST", "/api/payouts"},
            {"POST", "/api/payouts/p1/query"},
            {"POST", "/api/batches/b1/recheck"},
            {"POST", "/api/corrections"},
            {"POST", "/api/recon/runs"},
            {"POST", "/api/recon/processor-snapshots"},
            {"POST", "/api/recon/issues/1/accept"},
            {"POST", "/api/recon/issues/1/resolve"},
            {"POST", "/api/bank-statements"},
            {"PUT", "/api/payments"},
            {"DELETE", "/api/payments/pi_1"},
            {"PATCH", "/api/overview"},
            {"OPTIONS", "/api/overview"},
            {"GET", "/api"},
            {"GET", "/actuator/health"},
            {"GET", "/sim/clock"},
            {"POST", "/test/break-refund-lock"},
            {"GET", "/webhooks/stripe"},
            {"POST", "/webhooks/other"},
            {"GET", "/api/demo/actions/pay"},
            {"POST", "/api/demo/actions"},
            {"POST", "/api/demo/actions/pay/extra"},
            {"POST", "/api/demo/stream"},
            {"GET", "/api/demo/other"},
            {"GET", "/internal/anything"},
            {"POST", "/index.html"},
            {"GET", "/assets"},
            {"GET", "/api/payments;jsessionid=1"},
            {"GET", "/api//payments"},
            {"GET", "/api/payments/"},
            {"GET", "/api/demo/../refunds"},
            {"POST", "/api/%72efunds"},
            {"POST", "/api/refunds%2f..%2f"},
            {"GET", "/assets/../api/refunds"},
            {"GET", "api/payments"},
        };
        for (String[] route : blocked) {
            assertThat(DemoPathWhitelist.classify(route[0], route[1]))
                    .as(route[0] + " " + route[1])
                    .isEqualTo(Kind.BLOCKED);
        }
    }

    @Test
    void ac_9_3_blockedRoutesAnswer404AndNeverReachTheApplication() throws Exception {
        Gate gate = gate(List.of(), 20, 300, 3);
        for (String path : List.of("/api/refunds", "/api/payouts", "/api/corrections", "/api/bank-statements")) {
            var response = new MockHttpServletResponse();
            int[] outcome = pass(gate.filter(), request("POST", path, "203.0.113.9"), response);
            assertThat(outcome).as(path).containsExactly(0, 404);
        }
    }

    @Test
    void ac_9_3_theScenariosOwnLoopbackCallsPassOnlyWithTheProcessSecret() throws Exception {
        Gate gate = gate(List.of(), 20, 300, 3);
        var withToken = request("POST", "/api/refunds", "127.0.0.1");
        withToken.addHeader(DemoGateFilter.INTERNAL_HEADER, TOKEN);
        assertThat(pass(gate.filter(), withToken, new MockHttpServletResponse()))
                .containsExactly(1, 200);

        var wrongToken = request("POST", "/api/refunds", "127.0.0.1");
        wrongToken.addHeader(DemoGateFilter.INTERNAL_HEADER, TOKEN + "x");
        assertThat(pass(gate.filter(), wrongToken, new MockHttpServletResponse()))
                .containsExactly(0, 404);

        var loopbackWithoutToken = request("POST", "/api/refunds", "127.0.0.1");
        assertThat(pass(gate.filter(), loopbackWithoutToken, new MockHttpServletResponse()))
                .containsExactly(0, 404);
    }

    @Test
    void ac_9_3_theWebhookOpensOnlyForTheConfiguredSources() throws Exception {
        Gate gate = gate(List.of(), 20, 300, 3);
        assertThat(pass(gate.filter(), request("POST", "/webhooks/stripe", "10.1.4.4"), new MockHttpServletResponse()))
                .containsExactly(1, 200);
        assertThat(pass(gate.filter(), request("POST", "/webhooks/stripe", "10.2.0.1"), new MockHttpServletResponse()))
                .containsExactly(0, 404);
        // A forwarded header cannot make a stranger look like the relay.
        var spoofed = request("POST", "/webhooks/stripe", "203.0.113.9");
        spoofed.addHeader("X-Forwarded-For", "10.1.4.4");
        assertThat(pass(gate.filter(), spoofed, new MockHttpServletResponse())).containsExactly(0, 404);
    }

    @Test
    void ac_9_4_theTwentyFirstActionFromOneAddressIsRefusedUntilTokensRefill() throws Exception {
        Gate gate = gate(List.of(), 20, 300, 3);
        for (int i = 1; i <= 20; i++) {
            int[] outcome = pass(
                    gate.filter(),
                    request("POST", "/api/demo/actions/pay", "203.0.113.9"),
                    new MockHttpServletResponse());
            assertThat(outcome).as("action " + i).containsExactly(1, 200);
        }
        var refused = new MockHttpServletResponse();
        assertThat(pass(gate.filter(), request("POST", "/api/demo/actions/pay", "203.0.113.9"), refused))
                .containsExactly(0, 429);
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 3L);

        // Another address is unaffected.
        assertThat(pass(
                        gate.filter(),
                        request("POST", "/api/demo/actions/pay", "203.0.113.10"),
                        new MockHttpServletResponse()))
                .containsExactly(1, 200);

        gate.clock().advance(Duration.ofSeconds(3));
        assertThat(pass(
                        gate.filter(),
                        request("POST", "/api/demo/actions/pay", "203.0.113.9"),
                        new MockHttpServletResponse()))
                .containsExactly(1, 200);
        assertThat(pass(
                        gate.filter(),
                        request("POST", "/api/demo/actions/pay", "203.0.113.9"),
                        new MockHttpServletResponse()))
                .containsExactly(0, 429);

        gate.clock().advance(Duration.ofMinutes(5));
        for (int i = 1; i <= 20; i++) {
            assertThat(pass(
                            gate.filter(),
                            request("POST", "/api/demo/actions/pay", "203.0.113.9"),
                            new MockHttpServletResponse()))
                    .as("after a quiet spell, action " + i)
                    .containsExactly(1, 200);
        }
    }

    @Test
    void ac_9_4_theSiteWideLimitHoldsAcrossAddressesAndRefusedCallersKeepTheirOwnTokens() throws Exception {
        Gate gate = gate(List.of(), 20, 5, 3);
        for (int i = 1; i <= 5; i++) {
            assertThat(pass(
                            gate.filter(),
                            request("POST", "/api/demo/actions/pay", "198.51.100." + i),
                            new MockHttpServletResponse()))
                    .containsExactly(1, 200);
        }
        var refused = new MockHttpServletResponse();
        assertThat(pass(gate.filter(), request("POST", "/api/demo/actions/pay", "198.51.100.99"), refused))
                .containsExactly(0, 429);
        assertThat(refused.getHeader("Retry-After")).isNotNull();

        // five a minute is one token every 12 seconds; the refused caller still has all its own tokens.
        gate.clock().advance(Duration.ofSeconds(12));
        assertThat(pass(
                        gate.filter(),
                        request("POST", "/api/demo/actions/pay", "198.51.100.99"),
                        new MockHttpServletResponse()))
                .containsExactly(1, 200);
    }

    @Test
    void ac_9_4_forwardedForIsBelievedOnlyFromATrustedProxyAndReadFromTheRight() {
        var clients = new ClientAddress(List.of(Cidr.parse("10.0.0.0/8"), Cidr.parse("192.168.1.7")));
        // Not from a proxy: the header is a forgery.
        assertThat(clients.of("203.0.113.9", List.of("1.2.3.4"))).isEqualTo("203.0.113.9");
        // From a proxy: the entry the proxy appended.
        assertThat(clients.of("10.0.0.2", List.of("198.51.100.5"))).isEqualTo("198.51.100.5");
        // A client-supplied leftmost entry is ignored.
        assertThat(clients.of("10.0.0.2", List.of("1.2.3.4, 198.51.100.5"))).isEqualTo("198.51.100.5");
        // Two proxies in a row, and a header split over two lines.
        assertThat(clients.of("10.0.0.2", List.of("1.2.3.4", "198.51.100.5, 10.9.9.9")))
                .isEqualTo("198.51.100.5");
        assertThat(clients.of("10.0.0.2", List.of("198.51.100.5, 192.168.1.7"))).isEqualTo("198.51.100.5");
        // Nothing but proxies, or no header at all: the connection itself.
        assertThat(clients.of("10.0.0.2", List.of("10.1.1.1"))).isEqualTo("10.0.0.2");
        assertThat(clients.of("10.0.0.2", List.of())).isEqualTo("10.0.0.2");
        // IPv6 is compared as an address, not as text.
        var v6 = new ClientAddress(List.of(Cidr.parse("fd00::/8")));
        assertThat(v6.of("fd00::1", List.of("2001:db8:0:0:0:0:0:5"))).isEqualTo("2001:db8:0:0:0:0:0:5");
        // Junk from a proxy is a key of its own, bounded in length.
        assertThat(clients.of("10.0.0.2", List.of("x".repeat(500)))).hasSize(64);
    }

    @Test
    void ac_9_4_aForgedForwardedForFromAnUntrustedSourceCannotEscapeThePerAddressLimit() throws Exception {
        Gate gate = gate(List.of(), 20, 300, 3);
        for (int i = 1; i <= 20; i++) {
            var request = request("POST", "/api/demo/actions/pay", "203.0.113.9");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            assertThat(pass(gate.filter(), request, new MockHttpServletResponse()))
                    .containsExactly(1, 200);
        }
        var request = request("POST", "/api/demo/actions/pay", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "192.0.2.200");
        assertThat(pass(gate.filter(), request, new MockHttpServletResponse())).containsExactly(0, 429);
    }

    @Test
    void ac_9_4_aTrustedProxyGivesEachRealClientItsOwnBucket() throws Exception {
        Gate gate = gate(List.of("10.0.0.0/8"), 2, 300, 3);
        for (int i = 0; i < 2; i++) {
            var request = request("POST", "/api/demo/actions/pay", "10.0.0.2");
            request.addHeader("X-Forwarded-For", "198.51.100.1");
            assertThat(pass(gate.filter(), request, new MockHttpServletResponse()))
                    .containsExactly(1, 200);
        }
        var third = request("POST", "/api/demo/actions/pay", "10.0.0.2");
        third.addHeader("X-Forwarded-For", "198.51.100.1");
        assertThat(pass(gate.filter(), third, new MockHttpServletResponse())).containsExactly(0, 429);

        var other = request("POST", "/api/demo/actions/pay", "10.0.0.2");
        other.addHeader("X-Forwarded-For", "198.51.100.2");
        assertThat(pass(gate.filter(), other, new MockHttpServletResponse())).containsExactly(1, 200);
    }

    @Test
    void ac_9_4_aFourthStreamFromOneAddressIsRefusedAndASlotComesBackWhenAStreamEnds() throws Exception {
        Gate gate = gate(List.of(), 20, 300, 3);
        List<MockHttpServletRequest> open = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var request = request("GET", "/api/demo/stream", "203.0.113.9");
            AtomicInteger reached = new AtomicInteger();
            gate.filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                reached.incrementAndGet();
                ((MockHttpServletRequest) req).startAsync();
            });
            assertThat(reached.get()).isEqualTo(1);
            open.add(request);
        }
        assertThat(gate.limiter().openStreams("203.0.113.9")).isEqualTo(3);

        var refused = new MockHttpServletResponse();
        assertThat(pass(gate.filter(), request("GET", "/api/demo/stream", "203.0.113.9"), refused))
                .containsExactly(0, 429);
        assertThat(refused.getHeader("Retry-After")).isNotNull();
        // Other addresses have their own three.
        assertThat(gate.limiter().tryStream("203.0.113.10")).isTrue();

        Objects.requireNonNull(open.get(0).getAsyncContext()).complete();
        assertThat(gate.limiter().openStreams("203.0.113.9")).isEqualTo(2);
        // Ending twice (complete, then an error callback) must not hand out the slot twice.
        assertThat(gate.limiter().tryStream("203.0.113.9")).isTrue();
        assertThat(gate.limiter().tryStream("203.0.113.9")).isFalse();
    }

    @Test
    void ac_9_4_aStreamThatFailsToStartGivesItsSlotBack() {
        Gate gate = gate(List.of(), 20, 300, 1);
        var request = request("GET", "/api/demo/stream", "203.0.113.9");
        assertThatThrownBy(() -> gate.filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class);
        assertThat(gate.limiter().openStreams("203.0.113.9")).isZero();
    }

    @Test
    void ac_9_4_cidrRejectsNamesAndOutOfRangePrefixesInsteadOfResolvingThem() {
        assertThatThrownBy(() -> Cidr.parse("example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/33")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cidr.parse("10.0.0.0/x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Cidr.parse("10.1.0.0/16").contains(ip("10.1.255.255"))).isTrue();
        assertThat(Cidr.parse("10.1.0.0/16").contains(ip("10.2.0.0"))).isFalse();
        assertThat(Cidr.parse("10.1.0.0/12").contains(ip("10.15.0.1"))).isTrue();
        assertThat(Cidr.parse("10.1.0.0/12").contains(ip("10.16.0.1"))).isFalse();
        assertThat(Cidr.parse("::1/128").contains(ip("0:0:0:0:0:0:0:1"))).isTrue();
        assertThat(Cidr.parse("10.0.0.0/8").contains(ip("::1"))).isFalse();
    }

    /** The Compose gateway Caddy reaches the published port through (deploy/Caddyfile, compose.demo.yaml). */
    private static final String GATEWAY = "10.89.77.1";

    private static int[] actionFrom(Gate gate, String remote, String forwardedFor) throws Exception {
        var request = request("POST", "/api/demo/actions/pay", remote);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return pass(gate.filter(), request, new MockHttpServletResponse());
    }

    @Test
    void ac_9_10_behindTheTrustedGatewayEachVisitorHasHisOwnTwentyActionBucket() throws Exception {
        Gate gate = gate(List.of(GATEWAY + "/32"), List.of("127.0.0.1/32", GATEWAY + "/32"), 20);
        for (int i = 1; i <= 20; i++) {
            assertThat(actionFrom(gate, GATEWAY, "198.51.100.1"))
                    .as("A, action " + i)
                    .containsExactly(1, 200);
        }
        assertThat(actionFrom(gate, GATEWAY, "198.51.100.1")).containsExactly(0, 429);
        assertThat(actionFrom(gate, GATEWAY, "198.51.100.2"))
                .as("B is unaffected")
                .containsExactly(1, 200);
    }

    @Test
    void ac_9_10_withoutATrustedProxyAForgedForwardedForSharesOneBucket() throws Exception {
        Gate gate = gate(List.of(), List.of("127.0.0.1/32", GATEWAY + "/32"), 20);
        for (int i = 1; i <= 20; i++) {
            assertThat(actionFrom(gate, GATEWAY, "198.51.100." + i))
                    .as("action " + i)
                    .containsExactly(1, 200);
        }
        assertThat(actionFrom(gate, GATEWAY, "198.51.100.200")).containsExactly(0, 429);
    }

    @Test
    void ac_9_10_theSeedRunStaysClosedWhenItComesThroughTheTrustedProxyWithForwardedHeaders() throws Exception {
        // The gateway is both a trusted proxy and an admin source, as in the deployed stack.
        Gate gate = gate(List.of(GATEWAY + "/32"), List.of("127.0.0.1/32", GATEWAY + "/32"), 20);
        for (String header : List.of("X-Forwarded-For", "Forwarded", "X-Real-IP")) {
            var viaProxy = request("POST", "/api/demo/seed", GATEWAY);
            viaProxy.addHeader(header, "198.51.100.7");
            assertThat(pass(gate.filter(), viaProxy, new MockHttpServletResponse()))
                    .as(header)
                    .containsExactly(0, 404);
        }
        // The operator's script reaches it through the same gateway address without any proxy header.
        assertThat(pass(gate.filter(), request("POST", "/api/demo/seed", GATEWAY), new MockHttpServletResponse()))
                .containsExactly(1, 200);
    }

    @Test
    void ac_9_8_inCiAMissingSimulatorJarFailsInsteadOfSkipping() {
        assertThat(DemoStack.simulatorAvailable("true", true)).isTrue();
        assertThat(DemoStack.simulatorAvailable(null, false)).isFalse();
        assertThat(DemoStack.simulatorAvailable("false", false)).isFalse();
        assertThatThrownBy(() -> DemoStack.simulatorAvailable("true", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CI=true");
    }
}
