ALTER TABLE io.processor_fetch DROP CONSTRAINT processor_fetch_purpose_check;
ALTER TABLE io.processor_fetch ADD CONSTRAINT processor_fetch_purpose_check
    CHECK (purpose IN ('BATCH_LINES', 'PAYOUT', 'RECON_SNAPSHOT', 'REFUND_COMMAND', 'REFUND_QUERY'));
