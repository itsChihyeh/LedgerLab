CREATE TABLE sim_bank.outgoing_payment (
    request_id text PRIMARY KEY CHECK (request_id ~ '^mpo_.+'),
    payment_id text NOT NULL UNIQUE CHECK (payment_id ~ '^bpm_.+'),
    account_ref text NOT NULL REFERENCES sim_bank.account,
    amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 99999999),
    currency text NOT NULL CHECK (currency = 'CAD'),
    destination text NOT NULL CHECK (length(btrim(destination)) > 0),
    status text NOT NULL CHECK (status IN ('ACCEPTED','EXECUTED','REJECTED','RETURNED')),
    reason text,
    execution_date date NOT NULL,
    return_due_date date,
    plan jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    recorded_at timestamptz NOT NULL
);
ALTER TABLE sim_bank.fault_plan DROP CONSTRAINT fault_plan_fault_check;
ALTER TABLE sim_bank.fault_plan ADD CONSTRAINT fault_plan_fault_check CHECK (fault IN (
    'NONE','SHORT','DUPLICATE','SPLIT','DELAY_DAYS','PENDING_FIRST','FOREIGN_CURRENCY','UNKNOWN_CREDIT',
    'TIMEOUT','EXECUTE_DROP_RESPONSE','UNAVAILABLE','REJECT','REJECT_ON_QUERY','EXECUTE_AFTER_REJECT','RETURN_AFTER_DAYS'
));
ALTER TABLE sim_bank.txn ALTER COLUMN payout_ref DROP NOT NULL;
ALTER TABLE sim_bank.txn ADD COLUMN request_id text REFERENCES sim_bank.outgoing_payment;
ALTER TABLE sim_bank.txn ADD CONSTRAINT txn_one_source CHECK (
    (payout_ref IS NOT NULL) <> (request_id IS NOT NULL)
);
REVOKE ALL ON sim_bank.outgoing_payment FROM PUBLIC, migrator, app, readonly;
