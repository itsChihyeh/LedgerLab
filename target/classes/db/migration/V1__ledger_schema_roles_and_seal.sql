-- P1a: schemas, the ledger tables, the seal and balance triggers, and the grants for app and readonly.
-- Design: docs/design/data-dictionary.md sections 0, 0.1 and 1; ADR-0002, ADR-0003, ADR-0004, ADR-0014.
-- Runs as migrator, which owns everything created here. The roles themselves are created when the
-- database is initialised (docker/postgres/initdb/01-roles.sh), because roles are cluster-wide.
--
-- Custom SQLSTATEs raised by the triggers below (class LL, "ledger"):
--   LL001  account attributes are immutable
--   LL002  posting added outside the transaction that created its entry (append attack)
--   LL003  entry does not match its header: posting count, debit total, credit total
--   LL004  journal entries and postings are never updated, deleted or truncated

CREATE SCHEMA ledger;
CREATE SCHEMA core;
CREATE SCHEMA io;
CREATE SCHEMA recon;

GRANT USAGE ON SCHEMA ledger, core, io, recon TO app;
-- readonly reads views in ledger, core and recon; io is invisible to it.
GRANT USAGE ON SCHEMA ledger, core, recon TO readonly;

-- ---------------------------------------------------------------------------------------------
-- funds_lock: one row; every money-moving transaction locks it first (ADR-0004 addendum).

CREATE TABLE ledger.funds_lock (
    id          smallint    PRIMARY KEY CHECK (id = 1),
    touched_at  timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO ledger.funds_lock (id, touched_at) VALUES (1, now());

-- ---------------------------------------------------------------------------------------------
-- account: the chart of accounts and the stored balance (ADR-0002). The code is also the lock order.

CREATE TABLE ledger.account (
    code           smallint    PRIMARY KEY,
    name           text        NOT NULL UNIQUE,
    kind           text        NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY')),
    normal_side    char(1)     NOT NULL CHECK (normal_side IN ('D', 'C')),
    allow_negative boolean     NOT NULL,
    balance        bigint      NOT NULL DEFAULT 0,
    version        bigint      NOT NULL DEFAULT 0 CHECK (version >= 0),
    recorded_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT account_kind_matches_side
        CHECK ((kind = 'ASSET' AND normal_side = 'D') OR (kind = 'LIABILITY' AND normal_side = 'C')),
    -- INV-5 backstop: holds even for a direct UPDATE that bypasses the posting interface.
    CONSTRAINT account_non_negative CHECK (allow_negative OR balance >= 0),
    -- Aggregates stay exactly representable as JSON numbers (contracts section 3).
    CONSTRAINT account_balance_bound CHECK (balance > -9007199254740992 AND balance < 9007199254740992)
);

INSERT INTO ledger.account (code, name, kind, normal_side, allow_negative) VALUES
    (1101, 'processor_receivable',  'ASSET',     'D', true),
    (1102, 'settlement_in_transit', 'ASSET',     'D', false),
    (1103, 'bank_cash',             'ASSET',     'D', false),
    (2101, 'merchant_pending',      'LIABILITY', 'C', false),
    (2102, 'merchant_available',    'LIABILITY', 'C', true),
    (2103, 'refund_payable',        'LIABILITY', 'C', false),
    (2104, 'payout_in_transit',     'LIABILITY', 'C', false),
    (2105, 'unapplied_cash',        'LIABILITY', 'C', false);

-- app has no privilege on these columns; this trigger also stops the owner (S4 attack on attributes).
CREATE FUNCTION ledger.reject_account_attribute_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code IS DISTINCT FROM OLD.code
        OR NEW.name IS DISTINCT FROM OLD.name
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.normal_side IS DISTINCT FROM OLD.normal_side
        OR NEW.allow_negative IS DISTINCT FROM OLD.allow_negative THEN
        RAISE EXCEPTION 'account % attributes are immutable', OLD.code USING ERRCODE = 'LL001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_attributes_immutable
    BEFORE UPDATE ON ledger.account
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_account_attribute_change();

-- ---------------------------------------------------------------------------------------------
-- journal_entry and posting: append-only, sealed at commit (ADR-0003).

CREATE TABLE ledger.journal_entry (
    id                text        PRIMARY KEY CHECK (id ~ '^je_[0-9A-HJKMNP-TV-Z]{26}$'),
    seq               bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    entry_type        text        NOT NULL CHECK (entry_type IN (
                          'PAYMENT_CAPTURED', 'BATCH_SETTLED', 'BATCH_PAYOUT_FAILED', 'BANK_DEPOSIT_RECEIVED',
                          'REFUND_ACCEPTED', 'REFUND_EXECUTED', 'REFUND_FAILED', 'REFUND_CANCELLED',
                          'PAYOUT_RESERVED', 'PAYOUT_PAID', 'PAYOUT_FAILED', 'PAYOUT_RETURNED',
                          'UNAPPLIED_CASH_RECEIVED', 'UNAPPLIED_CASH_RETURNED', 'REVERSAL', 'CORRECTION')),
    source_key        text        NOT NULL UNIQUE CHECK (length(source_key) BETWEEN 1 AND 200),
    posting_count     smallint    NOT NULL CHECK (posting_count >= 2),
    total_amount      bigint      NOT NULL CHECK (total_amount > 0),
    created_txid      xid8        NOT NULL,
    occurred_at       timestamptz NOT NULL,
    recorded_at       timestamptz NOT NULL,
    subject_type      text        NOT NULL CHECK (subject_type IN ('payment', 'refund', 'payout', 'batch', 'bank_txn')),
    subject_id        text        NOT NULL,
    -- Restricted corrections (ADR-0011, P4). The FK to ledger.correction is added with that table.
    reverses_entry_id text        UNIQUE REFERENCES ledger.journal_entry (id),
    correction_id     text,
    CONSTRAINT journal_entry_reversal_link
        CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    CONSTRAINT journal_entry_correction_link
        CHECK ((entry_type IN ('REVERSAL', 'CORRECTION')) = (correction_id IS NOT NULL))
);

CREATE TABLE ledger.posting (
    id              text        PRIMARY KEY CHECK (id ~ '^pst_[0-9A-HJKMNP-TV-Z]{26}$'),
    entry_id        text        NOT NULL REFERENCES ledger.journal_entry (id),
    line_no         smallint    NOT NULL CHECK (line_no >= 1),
    account_code    smallint    NOT NULL REFERENCES ledger.account (code),
    side            char(1)     NOT NULL CHECK (side IN ('D', 'C')),
    amount          bigint      NOT NULL CHECK (amount > 0),
    account_version bigint      NOT NULL CHECK (account_version >= 1),
    balance_after   bigint      NOT NULL,
    recorded_at     timestamptz NOT NULL,
    UNIQUE (entry_id, line_no)
);
CREATE INDEX posting_account_code_idx ON ledger.posting (account_code);

-- The entry remembers which transaction created it; whatever the caller passed is overwritten.
CREATE FUNCTION ledger.stamp_entry_txid() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    NEW.created_txid := pg_current_xact_id();
    RETURN NEW;
END;
$$;

CREATE TRIGGER journal_entry_stamp_txid
    BEFORE INSERT ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger.stamp_entry_txid();

-- Postings can only be added in the transaction that created their entry (append attack, AC-1.6).
CREATE FUNCTION ledger.reject_posting_outside_entry_txn() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    entry_txid xid8;
BEGIN
    SELECT created_txid INTO entry_txid FROM ledger.journal_entry WHERE id = NEW.entry_id;
    IF entry_txid IS DISTINCT FROM pg_current_xact_id() THEN
        RAISE EXCEPTION 'entry % is sealed: postings can only be added in the transaction that created it',
            NEW.entry_id USING ERRCODE = 'LL002';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER posting_same_txn_as_entry
    BEFORE INSERT ON ledger.posting
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_posting_outside_entry_txn();

-- At commit (or at statement end after SET CONSTRAINTS ALL IMMEDIATE): the actual postings must
-- match the header, and debits must equal credits (INV-1, INV-4). Fired from both tables so an
-- entry with no postings at all is checked too.
CREATE FUNCTION ledger.check_entry_sealed() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    target_id   text;
    want_count  smallint;
    want_total  bigint;
    have_count  bigint;
    have_debit  bigint;
    have_credit bigint;
BEGIN
    -- Separate branches: PL/pgSQL resolves NEW.entry_id only when the posting branch runs.
    IF TG_TABLE_NAME = 'journal_entry' THEN
        target_id := NEW.id;
    ELSE
        target_id := NEW.entry_id;
    END IF;

    SELECT posting_count, total_amount INTO want_count, want_total
    FROM ledger.journal_entry WHERE id = target_id;

    SELECT count(*),
           coalesce(sum(amount) FILTER (WHERE side = 'D'), 0),
           coalesce(sum(amount) FILTER (WHERE side = 'C'), 0)
    INTO have_count, have_debit, have_credit
    FROM ledger.posting WHERE entry_id = target_id;

    IF have_count <> want_count OR have_debit <> want_total OR have_credit <> want_total THEN
        RAISE EXCEPTION 'entry % does not match its header: % postings (header %), debits %, credits %, header total %',
            target_id, have_count, want_count, have_debit, have_credit, want_total
            USING ERRCODE = 'LL003';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_sealed
    AFTER INSERT ON ledger.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_sealed();

CREATE CONSTRAINT TRIGGER posting_sealed
    AFTER INSERT ON ledger.posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_sealed();

-- app has no UPDATE, DELETE or TRUNCATE on these tables; this also stops the owner.
CREATE FUNCTION ledger.reject_ledger_rewrite() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on ledger.% is not allowed: entries are append-only, corrections are reversals (ADR-0011)',
        TG_OP, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;

CREATE TRIGGER journal_entry_no_rewrite
    BEFORE UPDATE OR DELETE ON ledger.journal_entry
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_ledger_rewrite();
CREATE TRIGGER journal_entry_no_truncate
    BEFORE TRUNCATE ON ledger.journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_ledger_rewrite();
CREATE TRIGGER posting_no_rewrite
    BEFORE UPDATE OR DELETE ON ledger.posting
    FOR EACH ROW EXECUTE FUNCTION ledger.reject_ledger_rewrite();
CREATE TRIGGER posting_no_truncate
    BEFORE TRUNCATE ON ledger.posting
    FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_ledger_rewrite();

-- Trigger functions are only for triggers; nobody calls them directly.
REVOKE ALL ON FUNCTION ledger.reject_account_attribute_change(), ledger.stamp_entry_txid(),
    ledger.reject_posting_outside_entry_txn(), ledger.check_entry_sealed(), ledger.reject_ledger_rewrite()
    FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- Read-only views (ADR-0014): readonly gets SELECT on these, never on the base tables.

CREATE VIEW ledger.v_account AS
    SELECT code, name, kind, normal_side, allow_negative, balance, version
    FROM ledger.account;

CREATE VIEW ledger.v_journal_entry AS
    SELECT id, seq, entry_type, source_key, posting_count, total_amount, occurred_at, recorded_at,
           subject_type, subject_id, reverses_entry_id, correction_id
    FROM ledger.journal_entry;

CREATE VIEW ledger.v_posting AS
    SELECT id, entry_id, line_no, account_code, side, amount, account_version, balance_after, recorded_at
    FROM ledger.posting;

-- ---------------------------------------------------------------------------------------------
-- Grants (data-dictionary section 0.1). Column lists, never whole-table-then-revoke; no TRUNCATE.

GRANT SELECT, UPDATE (touched_at) ON ledger.funds_lock TO app;
GRANT SELECT, UPDATE (balance, version) ON ledger.account TO app;
GRANT SELECT, INSERT ON ledger.journal_entry TO app;
GRANT SELECT, INSERT ON ledger.posting TO app;

GRANT SELECT ON ledger.v_account, ledger.v_journal_entry, ledger.v_posting TO readonly;
