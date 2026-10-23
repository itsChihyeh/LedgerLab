-- P1b-2: retain immutable incoming evidence and mutable worker progress.
-- io schema and app USAGE already exist from V1; readonly has no access.
CREATE TABLE io.inbox_event (
    event_id        text        PRIMARY KEY CHECK (event_id LIKE 'evt\_%' ESCAPE '\'),
    event_type      text        NOT NULL,
    api_version     text        NOT NULL,
    livemode        boolean     NOT NULL,
    payload         bytea       NOT NULL,
    payload_sha256  text        NOT NULL CHECK (payload_sha256 = encode(sha256(payload), 'hex')),
    signed_at       timestamptz NOT NULL,
    occurred_at     timestamptz NOT NULL,
    received_at     timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL,
    status          text        NOT NULL CHECK (status IN
        ('RECEIVED', 'PARKED', 'PROCESSED', 'REJECTED', 'IGNORED', 'DEAD')),
    result          text        CHECK (result IN
        ('APPLIED', 'DUPLICATE_FACT', 'BUSINESS_CONFLICT', 'INVALID_AMOUNT',
         'UNSUPPORTED_CURRENCY', 'UNSUPPORTED_CAPTURE', 'UNSUPPORTED_TYPE', 'LIVEMODE')),
    attempts        int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    lease_owner     text,
    lease_until     timestamptz,
    lease_epoch     bigint      NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    parked_on       text,
    last_error      text,
    processed_at    timestamptz,
    CHECK (
        (status = 'PROCESSED' AND result IS NOT NULL AND result IN ('APPLIED', 'DUPLICATE_FACT'))
        OR (status = 'REJECTED' AND result IS NOT NULL AND result NOT IN ('APPLIED', 'DUPLICATE_FACT'))
        OR (status IN ('RECEIVED', 'PARKED', 'IGNORED', 'DEAD') AND result IS NULL)
    ),
    CHECK ((status = 'PARKED' AND parked_on IS NOT NULL)
        OR (status <> 'PARKED' AND parked_on IS NULL))
);

CREATE INDEX inbox_event_claim_idx ON io.inbox_event (status, next_attempt_at, received_at);

CREATE TABLE io.inbox_conflict (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       text        NOT NULL REFERENCES io.inbox_event (event_id),
    payload        bytea       NOT NULL,
    payload_sha256 text        NOT NULL CHECK (payload_sha256 = encode(sha256(payload), 'hex')),
    signed_at      timestamptz NOT NULL,
    received_at    timestamptz NOT NULL,
    recorded_at    timestamptz NOT NULL
);

CREATE FUNCTION io.reject_inbox_conflict_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on io.% is not allowed: inbox conflicts are append-only',
        TG_OP, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;

CREATE TRIGGER inbox_conflict_no_rewrite
    BEFORE UPDATE OR DELETE ON io.inbox_conflict
    FOR EACH ROW EXECUTE FUNCTION io.reject_inbox_conflict_rewrite();
CREATE TRIGGER inbox_conflict_no_truncate
    BEFORE TRUNCATE ON io.inbox_conflict
    FOR EACH STATEMENT EXECUTE FUNCTION io.reject_inbox_conflict_rewrite();

REVOKE ALL ON FUNCTION io.reject_inbox_conflict_rewrite() FROM PUBLIC;

ALTER TABLE core.payment ADD CONSTRAINT payment_source_event_fk
    FOREIGN KEY (source_event_id) REFERENCES io.inbox_event (event_id);

GRANT SELECT, INSERT, UPDATE (status, result, attempts, next_attempt_at, lease_owner,
    lease_until, lease_epoch, parked_on, last_error, processed_at) ON io.inbox_event TO app;
GRANT SELECT, INSERT ON io.inbox_conflict TO app;
