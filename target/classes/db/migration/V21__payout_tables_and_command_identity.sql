-- P5 T02: durable payout identity and evidence, with least-privilege role grants.
-- Runs as migrator; bank_request_id is the payout id itself (ADR-0006).
CREATE TABLE core.payout (
    id                  text PRIMARY KEY CHECK (id ~ '^mpo_[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    merchant_payout_ref text NOT NULL UNIQUE CHECK (merchant_payout_ref ~ '^[!-~]{1,64}$'),
    amount              bigint NOT NULL CHECK (amount BETWEEN 1 AND 99999999),
    currency            text NOT NULL CHECK (currency = 'CAD'),
    status              text NOT NULL CHECK (status IN
        ('RESERVED', 'SUBMITTED', 'UNKNOWN', 'ACCEPTED', 'PAID', 'FAILED', 'RETURNED')),
    initial_result      jsonb NOT NULL,
    request_fingerprint text NOT NULL,
    bank_ack_ref        text,
    bank_execution_date date,
    bank_txn_id         text,
    return_bank_txn_id  text,
    failure_reason      text,
    return_reason       text,
    reserved_at         timestamptz NOT NULL,
    submitted_at        timestamptz,
    unknown_at          timestamptz,
    accepted_at         timestamptz,
    paid_at             timestamptz,
    failed_at           timestamptz,
    returned_at         timestamptz,
    updated_at          timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL,
    CONSTRAINT payout_paid_evidence CHECK (status NOT IN ('PAID', 'RETURNED') OR bank_txn_id IS NOT NULL),
    CONSTRAINT payout_returned_evidence CHECK (status <> 'RETURNED' OR return_bank_txn_id IS NOT NULL),
    CONSTRAINT payout_failed_evidence CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL)
);

CREATE TABLE core.payout_status_history (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id     text NOT NULL REFERENCES core.payout(id),
    from_status   text CHECK (from_status IN
        ('RESERVED', 'SUBMITTED', 'UNKNOWN', 'ACCEPTED', 'PAID', 'FAILED', 'RETURNED')),
    to_status     text NOT NULL CHECK (to_status IN
        ('RESERVED', 'SUBMITTED', 'UNKNOWN', 'ACCEPTED', 'PAID', 'FAILED', 'RETURNED')),
    evidence_type text NOT NULL CHECK (evidence_type IN ('api', 'bank_api', 'bank_txn', 'correction', 'operator')),
    evidence_id   text NOT NULL,
    occurred_at   timestamptz NOT NULL,
    recorded_at   timestamptz NOT NULL
);

CREATE FUNCTION core.reject_payout_history_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'payout status history is append-only' USING ERRCODE = 'LL004';
END;
$$;
REVOKE ALL ON FUNCTION core.reject_payout_history_rewrite() FROM PUBLIC;
CREATE TRIGGER payout_status_history_no_rewrite BEFORE UPDATE OR DELETE ON core.payout_status_history
    FOR EACH ROW EXECUTE FUNCTION core.reject_payout_history_rewrite();
CREATE TRIGGER payout_status_history_no_truncate BEFORE TRUNCATE ON core.payout_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_payout_history_rewrite();

CREATE VIEW core.v_payout AS
    SELECT id, merchant_payout_ref, amount, currency, status, bank_ack_ref, bank_execution_date,
        bank_txn_id, return_bank_txn_id, failure_reason, return_reason, reserved_at, submitted_at,
        unknown_at, accepted_at, paid_at, failed_at, returned_at, updated_at, recorded_at FROM core.payout;
CREATE VIEW core.v_payout_status_history AS
    SELECT id, payout_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at
        FROM core.payout_status_history;
GRANT INSERT, SELECT ON core.payout, core.payout_status_history TO app;
GRANT UPDATE (status, reserved_at, submitted_at, unknown_at, accepted_at, paid_at, failed_at, returned_at,
    bank_ack_ref, bank_execution_date, bank_txn_id, return_bank_txn_id, failure_reason, return_reason, updated_at)
    ON core.payout TO app;
GRANT SELECT ON core.v_payout, core.v_payout_status_history TO readonly;

ALTER TABLE recon.bank_line ADD COLUMN matched_payout_id text REFERENCES core.payout(id);
ALTER TABLE recon.bank_line DROP CONSTRAINT bank_line_match_status_check;
ALTER TABLE recon.bank_line ADD CONSTRAINT bank_line_match_status_check CHECK (match_status IN
    ('WAITING', 'MATCHED', 'UNAPPLIED', 'UNMATCHED_NO_POSTING', 'IGNORED_PENDING', 'APPLIED', 'RETURNED',
     'RETURN_POSTED', 'PAYOUT_PAID', 'PAYOUT_RETURNED'));
-- A conflict row may retain its candidate payout without being a paid/returned match.
ALTER TABLE recon.bank_line ADD CONSTRAINT bank_line_payout_match CHECK (
    match_status NOT IN ('PAYOUT_PAID', 'PAYOUT_RETURNED') OR matched_payout_id IS NOT NULL);
ALTER TABLE recon.bank_line DROP CONSTRAINT bank_line_direction;
ALTER TABLE recon.bank_line ADD CONSTRAINT bank_line_direction CHECK (
    (match_status NOT IN ('WAITING', 'MATCHED', 'UNAPPLIED', 'APPLIED', 'RETURNED', 'PAYOUT_RETURNED')
        OR direction = 'CREDIT')
    AND (match_status NOT IN ('RETURN_POSTED', 'PAYOUT_PAID') OR direction = 'DEBIT'));
CREATE OR REPLACE FUNCTION recon.protect_bank_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'bank_line' THEN
        IF (to_jsonb(NEW) - 'match_status' - 'matched_batch_id' - 'matched_payout_id') =
           (to_jsonb(OLD) - 'match_status' - 'matched_batch_id' - 'matched_payout_id') THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'bank evidence is immutable' USING ERRCODE = 'LL004';
END;
$$;
REVOKE ALL ON FUNCTION recon.protect_bank_evidence() FROM PUBLIC;
GRANT UPDATE (matched_payout_id) ON recon.bank_line TO app;
CREATE OR REPLACE VIEW recon.v_bank_line AS
    SELECT import_order, bank_txn_id, version, file_id, direction, currency, status, amount, reference,
        return_of, reason_code, booking_date, value_date, memo, match_status,
        matched_batch_id, wait_until, recorded_at, matched_payout_id FROM recon.bank_line;

-- Existing issue subject/category checks already admit payout and both payout issue categories.
ALTER TABLE ledger.correction DROP CONSTRAINT correction_correction_type_check;
ALTER TABLE ledger.correction ADD CONSTRAINT correction_correction_type_check CHECK (correction_type IN
    ('VOID_CAPTURE', 'RESTATE_CAPTURE_AMOUNT', 'APPLY_UNAPPLIED_CASH', 'RESTORE_FAILED_PAYOUT',
     'CONFIRM_FAILED_PAYOUT_PAID'));

ALTER TABLE io.outbound_command DROP CONSTRAINT outbound_command_kind_check;
ALTER TABLE io.outbound_command ADD CONSTRAINT outbound_command_kind_check CHECK (kind IN
    ('SUBMIT_REFUND', 'SUBMIT_PAYOUT', 'QUERY_REFUND', 'QUERY_PAYOUT'));
ALTER TABLE io.outbound_command DROP CONSTRAINT outbound_command_request_id_key;
CREATE UNIQUE INDEX outbound_command_submit_request_unique ON io.outbound_command(request_id)
    WHERE kind IN ('SUBMIT_REFUND', 'SUBMIT_PAYOUT');
CREATE UNIQUE INDEX outbound_command_active_query_unique ON io.outbound_command(kind, request_id)
    WHERE kind IN ('QUERY_REFUND', 'QUERY_PAYOUT') AND status <> 'DONE';
