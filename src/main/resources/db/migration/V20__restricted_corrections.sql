-- ADR-0011 (2026-10-25): append-only correction audit and commit-time consistency.
-- No legacy correction can have a valid parent before this table exists. Stop for investigation.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM ledger.journal_entry WHERE correction_id IS NOT NULL)
        OR EXISTS (SELECT 1 FROM recon.issue WHERE resolution_correction_id IS NOT NULL) THEN
        RAISE EXCEPTION 'legacy correction references exist; investigate before migrating' USING ERRCODE = '23503';
    END IF;
END;
$$;

CREATE TABLE ledger.correction (
    id text PRIMARY KEY CHECK (id ~ '^cor_[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    correction_type text NOT NULL CHECK (correction_type IN
        ('VOID_CAPTURE', 'RESTATE_CAPTURE_AMOUNT', 'APPLY_UNAPPLIED_CASH', 'RESTORE_FAILED_PAYOUT')),
    reason_code text NOT NULL CHECK (reason_code IN
        ('CAPTURE_RECORDED_IN_ERROR', 'CAPTURE_AMOUNT_ERROR', 'SPLIT_DEPOSIT', 'PAYOUT_FAILURE_DISPROVED', 'OTHER')),
    reason_note text NOT NULL CHECK (reason_note ~ '[^[:space:]]' AND char_length(reason_note) <= 2000),
    evidence_refs jsonb NOT NULL CHECK (jsonb_typeof(evidence_refs) = 'array' AND jsonb_array_length(evidence_refs) > 0),
    params jsonb NOT NULL,
    subject_type text NOT NULL CHECK (subject_type IN ('payment', 'refund', 'payout', 'batch', 'bank_txn')),
    subject_id text NOT NULL CHECK (subject_id ~ '[^[:space:]]'),
    requested_by text NOT NULL CHECK (requested_by ~ '[^[:space:]]'),
    recorded_at timestamptz NOT NULL
);
CREATE TABLE ledger.correction_target (
    correction_id text NOT NULL REFERENCES ledger.correction(id),
    entry_id text NOT NULL UNIQUE REFERENCES ledger.journal_entry(id),
    role text NOT NULL CHECK (role IN ('REVERSED', 'APPLIED')),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (correction_id, entry_id)
);
ALTER TABLE ledger.journal_entry ADD CONSTRAINT journal_entry_correction_fk
    FOREIGN KEY (correction_id) REFERENCES ledger.correction(id);
ALTER TABLE recon.issue ADD CONSTRAINT issue_resolution_correction_fk
    FOREIGN KEY (resolution_correction_id) REFERENCES ledger.correction(id);
CREATE INDEX journal_entry_correction_idx ON ledger.journal_entry(correction_id)
    WHERE correction_id IS NOT NULL;
CREATE UNIQUE INDEX journal_entry_one_correction ON ledger.journal_entry(correction_id)
    WHERE entry_type = 'CORRECTION';

CREATE TRIGGER correction_no_rewrite BEFORE UPDATE OR DELETE ON ledger.correction
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_ledger_rewrite();
CREATE TRIGGER correction_no_truncate BEFORE TRUNCATE ON ledger.correction
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_ledger_rewrite();
CREATE TRIGGER correction_target_no_rewrite BEFORE UPDATE OR DELETE ON ledger.correction_target
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_ledger_rewrite();
CREATE TRIGGER correction_target_no_truncate BEFORE TRUNCATE ON ledger.correction_target
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_ledger_rewrite();

CREATE FUNCTION ledger.check_correction_consistent() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    correction_ref text;
    reversal record;
BEGIN
    IF TG_TABLE_NAME = 'correction' THEN
        correction_ref := NEW.id;
    ELSIF TG_TABLE_NAME = 'posting' THEN
        SELECT correction_id INTO correction_ref FROM ledger.journal_entry WHERE id = NEW.entry_id;
    ELSE
        correction_ref := NEW.correction_id;
    END IF;
    IF correction_ref IS NULL THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ledger.journal_entry WHERE correction_id = correction_ref) THEN
        RAISE EXCEPTION 'correction % has no entries', correction_ref USING ERRCODE = 'LL005';
    END IF;
    IF EXISTS (
        SELECT 1 FROM ledger.correction_target t JOIN ledger.journal_entry e ON e.id = t.entry_id
        WHERE t.correction_id = correction_ref AND
            ((t.role = 'APPLIED' AND e.entry_type <> 'UNAPPLIED_CASH_RECEIVED') OR
             (t.role = 'REVERSED' AND NOT EXISTS (
                 SELECT 1 FROM ledger.journal_entry r WHERE r.entry_type = 'REVERSAL'
                     AND r.reverses_entry_id = t.entry_id AND r.correction_id = t.correction_id)))
    ) THEN
        RAISE EXCEPTION 'correction % has an invalid target', correction_ref USING ERRCODE = 'LL005';
    END IF;
    FOR reversal IN SELECT id, reverses_entry_id FROM ledger.journal_entry
        WHERE correction_id = correction_ref AND entry_type = 'REVERSAL'
    LOOP
        IF NOT EXISTS (SELECT 1 FROM ledger.correction_target
            WHERE correction_id = correction_ref AND entry_id = reversal.reverses_entry_id AND role = 'REVERSED')
            OR EXISTS (SELECT 1 FROM ledger.journal_entry
                WHERE id = reversal.reverses_entry_id AND entry_type = 'REVERSAL') THEN
            RAISE EXCEPTION 'reversal % has an invalid original or target', reversal.id USING ERRCODE = 'LL005';
        END IF;
        -- EXCEPT ALL compares multisets: matching totals alone would miss changed accounts or split lines.
        IF EXISTS (
            (SELECT account_code, amount, side::text FROM ledger.posting WHERE entry_id = reversal.id
             EXCEPT ALL
             SELECT account_code, amount, CASE side WHEN 'D' THEN 'C' ELSE 'D' END
                 FROM ledger.posting WHERE entry_id = reversal.reverses_entry_id)
            UNION ALL
            (SELECT account_code, amount, CASE side WHEN 'D' THEN 'C' ELSE 'D' END
                 FROM ledger.posting WHERE entry_id = reversal.reverses_entry_id
             EXCEPT ALL
             SELECT account_code, amount, side::text FROM ledger.posting WHERE entry_id = reversal.id)
        ) THEN
            RAISE EXCEPTION 'reversal % does not mirror its original', reversal.id USING ERRCODE = 'LL005';
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER correction_consistent AFTER INSERT ON ledger.correction
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_correction_consistent();
CREATE CONSTRAINT TRIGGER correction_target_consistent AFTER INSERT ON ledger.correction_target
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_correction_consistent();
CREATE CONSTRAINT TRIGGER correction_entry_consistent AFTER INSERT ON ledger.journal_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_correction_consistent();
CREATE CONSTRAINT TRIGGER correction_posting_consistent AFTER INSERT ON ledger.posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger.check_correction_consistent();
REVOKE ALL ON FUNCTION ledger.check_correction_consistent() FROM PUBLIC;

CREATE VIEW ledger.v_correction AS
    SELECT id, correction_type, reason_code, reason_note, evidence_refs, params,
        subject_type, subject_id, requested_by, recorded_at FROM ledger.correction;
CREATE VIEW ledger.v_correction_target AS
    SELECT correction_id, entry_id, role, recorded_at FROM ledger.correction_target;
GRANT INSERT, SELECT ON ledger.correction, ledger.correction_target TO app;
GRANT SELECT ON ledger.v_correction, ledger.v_correction_target TO readonly;
GRANT UPDATE (amount, expected_fee) ON core.payment TO app;

ALTER TABLE recon.bank_line DROP CONSTRAINT bank_line_match_status_check;
ALTER TABLE recon.bank_line ADD CONSTRAINT bank_line_match_status_check CHECK (match_status IN
    ('WAITING', 'MATCHED', 'UNAPPLIED', 'UNMATCHED_NO_POSTING', 'IGNORED_PENDING', 'APPLIED', 'RETURNED', 'RETURN_POSTED'));
ALTER TABLE recon.bank_line DROP CONSTRAINT bank_line_match;
ALTER TABLE recon.bank_line ADD CONSTRAINT bank_line_match CHECK (
    (match_status IN ('MATCHED', 'APPLIED')) = (matched_batch_id IS NOT NULL));
ALTER TABLE recon.bank_line DROP CONSTRAINT bank_line_direction;
ALTER TABLE recon.bank_line ADD CONSTRAINT bank_line_direction CHECK (
    (match_status NOT IN ('WAITING', 'MATCHED', 'UNAPPLIED', 'APPLIED', 'RETURNED') OR direction = 'CREDIT')
    AND (match_status <> 'RETURN_POSTED' OR direction = 'DEBIT'));

ALTER TABLE core.settlement_batch DROP CONSTRAINT settlement_batch_hold_reason_check;
ALTER TABLE core.settlement_batch ADD CONSTRAINT settlement_batch_hold_reason_check
    CHECK (hold_reason IN ('UNKNOWN_PAYMENT', 'AMOUNT_MISMATCH', 'UNSUPPORTED_LINE',
        'UNSUPPORTED_PAYOUT', 'FEE_EXCEEDS_GROSS', 'LINE_IN_OTHER_BATCH',
        'TOTAL_MISMATCH', 'LINES_NOT_READY', 'UNKNOWN_REFUND', 'REFUND_STATE_CONFLICT', 'PAYMENT_STATE_CONFLICT'));
-- batch_transition.hold_reason has no enum CHECK. All other grants and constraints stay intact.
