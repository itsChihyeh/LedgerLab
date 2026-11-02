CREATE TABLE sim_processor.refunds (
    id text PRIMARY KEY CHECK (id LIKE 're\_%' ESCAPE '\'),
    charge text NOT NULL REFERENCES sim_processor.charge(id),
    amount bigint NOT NULL CHECK (amount > 0 AND amount < 9007199254740992),
    status text NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed', 'canceled')),
    metadata jsonb NOT NULL CHECK (jsonb_typeof(metadata) = 'object'),
    created timestamptz NOT NULL,
    creation_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    failure_reason text,
    balance_transaction text UNIQUE REFERENCES sim_processor.balance_transaction(id),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT refund_execution CHECK ((status = 'succeeded') = (balance_transaction IS NOT NULL)),
    CONSTRAINT refund_failure CHECK ((status = 'failed') = (failure_reason IS NOT NULL))
);
CREATE INDEX refunds_charge_order ON sim_processor.refunds(charge, created DESC, creation_order DESC);
CREATE INDEX refunds_pending_order ON sim_processor.refunds(created, creation_order) WHERE status = 'pending';

CREATE TABLE sim_processor.idempotency_keys (
    key text PRIMARY KEY CHECK (length(key) BETWEEN 1 AND 255),
    request_fingerprint text NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    response text NOT NULL,
    created timestamptz NOT NULL,
    purged boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_processor.refund_faults (
    target text PRIMARY KEY CHECK (target = '*' OR target LIKE 'ch\_%' ESCAPE '\'),
    plan jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    recorded_at timestamptz NOT NULL
);
ALTER TABLE sim_processor.event ADD COLUMN refund_id text REFERENCES sim_processor.refunds(id);
REVOKE ALL ON sim_processor.refunds, sim_processor.idempotency_keys, sim_processor.refund_faults
    FROM PUBLIC, migrator, app, readonly;
REVOKE ALL ON SEQUENCE sim_processor.refunds_creation_order_seq FROM PUBLIC, migrator, app, readonly;
