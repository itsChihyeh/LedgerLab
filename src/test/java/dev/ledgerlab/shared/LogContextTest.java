package dev.ledgerlab.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {
    @AfterEach
    void clean() {
        MDC.clear();
    }

    @Test
    void ac_12_2_valueIsVisibleInsideAndGoneAfter() {
        try (LogContext ignored = LogContext.with(LogContext.EVENT_ID, "evt_1")) {
            assertThat(MDC.get("eventId")).isEqualTo("evt_1");
        }
        assertThat(MDC.get("eventId")).isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void ac_12_2_nestedContextRestoresTheOuterValueNotNull() {
        try (LogContext outer = LogContext.with(LogContext.BATCH_ID, "po_outer")) {
            try (LogContext inner =
                    LogContext.with(LogContext.BATCH_ID, "po_inner").and(LogContext.ISSUE_ID, "iss_1")) {
                assertThat(MDC.get("batchId")).isEqualTo("po_inner");
                assertThat(MDC.get("issueId")).isEqualTo("iss_1");
            }
            assertThat(MDC.get("batchId")).isEqualTo("po_outer");
            assertThat(MDC.get("issueId")).isNull();
        }
        assertThat(MDC.get("batchId")).isNull();
    }

    @Test
    void ac_12_2_nextJobOnTheSameThreadDoesNotInheritThePreviousId() {
        try (LogContext first = LogContext.with(LogContext.COMMAND_ID, "cmd_1").and(LogContext.COMMAND_KIND, "A")) {
            assertThat(MDC.get("commandId")).isEqualTo("cmd_1");
        }
        try (LogContext second = LogContext.with(LogContext.EVENT_ID, "evt_2")) {
            assertThat(MDC.get("commandId")).isNull();
            assertThat(MDC.get("commandKind")).isNull();
            assertThat(MDC.get("eventId")).isEqualTo("evt_2");
        }
    }

    @Test
    void ac_12_2_contextIsRestoredWhenTheBlockThrows() {
        MDC.put("runId", "rr_before");
        try {
            try (LogContext ignored = LogContext.with(LogContext.RUN_ID, "rr_during")) {
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException expected) {
            // the failure itself is not under test
        }
        assertThat(MDC.get("runId")).isEqualTo("rr_before");
    }

    @Test
    void ac_12_2_nullValueLeavesTheKeyAlone() {
        MDC.put("batchId", "po_keep");
        try (LogContext ignored = LogContext.with(LogContext.BATCH_ID, null)) {
            assertThat(MDC.get("batchId")).isEqualTo("po_keep");
        }
        assertThat(MDC.get("batchId")).isEqualTo("po_keep");
    }
}
