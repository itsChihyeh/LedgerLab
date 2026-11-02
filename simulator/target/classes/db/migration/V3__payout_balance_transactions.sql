CREATE TABLE sim_processor.payout (
    id text PRIMARY KEY,
    amount bigint NOT NULL CHECK (amount > -9007199254740992 AND amount < 9007199254740992),
    currency text NOT NULL,
    automatic boolean NOT NULL,
    method text NOT NULL CHECK (method IN ('standard', 'instant')),
    status text NOT NULL CHECK (status IN ('pending', 'in_transit', 'paid', 'failed', 'canceled')),
    reconciliation_status text NOT NULL CHECK (reconciliation_status IN ('in_progress', 'completed', 'not_applicable')),
    arrival_date timestamptz NOT NULL,
    created timestamptz NOT NULL,
    failure_code text,
    duplicate_transaction text,
    fail_page integer NOT NULL DEFAULT 0 CHECK (fail_page >= 0),
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_processor.balance_transaction (
    id text PRIMARY KEY,
    type text NOT NULL CHECK (type IN ('charge', 'refund', 'payout', 'payout_failure', 'adjustment')),
    source text NOT NULL,
    amount bigint NOT NULL CHECK (amount > -9007199254740992 AND amount < 9007199254740992),
    fee bigint NOT NULL CHECK (fee > -9007199254740992 AND fee < 9007199254740992),
    net bigint NOT NULL CHECK (net > -9007199254740992 AND net < 9007199254740992),
    currency text NOT NULL,
    created timestamptz NOT NULL,
    available_on timestamptz NOT NULL,
    payout text REFERENCES sim_processor.payout(id),
    reporting_category text NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT balance_transaction_net CHECK (net = amount - fee)
);
CREATE INDEX balance_transaction_payout ON sim_processor.balance_transaction(payout, created DESC, id DESC);
CREATE INDEX payout_created ON sim_processor.payout(created DESC, id DESC);
CREATE TABLE sim_processor.fault_plan (
    id text PRIMARY KEY,
    target_id text NOT NULL,
    request text NOT NULL,
    recorded_at timestamptz NOT NULL
);
ALTER TABLE sim_processor.event ALTER COLUMN charge_id DROP NOT NULL;
ALTER TABLE sim_processor.event ADD COLUMN payout_id text REFERENCES sim_processor.payout(id);
REVOKE ALL ON sim_processor.payout, sim_processor.balance_transaction, sim_processor.fault_plan
    FROM PUBLIC, migrator, app, readonly;
