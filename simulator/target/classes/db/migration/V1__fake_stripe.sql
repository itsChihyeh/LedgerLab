CREATE TABLE sim_processor.charge (
    id text PRIMARY KEY,
    payment_intent text NOT NULL,
    amount bigint NOT NULL,
    amount_captured bigint NOT NULL,
    currency text NOT NULL,
    captured boolean NOT NULL,
    status text NOT NULL,
    livemode boolean NOT NULL,
    created bigint NOT NULL,
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_processor.event (
    id text PRIMARY KEY,
    charge_id text NOT NULL REFERENCES sim_processor.charge(id),
    type text NOT NULL,
    api_version text NOT NULL,
    created bigint NOT NULL,
    payload text NOT NULL,
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_processor.webhook_delivery (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text NOT NULL REFERENCES sim_processor.event(id),
    target_url text NOT NULL,
    signed_at bigint NOT NULL,
    http_status integer,
    error text,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT delivery_result CHECK ((http_status IS NULL) <> (error IS NULL))
);
CREATE INDEX webhook_delivery_event ON sim_processor.webhook_delivery(event_id, id);
REVOKE ALL ON ALL TABLES IN SCHEMA sim_processor FROM PUBLIC, migrator, app, readonly;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA sim_processor FROM PUBLIC, migrator, app, readonly;
