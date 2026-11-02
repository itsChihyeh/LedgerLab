-- Preserve inbox state at an evidence snapshot without changing the four worker transactions.
CREATE TABLE io.inbox_status_history (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    text NOT NULL REFERENCES io.inbox_event (event_id),
    from_status text CHECK (from_status IN
        ('RECEIVED', 'PARKED', 'PROCESSED', 'REJECTED', 'IGNORED', 'DEAD')),
    to_status   text NOT NULL CHECK (to_status IN
        ('RECEIVED', 'PARKED', 'PROCESSED', 'REJECTED', 'IGNORED', 'DEAD')),
    result      text CHECK (result IN
        ('APPLIED', 'DUPLICATE_FACT', 'BUSINESS_CONFLICT', 'INVALID_AMOUNT',
         'UNSUPPORTED_CURRENCY', 'UNSUPPORTED_CAPTURE', 'UNSUPPORTED_TYPE', 'LIVEMODE')),
    parked_on   text,
    lease_epoch bigint NOT NULL CHECK (lease_epoch >= 0),
    recorded_at timestamptz NOT NULL,
    backfilled  boolean NOT NULL DEFAULT false,
    CHECK (
        (to_status = 'PROCESSED' AND result IS NOT NULL AND result IN ('APPLIED', 'DUPLICATE_FACT'))
        OR (to_status = 'REJECTED' AND result IS NOT NULL AND result NOT IN ('APPLIED', 'DUPLICATE_FACT'))
        OR (to_status IN ('RECEIVED', 'PARKED', 'IGNORED', 'DEAD') AND result IS NULL)
    ),
    CHECK ((to_status = 'PARKED' AND parked_on IS NOT NULL)
        OR (to_status <> 'PARKED' AND parked_on IS NULL))
);

CREATE INDEX inbox_status_history_snapshot_idx
    ON io.inbox_status_history (event_id, recorded_at DESC, id DESC);

INSERT INTO io.inbox_status_history
    (event_id, from_status, to_status, result, parked_on, lease_epoch, recorded_at, backfilled)
SELECT event_id, NULL, status, result, parked_on, lease_epoch,
       COALESCE(processed_at, recorded_at), true
FROM io.inbox_event ORDER BY event_id;

CREATE FUNCTION io.reject_inbox_status_history_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on io.% is not allowed: inbox status history is append-only',
        TG_OP, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;
CREATE TRIGGER inbox_status_history_no_rewrite
    BEFORE UPDATE OR DELETE ON io.inbox_status_history
    FOR EACH ROW EXECUTE FUNCTION io.reject_inbox_status_history_rewrite();
CREATE TRIGGER inbox_status_history_no_truncate
    BEFORE TRUNCATE ON io.inbox_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION io.reject_inbox_status_history_rewrite();

REVOKE ALL ON FUNCTION io.reject_inbox_status_history_rewrite() FROM PUBLIC;
GRANT SELECT, INSERT ON io.inbox_status_history TO app;
-- readonly has no io USAGE or table grants.
