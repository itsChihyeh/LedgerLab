CREATE TABLE sim_bank.account (
    account_ref text PRIMARY KEY,
    currency text NOT NULL CHECK (currency = 'CAD'),
    balance bigint NOT NULL CHECK (balance >= 0 AND balance < 9007199254740992),
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_bank.fault_plan (
    target_ref text PRIMARY KEY,
    scenario text NOT NULL,
    fault text NOT NULL CHECK (fault IN ('NONE','SHORT','DUPLICATE','SPLIT','DELAY_DAYS','PENDING_FIRST','FOREIGN_CURRENCY','UNKNOWN_CREDIT')),
    parameter bigint NOT NULL CHECK (parameter >= 0 AND parameter <= 99999999),
    memo text NOT NULL,
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_bank.incoming_transfer (
    payout_ref text PRIMARY KEY,
    account_ref text NOT NULL REFERENCES sim_bank.account,
    claimed_amount bigint NOT NULL CHECK (claimed_amount > 0 AND claimed_amount < 9007199254740992),
    plan jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    due_date date NOT NULL,
    executed boolean NOT NULL,
    recorded_at timestamptz NOT NULL
);
CREATE TABLE sim_bank.txn (
    bank_txn_id text PRIMARY KEY,
    account_ref text NOT NULL REFERENCES sim_bank.account,
    payout_ref text NOT NULL REFERENCES sim_bank.incoming_transfer,
    direction text NOT NULL CHECK (direction IN ('CREDIT','DEBIT')),
    amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 99999999),
    currency text NOT NULL CHECK (currency IN ('CAD','USD')),
    status text NOT NULL CHECK (status IN ('BOOKED','PENDING')),
    reference text NOT NULL,
    booking_date date NOT NULL,
    value_date date NOT NULL,
    visible_date date NOT NULL,
    memo text NOT NULL,
    recorded_at timestamptz NOT NULL,
    CHECK (booking_date >= visible_date)
);
CREATE TABLE sim_bank.statement (
    account_ref text NOT NULL REFERENCES sim_bank.account,
    statement_date date NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    content text NOT NULL,
    body text NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (account_ref, statement_date, version)
);
CREATE TABLE sim_truth.injected_fault (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scenario text NOT NULL,
    object_ref text NOT NULL,
    fault text NOT NULL,
    expected_category text NOT NULL,
    recorded_at timestamptz NOT NULL
);
REVOKE ALL ON sim_bank.account, sim_bank.fault_plan, sim_bank.incoming_transfer,
    sim_bank.txn, sim_bank.statement, sim_truth.injected_fault FROM PUBLIC, migrator, app, readonly;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA sim_truth FROM PUBLIC, migrator, app, readonly;
