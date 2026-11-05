package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerlab.refunds.Refunds;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AC-9.2: the refund-lock fault exists only when a test run asks for it. Default and demo contexts, which are what
 * production and the public site run, must contain neither the switch nor any refund guard other than the locked one.
 */
class RefundLockSwitchTest {
    private static final String SWITCH = "--ledgerlab.test.break-refund-lock=true";

    private static boolean has(ConfigurableApplicationContext context, String simpleName) {
        return Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(Objects::nonNull)
                .anyMatch(type -> type.getSimpleName().equals(simpleName));
    }

    private static String guardInUse(ConfigurableApplicationContext context) {
        Object refunds = context.getBean(Refunds.class);
        Object guard = ReflectionTestUtils.getField(refunds, "limits");
        return Objects.requireNonNull(guard).getClass().getSimpleName();
    }

    @Test
    void ac_9_2_defaultContextHasNoBrokenRefundLock() {
        try (var stack = new DemoStack(false, false)) {
            assertThat(has(stack.context, "BrokenRefundLimitGuard")).isFalse();
            assertThat(has(stack.context, "BrokenRefundApiPostProcessor")).isFalse();
            assertThat(guardInUse(stack.context)).isEqualTo("LockedRefundLimitGuard");
        }
    }

    @Test
    void ac_9_2_demoContextHasNoBrokenRefundLock() {
        try (var stack = new DemoStack(true, false)) {
            assertThat(has(stack.context, "BrokenRefundLimitGuard")).isFalse();
            assertThat(has(stack.context, "BrokenRefundApiPostProcessor")).isFalse();
            assertThat(guardInUse(stack.context)).isEqualTo("LockedRefundLimitGuard");
        }
    }

    @Test
    void ac_9_2_explicitTestSwitchReplacesTheGuard() {
        try (var stack = new DemoStack(true, false, SWITCH)) {
            assertThat(has(stack.context, "BrokenRefundLimitGuard")).isTrue();
            assertThat(has(stack.context, "BrokenRefundApiPostProcessor")).isTrue();
            assertThat(guardInUse(stack.context)).isEqualTo("BrokenRefundLimitGuard");
        }
    }
}
