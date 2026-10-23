package dev.ledgerlab.settlement;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(
        prefix = "ledgerlab.bank",
        name = "scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
final class BankWaitingSchedule {
    private final BankStatements statements;

    BankWaitingSchedule(BankStatements statements) {
        this.statements = statements;
    }

    @Scheduled(fixedDelayString = "${ledgerlab.bank.poll-interval:1m}")
    void scan() {
        statements.expireWaiting();
    }
}
