package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.outbound.OutboundHooks;
import org.junit.jupiter.api.Test;

/** The demo exists only under the demo profile (AC-9.1, AC-9.3); the stream limit is a hard 503. */
class DemoProfileTest {
    @Test
    void ac_9_3_defaultProfileHasNoDemoEndpointsBeansOrCrashHook() {
        try (var stack = new DemoStack(false, false)) {
            assertThat(stack.get("/api/demo/summary").status()).isEqualTo(404);
            assertThat(stack.post("/api/demo/actions/pay").status()).isEqualTo(404);
            assertThat(stack.post("/api/demo/seed").status()).isEqualTo(404);
            assertThat(stack.post("/api/demo/visit").status()).isEqualTo(404);
            assertThat(stack.get("/api/demo/stream").status()).isEqualTo(404);
            assertThat(stack.context.getBeansOfType(DemoCrashHooks.class)).isEmpty();
            assertThat(stack.context.getBeansOfType(DemoActions.class)).isEmpty();
            assertThat(stack.context.getBean(OutboundHooks.class).getClass().getName())
                    .doesNotContain("Demo");
            // The audit endpoint is not a demo feature and stays available everywhere.
            assertThat(stack.get("/api/audit/invariants").status()).isEqualTo(200);
        }
    }

    @Test
    void ac_9_1_streamsBeyondTheLimitAreRefusedWith503() {
        try (var stack = new DemoStack(true, false, "--ledgerlab.demo.max-streams=2")) {
            try (var one = new SseCollector(stack.appPort, null);
                    var two = new SseCollector(stack.appPort, null)) {
                assertThat(one.status).isEqualTo(200);
                assertThat(two.status).isEqualTo(200);
                try (var three = new SseCollector(stack.appPort, null)) {
                    assertThat(three.status).isEqualTo(503);
                }
            }
        }
    }
}
