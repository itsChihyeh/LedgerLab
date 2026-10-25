package dev.ledgerlab.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerlab.ingestion.internal.WorkerHooks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Ordered external evidence, independent of the reference model and database contents. */
final class ScenarioLog {
    final long idSeed;
    private final List<Input> inputs = new ArrayList<>();

    ScenarioLog(long idSeed) {
        this.idSeed = idSeed;
    }

    sealed interface Input
            permits Webhook,
                    ClockAdvance,
                    WorkerRun,
                    ProcessorRest,
                    BankFile,
                    BankScan,
                    ApiCommand,
                    ApiResult,
                    OutboundRun,
                    ApiCleanup {
        Instant at();
    }

    record Webhook(Instant at, byte[] body, String signature) implements Input {
        Webhook {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "Webhook[at=" + at + ", bodyBase64=" + Base64.getEncoder().encodeToString(body) + ", signature="
                    + signature + "]";
        }
    }

    record ClockAdvance(Instant at, Instant to) implements Input {}

    record ProcessorRest(Instant at, String payout, ProcessorReplay.Pages pages) implements Input {}

    record BankFile(Instant at, byte[] raw) implements Input {
        BankFile {
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        @Override
        public String toString() {
            return "BankFile[at=" + at + ", rawBase64=" + Base64.getEncoder().encodeToString(raw) + "]";
        }
    }

    record BankScan(Instant at) implements Input {}

    record ApiCommand(Instant at, String path, String key, String body) implements Input {}

    record ApiCleanup(Instant at) implements Input {}

    record ApiResult(Instant at, ModelApplication.ApiResponse response) implements Input {}

    record OutboundRun(Instant at, List<RefundReplay.Reply> replies) implements Input {
        OutboundRun {
            replies = List.copyOf(replies);
        }
    }

    enum CrashPoint {
        NONE,
        AFTER_CLAIM,
        AFTER_BUSINESS
    }

    record WorkerRun(Instant at, String owner, CrashPoint crash) implements Input {}

    void add(Input input) {
        inputs.add(input);
    }

    void clear() {
        inputs.clear();
    }

    List<Input> inputs() {
        return List.copyOf(inputs);
    }

    void replay(ModelApplication target) {
        assertThat(idSeed).isEqualTo(ModelApplication.ID_SEED);
        ModelApplication.ApiResponse lastResponse = null;
        for (Input input : inputs()) {
            target.clock.set(input.at());
            switch (input) {
                case ApiCleanup ignored -> target.expireApiKeys();
                case ApiCommand api -> lastResponse = target.api(api.path(), api.key(), api.body());
                case ApiResult result -> assertThat(lastResponse).isEqualTo(result.response());
                case OutboundRun run -> {
                    target.refunds.replies.addAll(run.replies());
                    target.outbound();
                    assertThat(target.refunds.replies).isEmpty();
                }
                case Webhook webhook -> target.deliver(webhook.body(), webhook.signature());
                case ClockAdvance advance -> target.clock.set(advance.to());
                case ProcessorRest rest -> target.processor.install(rest.payout(), rest.pages());
                case BankFile file -> target.bank(file.raw());
                case BankScan ignored -> target.expireWaiting();
                case WorkerRun run -> {
                    if (run.crash() == CrashPoint.NONE) {
                        target.drain(run.owner());
                    } else {
                        var worker = target.worker(run.owner(), new WorkerHooks() {
                            @Override
                            public void afterClaim(String id) {
                                if (run.crash() == CrashPoint.AFTER_CLAIM) throw new ReplayDeath();
                            }

                            @Override
                            public void afterBusiness(String id) {
                                if (run.crash() == CrashPoint.AFTER_BUSINESS) throw new ReplayDeath();
                            }
                        });
                        assertThatThrownBy(worker::processAvailable).isInstanceOf(ReplayDeath.class);
                    }
                }
            }
        }
        target.drain("replay-final-worker");
        target.assertNoPending();
    }

    @Override
    public String toString() {
        return "IdGenerator seed=" + idSeed + "\n"
                + String.join("\n", inputs.stream().map(Object::toString).toList());
    }

    private static final class ReplayDeath extends Error {}
}
