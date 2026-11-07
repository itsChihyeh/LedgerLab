package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * AC-9.1 (backend) and AC-9.6: every whitelisted action against the real application and the real simulator
 * process. After each action the counter it is about, the twelve invariants and the timeline are checked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoActionsTest {
    private DemoStack stack;

    @BeforeAll
    void start() {
        assumeTrue(
                DemoStack.simulatorAvailable(),
                "build the simulator first: ./mvnw -B -f simulator/pom.xml -DskipTests package");
        stack = new DemoStack(true, true);
    }

    @AfterAll
    void stop() {
        if (stack != null) stack.close();
    }

    @Test
    void ac_9_1_payBooksOnePaymentAndKeepsEveryInvariant() {
        long before = counter("payments");
        try (var feed = new SseCollector(stack.appPort, null)) {
            JsonNode result = run("pay");
            assertThat(counter("payments")).isEqualTo(before + 1);
            invariantsAllOk(result);
            feed.await("timeline", data -> data.contains("charge.succeeded PROCESSED APPLIED"), Duration.ofSeconds(3));
            feed.await("step", data -> data.contains("CAPTURED"), Duration.ofSeconds(1));
        }
    }

    @Test
    void ac_9_1_replayWebhookIsAbsorbedAsDuplicates() {
        long before = counter("duplicatesAbsorbed");
        try (var feed = new SseCollector(stack.appPort, null)) {
            invariantsAllOk(run("replay-webhook"));
            assertThat(counter("duplicatesAbsorbed")).isEqualTo(before + 2);
            feed.await("timeline", data -> data.contains("delivery DUPLICATE"), Duration.ofSeconds(3));
        }
    }

    @Test
    void ac_9_1_batchFirstIsParkedThenResolvedWithoutHelp() {
        long before = counter("parkedThenResolved");
        try (var feed = new SseCollector(stack.appPort, null)) {
            invariantsAllOk(run("batch-first"));
            assertThat(counter("parkedThenResolved")).isEqualTo(before + 1);
            feed.await("timeline", data -> data.contains("> HELD"), Duration.ofSeconds(3));
        }
    }

    @Test
    void ac_9_1_doubleRefundAcceptsOneAndRejectsOne() {
        long before = counter("overRefundsRejected");
        try (var feed = new SseCollector(stack.appPort, null)) {
            invariantsAllOk(run("double-refund"));
            assertThat(counter("overRefundsRejected")).isEqualTo(before + 1);
            feed.await("timeline", data -> data.contains("REFUND") || data.contains("refund"), Duration.ofSeconds(3));
        }
    }

    @Test
    void ac_9_1_droppedRefundResponseIsRecoveredWithoutASecondRefund() {
        long before = counter("unknownRecovered");
        invariantsAllOk(run("drop-refund-response"));
        assertThat(counter("unknownRecovered")).isEqualTo(before + 1);
    }

    @Test
    void ac_9_1_bankDropsTheReceiptAndTheWithdrawalIsPaidOnce() {
        long before = counter("unknownRecovered");
        try (var feed = new SseCollector(stack.appPort, null)) {
            invariantsAllOk(run("bank-drop-receipt"));
            assertThat(counter("unknownRecovered")).isEqualTo(before + 1);
            feed.await("timeline", data -> data.contains("UNKNOWN"), Duration.ofSeconds(3));
        }
    }

    @Test
    void ac_9_1_killedWorkerIsTakenOverWithoutAnotherExecution() {
        long before = counter("workerCrashesRecovered");
        invariantsAllOk(run("kill-worker"));
        assertThat(counter("workerCrashesRecovered")).isEqualTo(before + 1);
    }

    @Test
    void ac_9_1_badStatementOpensDiscrepanciesInsteadOfChangingTheBooks() {
        long before = counter("discrepanciesOpened");
        long entries = counter("entries");
        invariantsAllOk(run("bad-statement"));
        assertThat(counter("discrepanciesOpened")).isGreaterThanOrEqualTo(before + 2);
        assertThat(counter("entries")).isGreaterThanOrEqualTo(entries);
    }

    @Test
    void ac_9_1_aSecondClickWhileOneRuns_isRefusedWithConflict() {
        try (var feed = new SseCollector(stack.appPort, null)) {
            CompletableFuture<DemoStack.Answer> first =
                    CompletableFuture.supplyAsync(() -> stack.post("/api/demo/actions/drop-refund-response"));
            // The first step event proves the action holds the site-wide lock.
            feed.await("step", data -> data.contains("drop-refund-response"), Duration.ofSeconds(60));
            DemoStack.Answer second = stack.post("/api/demo/actions/pay");
            assertThat(second.status()).isEqualTo(409);
            assertThat(second.json().path("error").asString()).isEqualTo("ACTION_IN_PROGRESS");
            assertThat(first.join().status()).isEqualTo(200);
        }
    }

    @Test
    void ac_9_1_anUnknownActionIsNotFound() {
        assertThat(stack.post("/api/demo/actions/drop-all-tables").status()).isEqualTo(404);
    }

    @Test
    void ac_9_6_noPersonalData_onlyDailyAggregatesAndNoCookies() {
        DemoStack.Answer action = stack.post("/api/demo/actions/pay");
        DemoStack.Answer visit = stack.post("/api/demo/visit");
        DemoStack.Answer summary = stack.get("/api/demo/summary");
        assertThat(visit.status()).isEqualTo(204);
        for (DemoStack.Answer answer : List.of(action, visit, summary)) {
            assertThat(answer.headers().keySet().stream().map(String::toLowerCase))
                    .doesNotContain("set-cookie");
        }
        // The only places the demo writes: columns of dates, action names and counts, nothing else.
        var columns = stack.superuser().sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'demo' AND table_name IN ('action_count', 'page_view')
                        ORDER BY 1
                        """).query(String.class).list();
        assertThat(columns)
                .containsExactly(
                        "action_count.action",
                        "action_count.count",
                        "action_count.day",
                        "action_count.recorded_at",
                        "page_view.count",
                        "page_view.day",
                        "page_view.recorded_at");
        assertThat(stack.superuser()
                        .sql("SELECT count(*) FROM demo.page_view")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(summary.json().path("pageViews").path("today").asLong()).isEqualTo(1);
    }

    @Test
    void ac_9_1_aReconnectingStreamResumesFromLastEventId() {
        try (var first = new SseCollector(stack.appPort, null)) {
            run("pay");
            SseCollector.Event seen = first.await("counters", data -> true, Duration.ofSeconds(5));
            run("pay");
            try (var second = new SseCollector(stack.appPort, seen.id())) {
                SseCollector.Event replayed = second.await("invariants", data -> true, Duration.ofSeconds(5));
                assertThat(Long.parseLong(replayed.id())).isGreaterThan(Long.parseLong(seen.id()));
            }
        }
    }

    @Test
    void ac_9_1_seedIsIdempotent() {
        DemoStack.Answer first = stack.post("/api/demo/seed");
        assertThat(first.status()).isEqualTo(200);
        long payments = counter("payments");
        DemoStack.Answer second = stack.post("/api/demo/seed");
        assertThat(second.json().path("seeded").asBoolean()).isFalse();
        assertThat(counter("payments")).isEqualTo(payments);
        invariantsAllOk(second.json());
    }

    private JsonNode run(String action) {
        DemoStack.Answer answer = stack.post("/api/demo/actions/" + action);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return answer.json();
    }

    private long counter(String name) {
        return stack.get("/api/demo/summary").json().path("counters").path(name).asLong();
    }

    private static void invariantsAllOk(JsonNode result) {
        JsonNode verdict = result.path("invariants");
        assertThat(verdict.path("ok").asBoolean()).as(verdict.toString()).isTrue();
        assertThat(verdict.path("checks").size()).isEqualTo(12);
        assertThat(verdict.path("failed").size()).isZero();
        assertThat(result.path("steps").size()).isPositive();
    }
}
