CREATE TABLE sim_sandbox.payment_attempt (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id text NOT NULL CHECK (run_id ~ '^sbx_[A-Za-z0-9_-]{1,100}$'),
    card text NOT NULL CHECK (card IN ('pm_card_bypassPending', 'pm_card_ca')),
    seed bigint NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 50),
    amount bigint NOT NULL CHECK (amount BETWEEN 500 AND 20000),
    payment_intent text NOT NULL CHECK (payment_intent = '' OR payment_intent ~ '^pi_[A-Za-z0-9]+$'),
    charge text NOT NULL CHECK (charge = '' OR charge ~ '^ch_[A-Za-z0-9]+$'),
    status text NOT NULL CHECK (status IN ('started', 'failed', 'succeeded', 'processing', 'requires_payment_method',
        'requires_confirmation', 'requires_action', 'requires_capture', 'canceled')),
    http_status integer CHECK (http_status BETWEEN 100 AND 599),
    error_code text NOT NULL,
    recorded_at timestamptz NOT NULL
);
CREATE INDEX payment_attempt_identity ON sim_sandbox.payment_attempt (run_id, card, seed, ordinal, id DESC);

CREATE TABLE sim_sandbox.bank_sync (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id text NOT NULL CHECK (run_id ~ '^sbx_[A-Za-z0-9_-]{1,100}$'),
    pages integer NOT NULL CHECK (pages >= 0),
    seen integer NOT NULL CHECK (seen >= 0),
    received integer NOT NULL CHECK (received >= 0 AND received <= seen),
    http_status integer NOT NULL CHECK (http_status IN (200, 502)),
    received_payouts jsonb NOT NULL CHECK (jsonb_typeof(received_payouts) = 'array'),
    skipped jsonb NOT NULL CHECK (jsonb_typeof(skipped) = 'array'),
    error_code text NOT NULL,
    recorded_at timestamptz NOT NULL,
    CHECK (received = CASE WHEN jsonb_typeof(received_payouts) = 'array'
        THEN jsonb_array_length(received_payouts) ELSE -1 END),
    CHECK (http_status = 200 OR received = 0)
);
CREATE INDEX bank_sync_run ON sim_sandbox.bank_sync (run_id, id);

REVOKE ALL ON sim_sandbox.payment_attempt, sim_sandbox.bank_sync
    FROM PUBLIC, migrator, app, readonly, simulator;
GRANT INSERT, SELECT ON sim_sandbox.payment_attempt, sim_sandbox.bank_sync TO simulator;
REVOKE ALL ON SEQUENCE sim_sandbox.payment_attempt_id_seq, sim_sandbox.bank_sync_id_seq
    FROM PUBLIC, migrator, app, readonly, simulator;
GRANT USAGE, SELECT ON SEQUENCE sim_sandbox.payment_attempt_id_seq, sim_sandbox.bank_sync_id_seq TO simulator;
