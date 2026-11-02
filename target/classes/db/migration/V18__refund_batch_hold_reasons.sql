-- Preserve the existing permissions and all previous hold reasons.
ALTER TABLE core.settlement_batch DROP CONSTRAINT settlement_batch_hold_reason_check;
ALTER TABLE core.settlement_batch ADD CONSTRAINT settlement_batch_hold_reason_check
    CHECK (hold_reason IN ('UNKNOWN_PAYMENT', 'AMOUNT_MISMATCH', 'UNSUPPORTED_LINE',
        'UNSUPPORTED_PAYOUT', 'FEE_EXCEEDS_GROSS', 'LINE_IN_OTHER_BATCH',
        'TOTAL_MISMATCH', 'LINES_NOT_READY', 'UNKNOWN_REFUND', 'REFUND_STATE_CONFLICT'));
-- batch_transition.hold_reason has no enum CHECK; its HELD/non-null CHECK is unchanged.
-- migrator owns both tables, app retains its existing column grants,
-- and readonly continues to read only the existing views.
