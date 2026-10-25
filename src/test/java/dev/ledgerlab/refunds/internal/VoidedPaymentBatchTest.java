package dev.ledgerlab.refunds.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VoidedPaymentBatchTest {
    @Test
    void ac_6_3_voidedPaymentHoldsBatchAsStateConflict() {
        try (var f = new RefundFixture()) {
            String payment = f.s1();
            // Isolate the existing batch branch before the correction service is introduced.
            f.jdbc.sql("UPDATE core.payment SET status='VOIDED' WHERE id=?")
                    .param(payment)
                    .update();
            f.batch("po_voided", "ch_p1", 10000, 320);
            assertThat(f.jdbc.sql("SELECT hold_reason FROM core.settlement_batch WHERE batch_id='po_voided'")
                            .query(String.class)
                            .single())
                    .isEqualTo("PAYMENT_STATE_CONFLICT");
            assertThat(f.jdbc.sql("SELECT category FROM recon.issue WHERE subject_id='po_voided'")
                            .query(String.class)
                            .single())
                    .isEqualTo("BATCH_STATE_CONFLICT");
            assertThat(f.count("core.settlement_batch_line")).isZero();
            assertThat(f.count("ledger.journal_entry")).isEqualTo(1);
        }
    }
}
