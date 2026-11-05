package dev.ledgerlab.refunds.internal;

import dev.ledgerlab.ledger.Ledger;
import dev.ledgerlab.payments.PaymentRefunds;
import dev.ledgerlab.refunds.Refunds;
import dev.ledgerlab.testsupport.DelegatingLedger;
import java.time.Clock;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The other half of the AC-9.2 fault ({@link BrokenRefundLimitGuard}): the refund API takes the funds lock before
 * anything else, which on its own already serializes every refund. With the switch on, the API is rebuilt around
 * a ledger view whose {@code lockFunds} does nothing, so a refund request starts without holding the lock; the
 * ledger itself (posting, balances, the real lock) is unchanged and is still taken later by the guard.
 */
@Component
@ConditionalOnProperty(name = "ledgerlab.test.break-refund-lock", havingValue = "true")
final class BrokenRefundApiPostProcessor implements BeanPostProcessor {
    private final BeanFactory beans;

    BrokenRefundApiPostProcessor(BeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof RefundApi)) return bean;
        Ledger real = beans.getBean(Ledger.class);
        Ledger unlocked = new DelegatingLedger(real) {
            @Override
            public void lockFunds() {
                // Skipped on purpose: this is the fault.
            }

            @Override
            public void assertFundsLocked() {
                // The lookups the API makes before the guard run without the lock.
            }
        };
        JdbcClient jdbc = beans.getBean(JdbcClient.class);
        return new RefundApi(
                jdbc,
                unlocked,
                new PaymentRefunds(jdbc, unlocked),
                beans.getBean(Refunds.class),
                beans.getBean(Clock.class),
                beans.getBean(ObjectMapper.class),
                beans.getBean(PlatformTransactionManager.class),
                beans.getBean(RefundApiProperties.class),
                () -> {});
    }
}
