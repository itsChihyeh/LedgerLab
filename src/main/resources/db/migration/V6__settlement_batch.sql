-- P2 T02: settlement facts, immutable fetched evidence and batch status history.
-- Runs as migrator, which owns all new tables, functions and views.
CREATE TABLE io.processor_fetch (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        text        REFERENCES io.inbox_event (event_id),
    purpose         text        NOT NULL CHECK (purpose IN ('BATCH_LINES', 'PAYOUT', 'RECON_SNAPSHOT')),
    request         jsonb       NOT NULL,
    response_body   text        NOT NULL,
    response_sha256 text        NOT NULL
        CHECK (response_sha256 = encode(sha256(convert_to(response_body, 'UTF8')), 'hex')),
    complete        boolean     NOT NULL,
    fetched_at      timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL
);

CREATE TABLE core.settlement_batch (
    batch_id                 text        PRIMARY KEY CHECK (batch_id ~ '^po_.+'),
    -- Keep unsupported payouts as evidence; only confirmed batches require positive net.
    payout_amount            bigint      NOT NULL
        CHECK (payout_amount BETWEEN -9007199254740991 AND 9007199254740991),
    currency                 text        NOT NULL,
    automatic                boolean     NOT NULL,
    method                   text        NOT NULL,
    settlement_date          date,
    arrival_date             date,
    gross                    bigint      CHECK (gross BETWEEN 0 AND 9007199254740991),
    fees                     bigint      CHECK (fees BETWEEN 0 AND 9007199254740991),
    refunds                  bigint      CHECK (refunds BETWEEN 0 AND 9007199254740991),
    payout_failures_returned  bigint      CHECK (payout_failures_returned BETWEEN 0 AND 9007199254740991),
    net                      bigint      CHECK (net BETWEEN -9007199254740991 AND 9007199254740991),
    status                   text        NOT NULL
        CHECK (status IN ('ANNOUNCED', 'RECEIVED', 'CONFIRMED', 'HELD', 'PAYOUT_FAILED', 'BANKED')),
    hold_reason              text        CHECK (hold_reason IN
        ('UNKNOWN_PAYMENT', 'AMOUNT_MISMATCH', 'UNSUPPORTED_LINE', 'UNSUPPORTED_PAYOUT',
         'FEE_EXCEEDS_GROSS', 'LINE_IN_OTHER_BATCH', 'TOTAL_MISMATCH', 'LINES_NOT_READY')),
    source_event_id          text        NOT NULL REFERENCES io.inbox_event (event_id),
    lines_fetch_id           bigint      REFERENCES io.processor_fetch (id),
    occurred_at              timestamptz NOT NULL,
    recorded_at              timestamptz NOT NULL,
    updated_at               timestamptz NOT NULL,
    CHECK ((status = 'HELD') = (hold_reason IS NOT NULL)),
    -- Each bounded operand fits safely in bigint, including intermediate arithmetic.
    CHECK (net = gross - fees - refunds + payout_failures_returned),
    CHECK (status NOT IN ('CONFIRMED', 'BANKED', 'PAYOUT_FAILED') OR
        (gross IS NOT NULL AND fees IS NOT NULL AND refunds IS NOT NULL
         AND payout_failures_returned IS NOT NULL AND net IS NOT NULL
         AND net > 0 AND net = payout_amount))
);

CREATE TABLE core.settlement_batch_line (
    batch_id       text        NOT NULL REFERENCES core.settlement_batch (batch_id),
    line_no        integer     NOT NULL CHECK (line_no > 0),
    line_type      text        NOT NULL CHECK (line_type IN ('PAYMENT', 'REFUND', 'PAYOUT_FAILURE')),
    processor_ref  text        NOT NULL,
    balance_txn_id text        NOT NULL UNIQUE CHECK (balance_txn_id ~ '^txn_.+'),
    gross          bigint      NOT NULL,
    fee            bigint      NOT NULL CHECK (fee BETWEEN 0 AND 99999999),
    net            bigint      NOT NULL CHECK (net BETWEEN -99999999 AND 99999999),
    occurred_at    timestamptz NOT NULL,
    recorded_at    timestamptz NOT NULL,
    PRIMARY KEY (batch_id, line_no),
    CHECK ((line_type = 'REFUND' AND gross BETWEEN -99999999 AND -1)
        OR (line_type IN ('PAYMENT', 'PAYOUT_FAILURE') AND gross BETWEEN 1 AND 99999999)),
    CHECK (net = gross - fee)
);

CREATE TABLE core.batch_transition (
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_id    text        NOT NULL REFERENCES core.settlement_batch (batch_id),
    from_status   text        CHECK (from_status IN
        ('ANNOUNCED', 'RECEIVED', 'CONFIRMED', 'HELD', 'PAYOUT_FAILED', 'BANKED')),
    to_status     text        NOT NULL CHECK (to_status IN
        ('ANNOUNCED', 'RECEIVED', 'CONFIRMED', 'HELD', 'PAYOUT_FAILED', 'BANKED')),
    evidence_type text        NOT NULL,
    evidence_id   text        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    recorded_at   timestamptz NOT NULL
);

CREATE FUNCTION core.require_confirmed_batch_for_line() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    batch_status text;
BEGIN
    -- SHARE prevents a concurrent status update until this insertion commits.
    SELECT status INTO batch_status FROM core.settlement_batch
        WHERE batch_id = NEW.batch_id FOR SHARE;
    -- Let NOT NULL / FK constraints report missing references with their normal SQLSTATE.
    IF FOUND AND batch_status <> 'CONFIRMED' THEN
        RAISE EXCEPTION 'batch % must be CONFIRMED before inserting lines', NEW.batch_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER settlement_batch_line_confirmed
    BEFORE INSERT ON core.settlement_batch_line
    FOR EACH ROW EXECUTE FUNCTION core.require_confirmed_batch_for_line();
REVOKE ALL ON FUNCTION core.require_confirmed_batch_for_line() FROM PUBLIC;

CREATE FUNCTION core.reject_settlement_evidence_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: settlement evidence is append-only',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;

CREATE TRIGGER settlement_batch_line_no_rewrite
    BEFORE UPDATE OR DELETE ON core.settlement_batch_line
    FOR EACH ROW EXECUTE FUNCTION core.reject_settlement_evidence_rewrite();
CREATE TRIGGER settlement_batch_line_no_truncate
    BEFORE TRUNCATE ON core.settlement_batch_line
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_settlement_evidence_rewrite();
CREATE TRIGGER batch_transition_no_rewrite
    BEFORE UPDATE OR DELETE ON core.batch_transition
    FOR EACH ROW EXECUTE FUNCTION core.reject_settlement_evidence_rewrite();
CREATE TRIGGER batch_transition_no_truncate
    BEFORE TRUNCATE ON core.batch_transition
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_settlement_evidence_rewrite();
CREATE TRIGGER processor_fetch_no_rewrite
    BEFORE UPDATE OR DELETE ON io.processor_fetch
    FOR EACH ROW EXECUTE FUNCTION core.reject_settlement_evidence_rewrite();
CREATE TRIGGER processor_fetch_no_truncate
    BEFORE TRUNCATE ON io.processor_fetch
    FOR EACH STATEMENT EXECUTE FUNCTION core.reject_settlement_evidence_rewrite();
REVOKE ALL ON FUNCTION core.reject_settlement_evidence_rewrite() FROM PUBLIC;

ALTER TABLE core.payment ADD CONSTRAINT payment_batch_fk
    FOREIGN KEY (batch_id) REFERENCES core.settlement_batch (batch_id);

CREATE VIEW core.v_settlement_batch AS
    SELECT batch_id, payout_amount, currency, automatic, method, settlement_date, arrival_date,
           gross, fees, refunds, payout_failures_returned, net, status, hold_reason,
           source_event_id, lines_fetch_id, occurred_at, recorded_at, updated_at
    FROM core.settlement_batch;
CREATE VIEW core.v_settlement_batch_line AS
    SELECT batch_id, line_no, line_type, processor_ref, balance_txn_id, gross, fee, net,
           occurred_at, recorded_at
    FROM core.settlement_batch_line;
CREATE VIEW core.v_batch_transition AS
    SELECT id, subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at
    FROM core.batch_transition;

GRANT SELECT, INSERT, UPDATE (status, hold_reason, settlement_date, arrival_date, gross, fees,
    refunds, payout_failures_returned, net, lines_fetch_id, updated_at) ON core.settlement_batch TO app;
GRANT SELECT, INSERT ON core.settlement_batch_line, core.batch_transition, io.processor_fetch TO app;
GRANT SELECT ON core.v_settlement_batch, core.v_settlement_batch_line, core.v_batch_transition TO readonly;
