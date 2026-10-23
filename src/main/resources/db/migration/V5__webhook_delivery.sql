-- P1c-3: preserve every accepted delivery without changing inbox deduplication.
CREATE TABLE io.webhook_delivery (
    id bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT webhook_delivery_pk PRIMARY KEY,
    event_id text NOT NULL CONSTRAINT webhook_delivery_event_fk REFERENCES io.inbox_event (event_id),
    outcome text NOT NULL CONSTRAINT webhook_delivery_outcome CHECK (outcome IN ('NEW', 'DUPLICATE', 'CONFLICT')),
    payload_sha256 text NOT NULL CONSTRAINT webhook_delivery_hash CHECK (payload_sha256 ~ '^[0-9a-fA-F]{64}$'),
    signed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL
);

CREATE INDEX webhook_delivery_event_idx ON io.webhook_delivery (event_id, received_at, id);

CREATE FUNCTION io.reject_webhook_delivery_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on io.% is not allowed: deliveries are append-only',
        TG_OP, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;

CREATE TRIGGER webhook_delivery_no_rewrite
    BEFORE UPDATE OR DELETE ON io.webhook_delivery
    FOR EACH ROW EXECUTE FUNCTION io.reject_webhook_delivery_rewrite();
CREATE TRIGGER webhook_delivery_no_truncate
    BEFORE TRUNCATE ON io.webhook_delivery
    FOR EACH STATEMENT EXECUTE FUNCTION io.reject_webhook_delivery_rewrite();
REVOKE ALL ON FUNCTION io.reject_webhook_delivery_rewrite() FROM PUBLIC;

-- Owned by migrator: use the view owner's permissions, not security_invoker.
-- readonly still cannot access io. Expose only delivery summaries and the original
-- charge reference, never raw payloads (including conflicting delivery payloads).
CREATE VIEW core.v_payment_delivery AS
    SELECT d.id, d.event_id, e.event_type, d.outcome, d.received_at, e.status, e.result,
           CASE WHEN e.event_type LIKE 'charge.%'
                THEN convert_from(e.payload, 'UTF8')::json #>> '{data,object,id}'
           END AS processor_payment_ref
    FROM io.webhook_delivery d JOIN io.inbox_event e ON e.event_id = d.event_id;

GRANT SELECT, INSERT ON io.webhook_delivery TO app;
GRANT SELECT ON core.v_payment_delivery TO readonly;
