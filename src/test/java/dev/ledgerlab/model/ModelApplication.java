package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.LedgerLabApplication;
import dev.ledgerlab.ingestion.internal.InboxWorker;
import dev.ledgerlab.ingestion.internal.ModelWorkers;
import dev.ledgerlab.ingestion.internal.WorkerHooks;
import dev.ledgerlab.ledger.LedgerAudit;
import dev.ledgerlab.shared.IdGenerator;
import dev.ledgerlab.testsupport.MutableClock;
import dev.ledgerlab.testsupport.StripeSignatures;
import dev.ledgerlab.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One server and one real database for the entire property, including shrinking attempts. */
final class ModelApplication implements AutoCloseable {
    static final long ID_SEED = 2026102202L;
    final ScenarioLog log = new ScenarioLog(ID_SEED);
    private final TestDatabase.Handle db = TestDatabase.freshBlank();
    private final HttpClient http = HttpClient.newHttpClient();
    final ProcessorReplay processor = new ProcessorReplay();
    private final ConfigurableApplicationContext context;
    final JdbcClient jdbc;
    final MutableClock clock;
    final LedgerAudit audit;
    private final URI endpoint;

    ModelApplication() {
        context = new SpringApplicationBuilder(LedgerLabApplication.class, Controls.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + db.url(),
                        "--LEDGERLAB_APP_PASSWORD=" + TestDatabase.APP_PASSWORD,
                        "--LEDGERLAB_MIGRATOR_PASSWORD=" + TestDatabase.MIGRATOR_PASSWORD,
                        "--LEDGERLAB_READONLY_PASSWORD=" + TestDatabase.READONLY_PASSWORD,
                        "--ledgerlab.stripe.webhook-secret=" + StripeSignatures.SECRET,
                        "--ledgerlab.stripe.api-key=sk_test_" + "model_synthetic",
                        "--ledgerlab.stripe.api-base-url=" + processor.baseUrl(),
                        "--ledgerlab.inbox.scheduling-enabled=false",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
        jdbc = context.getBean(JdbcClient.class);
        clock = context.getBean(MutableClock.class);
        audit = context.getBean(LedgerAudit.class);
        endpoint = URI.create(
                "http://localhost:" + context.getEnvironment().getProperty("local.server.port") + "/webhooks/stripe");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Controls {
        @Bean
        @Primary
        MutableClock modelClock() {
            return new MutableClock(Instant.parse("2026-10-21T14:00:00Z"));
        }

        @Bean
        @Primary
        IdGenerator modelIds(MutableClock clock) {
            return IdGenerator.seeded(clock, ID_SEED);
        }
    }

    void advance(int seconds) {
        Instant next = clock.instant().plusSeconds(seconds);
        log.add(new ScenarioLog.ClockAdvance(clock.instant(), next));
        clock.set(next);
    }

    void deliver(PaymentModel.Input input) {
        deliverRaw(payload(input));
    }

    void deliverRaw(byte[] body) {
        String signature = StripeSignatures.sign(body, clock.instant().getEpochSecond());
        log.add(new ScenarioLog.Webhook(clock.instant(), body, signature));
        deliver(body, signature);
    }

    void processor(String payout, ProcessorReplay.Pages pages) {
        log.add(new ScenarioLog.ProcessorRest(clock.instant(), payout, pages));
        processor.install(payout, pages);
    }

    void bank(byte[] raw) {
        log.add(new ScenarioLog.BankFile(clock.instant(), raw));
        assertThat(context.getBean(dev.ledgerlab.settlement.BankStatements.class)
                        .importBankStatement("model.csv", raw)
                        .status())
                .isEqualTo("ACCEPTED");
    }

    void expireWaiting() {
        log.add(new ScenarioLog.BankScan(clock.instant()));
        context.getBean(dev.ledgerlab.settlement.BankStatements.class).expireWaiting();
    }

    void deliver(byte[] body, String signature) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static byte[] payload(PaymentModel.Input input) {
        return """
                {"id":"%s","type":"%s","api_version":"2026-09-30.endive",
                "livemode":%s,"created":1792591200,"data":{"object":{
                "id":"%s","payment_intent":"pi_model","amount":%s,"amount_captured":%s,
                "currency":"%s","captured":%s,"status":"succeeded","livemode":%s,"created":1792591100}}}
                """.formatted(
                        input.id(),
                        input.type(),
                        input.envelopeLive(),
                        input.charge(),
                        input.amount(),
                        input.capturedAmount(),
                        input.currency(),
                        input.captured(),
                        input.chargeLive())
                .getBytes(StandardCharsets.UTF_8);
    }

    InboxWorker worker(String owner, WorkerHooks hooks) {
        return ModelWorkers.create(context, owner, hooks);
    }

    void drain(String owner) {
        log.add(new ScenarioLog.WorkerRun(clock.instant(), owner, ScenarioLog.CrashPoint.NONE));
        InboxWorker worker = worker(owner, new WorkerHooks() {});
        // Bounded so a regression cannot hang the property or CI indefinitely.
        for (int i = 0; i < 100; i++) {
            if (worker.processAvailable() == 0) return;
        }
        throw new AssertionError("Worker did not drain after 100 batches");
    }

    void assertNoPending() {
        assertThat(jdbc.sql(
                                "SELECT event_id, status, next_attempt_at, lease_until FROM io.inbox_event WHERE status IN ('RECEIVED', 'PARKED')")
                        .query()
                        .listOfRows())
                .as("no inbox work remains, including leased or delayed events")
                .isEmpty();
    }

    Map<Integer, Long> balances() {
        Map<Integer, Long> result = new HashMap<>();
        jdbc.sql("SELECT code, balance FROM ledger.account")
                .query((rs, row) -> Map.entry(rs.getInt(1), rs.getLong(2)))
                .list()
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    @Override
    public void close() {
        http.close();
        context.close();
        processor.close();
        db.drop();
    }
}
