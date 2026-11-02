-- P1b-1: payment facts and append-only status history (data-dictionary sections 0.1 and 2).
-- Runs as migrator; core and its schema USAGE grants already exist from V1.

CREATE TABLE core.payment (
    id                    text        PRIMARY KEY CHECK (id ~ '^pay_[0-9A-HJKMNP-TV-Z]{26}$'),
    processor_payment_ref text        NOT NULL UNIQUE CHECK (processor_payment_ref ~ '^ch_.+'),
    processor_intent_ref  text,
    amount                bigint      NOT NULL CHECK (amount BETWEEN 50 AND 99999999),
    expected_fee          bigint      NOT NULL CHECK (expected_fee >= 0),
    actual_fee            bigint      CHECK (actual_fee >= 0),
    status                text        NOT NULL CHECK (status IN ('CAPTURED', 'SETTLED', 'VOIDED')),
    -- Add the FK when core.settlement_batch is created in P2.
    batch_id              text,
    refunded_amount       bigint      NOT NULL DEFAULT 0 CHECK (refunded_amount BETWEEN 0 AND amount),
    -- Add the FK when io.inbox_event is created in a later P1b task.
    source_event_id       text        NOT NULL,
    occurred_at           timestamptz NOT NULL,
    received_at           timestamptz NOT NULL,
    recorded_at           timestamptz NOT NULL
);

CREATE TABLE core.payment_transition (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id    text        NOT NULL REFERENCES core.payment (id),
    from_status   text        CHECK (from_status IN ('CAPTURED', 'SETTLED', 'VOIDED')),
    to_status     text        NOT NULL CHECK (to_status IN ('CAPTURED', 'SETTLED', 'VOIDED')),
    evidence_type text        NOT NULL,
    evidence_id   text        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    recorded_at   timestamptz NOT NULL
);

-- Keep this function in core: ledger.reject_ledger_rewrite() reports ledger-specific
-- table names and reversal instructions, which do not describe payment status history.
-- LL004 retains its append-only rewrite refusal meaning. This also guards owner mistakes.
CREATE FUNCTION core.reject_payment_transition_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on core.% is not allowed: payment status history is append-only',
        TG_OP, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;

CREATE TRIGGER payment_transition_no_rewrite
    BEFORE UPDATE OR DELETE ON core.payment_transition
    FOR EACH ROW EXECUTE FUNCTION core.reject_payment_transition_rewrite();
CREATE TRIGGER payment_transition_no_truncate
    BEFORE TRUNCATE ON core.payment_transition
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_payment_transition_rewrite();

REVOKE ALL ON FUNCTION core.reject_payment_transition_rewrite() FROM PUBLIC;

CREATE VIEW core.v_payment AS
    SELECT id, processor_payment_ref, processor_intent_ref, amount, expected_fee, actual_fee,
           status, batch_id, refunded_amount, source_event_id, occurred_at, received_at, recorded_at
    FROM core.payment;

CREATE VIEW core.v_payment_transition AS
    SELECT id, subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at
    FROM core.payment_transition;

GRANT SELECT, INSERT, UPDATE (status, actual_fee, batch_id, refunded_amount) ON core.payment TO app;
GRANT SELECT, INSERT ON core.payment_transition TO app;
GRANT SELECT ON core.v_payment, core.v_payment_transition TO readonly;
