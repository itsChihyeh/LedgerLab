-- P3 T03: durable refund identity, outbound recovery evidence and expiring API responses.
-- Runs as migrator. readonly sees only core views, never the io tables (ADR-0014).
CREATE TABLE core.refund (
    id                   text PRIMARY KEY CHECK (id ~ '^rfd_[0-9A-HJKMNP-TV-Z]{26}$'),
    payment_id           text NOT NULL REFERENCES core.payment (id),
    merchant_refund_ref  text NOT NULL
        CHECK (merchant_refund_ref ~ '[^[:space:]]' AND char_length(merchant_refund_ref) <= 64),
    amount               bigint NOT NULL CHECK (amount BETWEEN 1 AND 99999999),
    status               text NOT NULL CHECK (status IN
        ('AWAITING_FUNDS', 'READY', 'SUBMITTED', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    queue_seq            bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    processor_refund_ref text UNIQUE CHECK (processor_refund_ref LIKE 're\_%' ESCAPE '\'),
    failure_reason       text,
    cancel_reason        text,
    initial_result       jsonb NOT NULL,
    request_fingerprint  text NOT NULL,
    accepted_at          timestamptz NOT NULL,
    submitted_at         timestamptz,
    resolved_at          timestamptz,
    batch_id             text REFERENCES core.settlement_batch (batch_id),
    recorded_at          timestamptz NOT NULL,
    UNIQUE (payment_id, merchant_refund_ref),
    CHECK (status <> 'CANCELLED' OR
        (cancel_reason IS NOT NULL AND cancel_reason ~ '[^[:space:]]')),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')) = (resolved_at IS NOT NULL))
);

CREATE TABLE core.refund_transition (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id      text NOT NULL REFERENCES core.refund (id),
    from_status     text CHECK (from_status IN
        ('AWAITING_FUNDS', 'READY', 'SUBMITTED', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    to_status       text NOT NULL CHECK (to_status IN
        ('AWAITING_FUNDS', 'READY', 'SUBMITTED', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    evidence_type   text NOT NULL,
    evidence_id     text NOT NULL,
    evidence_source text NOT NULL CHECK (evidence_source IN
        ('API', 'QUEUE', 'COMMAND_RESPONSE', 'COMMAND_QUERY', 'WEBHOOK', 'BATCH_LINE', 'OPERATOR')),
    evidence_ref    text NOT NULL,
    occurred_at     timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL
);

CREATE TABLE io.outbound_command (
    id              text PRIMARY KEY CHECK (id ~ '^cmd_[0-9A-HJKMNP-TV-Z]{26}$'),
    kind            text NOT NULL CHECK (kind = 'SUBMIT_REFUND'),
    subject_id      text NOT NULL,
    request_id      text NOT NULL UNIQUE,
    status          text NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'UNKNOWN', 'DONE')),
    attempts        integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    lease_owner     text,
    lease_until     timestamptz,
    lease_epoch     bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    first_sent_at   timestamptz,
    external_ref    text,
    last_outcome    text,
    created_at      timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL
);

CREATE INDEX outbound_command_claim_idx ON io.outbound_command (status, next_attempt_at, created_at);

CREATE TABLE io.outbound_attempt (
    command_id      text NOT NULL REFERENCES io.outbound_command (id),
    attempt_no      integer NOT NULL CHECK (attempt_no > 0),
    lease_epoch     bigint NOT NULL CHECK (lease_epoch >= 0),
    sent_at         timestamptz NOT NULL,
    request_summary jsonb NOT NULL,
    response_status integer CHECK (response_status BETWEEN 100 AND 599),
    response_body   text,
    error           text,
    finished_at     timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL,
    PRIMARY KEY (command_id, attempt_no)
);

CREATE TABLE io.idempotency_record (
    client_id           text NOT NULL,
    idem_key            text NOT NULL,
    request_fingerprint text NOT NULL,
    state               text NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    response_status     integer CHECK (response_status BETWEEN 100 AND 599),
    response_body       jsonb,
    locked_until        timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    recorded_at         timestamptz NOT NULL,
    PRIMARY KEY (client_id, idem_key)
);

CREATE FUNCTION core.reject_refund_evidence_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: refund evidence is append-only',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;

CREATE TRIGGER refund_transition_no_rewrite
    BEFORE UPDATE OR DELETE ON core.refund_transition
    FOR EACH ROW EXECUTE FUNCTION core.reject_refund_evidence_rewrite();
CREATE TRIGGER refund_transition_no_truncate
    BEFORE TRUNCATE ON core.refund_transition
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_refund_evidence_rewrite();
CREATE TRIGGER outbound_attempt_no_rewrite
    BEFORE UPDATE OR DELETE ON io.outbound_attempt
    FOR EACH ROW EXECUTE FUNCTION core.reject_refund_evidence_rewrite();
CREATE TRIGGER outbound_attempt_no_truncate
    BEFORE TRUNCATE ON io.outbound_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_refund_evidence_rewrite();
REVOKE ALL ON FUNCTION core.reject_refund_evidence_rewrite() FROM PUBLIC;

CREATE FUNCTION io.protect_command_first_sent_at() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.first_sent_at IS NOT NULL AND NEW.first_sent_at IS DISTINCT FROM OLD.first_sent_at THEN
        RAISE EXCEPTION 'outbound command first_sent_at is immutable after first send'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbound_command_first_sent_at_immutable
    BEFORE UPDATE ON io.outbound_command
    FOR EACH ROW EXECUTE FUNCTION io.protect_command_first_sent_at();
REVOKE ALL ON FUNCTION io.protect_command_first_sent_at() FROM PUBLIC;

-- Keep both copies of the inbox result enum aligned. Existing REJECTED combination
-- checks accept non-success results; other status/result combinations stay unchanged.
ALTER TABLE io.inbox_event DROP CONSTRAINT inbox_event_result_check;
ALTER TABLE io.inbox_event ADD CONSTRAINT inbox_event_result_check CHECK (result IN
    ('APPLIED', 'DUPLICATE_FACT', 'BUSINESS_CONFLICT', 'INVALID_AMOUNT',
     'UNSUPPORTED_CURRENCY', 'UNSUPPORTED_CAPTURE', 'UNSUPPORTED_TYPE', 'LIVEMODE', 'UNKNOWN_REFUND'));
ALTER TABLE io.inbox_status_history DROP CONSTRAINT inbox_status_history_result_check;
ALTER TABLE io.inbox_status_history ADD CONSTRAINT inbox_status_history_result_check CHECK (result IN
    ('APPLIED', 'DUPLICATE_FACT', 'BUSINESS_CONFLICT', 'INVALID_AMOUNT',
     'UNSUPPORTED_CURRENCY', 'UNSUPPORTED_CAPTURE', 'UNSUPPORTED_TYPE', 'LIVEMODE', 'UNKNOWN_REFUND'));

CREATE VIEW core.v_refund AS
    SELECT id, payment_id, merchant_refund_ref, amount, status, queue_seq, processor_refund_ref,
           failure_reason, cancel_reason, initial_result, request_fingerprint, accepted_at,
           submitted_at, resolved_at, batch_id, recorded_at
    FROM core.refund;
CREATE VIEW core.v_refund_transition AS
    SELECT id, subject_id, from_status, to_status, evidence_type, evidence_id, evidence_source,
           evidence_ref, occurred_at, recorded_at
    FROM core.refund_transition;

GRANT SELECT, INSERT, UPDATE (status, processor_refund_ref, failure_reason, cancel_reason,
    submitted_at, resolved_at, batch_id) ON core.refund TO app;
GRANT SELECT, INSERT ON core.refund_transition, io.outbound_attempt TO app;
GRANT SELECT, INSERT, UPDATE (status, attempts, next_attempt_at, lease_owner, lease_until,
    lease_epoch, first_sent_at, external_ref, last_outcome) ON io.outbound_command TO app;
-- DELETE is intentionally limited to the expiring API cache. Durable business identity remains in core.refund.
GRANT SELECT, INSERT, DELETE, UPDATE (state, response_status, response_body, locked_until)
    ON io.idempotency_record TO app;
GRANT SELECT ON core.v_refund, core.v_refund_transition TO readonly;
