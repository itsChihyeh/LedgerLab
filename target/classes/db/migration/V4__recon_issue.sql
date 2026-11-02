-- P1b-3: persist reconciliation and ingestion issues with least-privilege disposition updates.
-- Runs as migrator; recon schema and schema USAGE grants already exist from V1.
CREATE TABLE recon.issue (
    id                       text PRIMARY KEY CONSTRAINT issue_id_format
        CHECK (id ~ '^iss_[0-9A-HJKMNP-TV-Z]{26}$'),
    category                 text NOT NULL CONSTRAINT issue_category CHECK (category IN (
        'MISSING_IN_LEDGER', 'MISSING_IN_STATEMENT', 'AMOUNT_MISMATCH', 'FEE_MISMATCH',
        'DUPLICATE_STATEMENT_LINE', 'BATCH_TOTAL_MISMATCH', 'REFUND_NOT_IN_STATEMENT',
        'DEPOSIT_MISSING', 'DEPOSIT_AMOUNT_MISMATCH', 'UNMATCHED_BANK_LINE', 'UNAPPLIED_CASH_OPEN',
        'BANK_BALANCE_MISMATCH', 'BATCH_VERSION_CONFLICT', 'STATEMENT_VERSION_CONFLICT',
        'TIMING_DIFFERENCE', 'PAYOUT_STATE_CONFLICT', 'BATCH_STATE_CONFLICT', 'REFUND_STATE_CONFLICT',
        'UNSUPPORTED_PAYOUT', 'EVENT_CONTENT_CONFLICT', 'EVENT_BUSINESS_CONFLICT',
        'UNSUPPORTED_EVENT', 'LIVEMODE_EVENT', 'PAYMENT_NOT_SETTLED', 'DEPOSIT_NOT_RECEIVED',
        'REFUND_AWAITING_FUNDS_AGED', 'REFUND_NOT_EXECUTED', 'PAYOUT_UNRESOLVED', 'EVENT_PARKED_AGED'
    )),
    subject_type             text NOT NULL CONSTRAINT issue_subject_type_nonblank CHECK (subject_type ~ '[^[:space:]]'),
    subject_id               text NOT NULL CONSTRAINT issue_subject_id_nonblank CHECK (subject_id ~ '[^[:space:]]'),
    detected_by              text NOT NULL CONSTRAINT issue_detected_by CHECK (detected_by IN ('RUN', 'INGESTION', 'AGING')),
    -- Add both run FKs when recon.run is created in P2.
    first_run_id             text,
    last_run_id              text,
    status                   text NOT NULL CONSTRAINT issue_status CHECK (status IN ('OPEN', 'EXPLAINED', 'RESOLVED', 'ACCEPTED')),
    expected                 jsonb,
    actual                   jsonb,
    evidence_refs            jsonb NOT NULL DEFAULT '[]' CONSTRAINT issue_evidence_refs_array
        CHECK (jsonb_typeof(evidence_refs) = 'array'),
    explanation              text,
    -- Add the FK when ledger.correction is created in P4.
    resolution_correction_id text,
    recorded_at              timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    CONSTRAINT issue_detected_by_runs CHECK (
        (detected_by <> 'RUN' OR first_run_id IS NOT NULL)
        AND (detected_by <> 'INGESTION' OR (first_run_id IS NULL AND last_run_id IS NULL))
    ),
    CONSTRAINT issue_status_explanation CHECK (
        status = 'OPEN'
        OR (status IN ('EXPLAINED', 'ACCEPTED') AND explanation IS NOT NULL)
        OR (status = 'RESOLVED' AND (explanation IS NOT NULL OR resolution_correction_id IS NOT NULL))
    ),
    -- Reappearance after closure must be redesigned under P2 prerequisite 3;
    -- per-run findings and current disposition will be separated then.
    CONSTRAINT issue_identity UNIQUE (category, subject_type, subject_id)
);

CREATE VIEW recon.v_issue AS
    SELECT id, category, subject_type, subject_id, detected_by, first_run_id, last_run_id,
           status, expected, actual, evidence_refs, explanation, resolution_correction_id,
           recorded_at, updated_at
    FROM recon.issue;

GRANT SELECT, INSERT, UPDATE (status, last_run_id, explanation, resolution_correction_id, updated_at)
    ON recon.issue TO app;
GRANT SELECT ON recon.v_issue TO readonly;

-- Names verified against pg_constraint on PostgreSQL 18 with the unchanged V3 migration.
ALTER TABLE io.inbox_event RENAME CONSTRAINT inbox_event_check1 TO inbox_event_status_result;
ALTER TABLE io.inbox_event RENAME CONSTRAINT inbox_event_check2 TO inbox_event_status_parked_on;
