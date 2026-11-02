CREATE TABLE io.imported_file (
    id text PRIMARY KEY CHECK (id ~ '^file_[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    kind text NOT NULL CHECK (kind IN ('PROCESSOR_STATEMENT', 'BANK_STATEMENT')),
    source_name text NOT NULL,
    sha256 text NOT NULL UNIQUE,
    statement_date date,
    version integer CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('ACCEPTED', 'REJECTED')),
    reject_reason text,
    raw bytea NOT NULL,
    received_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT imported_file_hash CHECK (sha256 = encode(sha256(raw), 'hex')),
    CONSTRAINT imported_file_result CHECK (
        (status = 'ACCEPTED' AND reject_reason IS NULL AND statement_date IS NOT NULL AND version IS NOT NULL)
        OR (status = 'REJECTED' AND reject_reason IS NOT NULL
            AND reject_reason IN ('FILE_INCOMPLETE', 'BALANCE_MISMATCH')))
);

CREATE TABLE recon.bank_statement (
    file_id text PRIMARY KEY REFERENCES io.imported_file(id),
    account_ref text NOT NULL CHECK (account_ref = 'SIM-CAD-001'),
    currency text NOT NULL CHECK (currency = 'CAD'),
    opening_balance bigint NOT NULL CHECK (opening_balance BETWEEN -9007199254740991 AND 9007199254740991),
    closing_balance bigint NOT NULL CHECK (closing_balance BETWEEN -9007199254740991 AND 9007199254740991),
    period_start date NOT NULL,
    period_end date NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT bank_statement_period CHECK (period_start = period_end)
);

CREATE TABLE recon.bank_line (
    import_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    bank_txn_id text NOT NULL CHECK (btrim(bank_txn_id) <> ''),
    version integer NOT NULL CHECK (version > 0),
    file_id text NOT NULL REFERENCES recon.bank_statement(file_id),
    direction text NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    currency text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status text NOT NULL CHECK (status IN ('BOOKED', 'PENDING')),
    amount bigint NOT NULL CHECK (amount BETWEEN 1 AND 99999999),
    reference text NOT NULL,
    return_of text,
    reason_code text,
    booking_date date NOT NULL,
    value_date date NOT NULL,
    memo text NOT NULL,
    match_status text NOT NULL CHECK (match_status IN
        ('WAITING', 'MATCHED', 'UNAPPLIED', 'UNMATCHED_NO_POSTING', 'IGNORED_PENDING')),
    matched_batch_id text REFERENCES core.settlement_batch(batch_id),
    wait_until timestamptz,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (bank_txn_id, version),
    CONSTRAINT bank_line_match CHECK (
        (match_status = 'MATCHED') = (matched_batch_id IS NOT NULL)),
    CONSTRAINT bank_line_pending CHECK (
        (status = 'PENDING') = (match_status = 'IGNORED_PENDING')),
    CONSTRAINT bank_line_direction CHECK (
        match_status NOT IN ('WAITING', 'MATCHED', 'UNAPPLIED') OR direction = 'CREDIT'),
    CONSTRAINT bank_line_wait CHECK (match_status <> 'WAITING' OR wait_until IS NOT NULL),
    CONSTRAINT bank_line_matching_currency CHECK (match_status NOT IN ('WAITING', 'MATCHED') OR currency = 'CAD')
);
CREATE UNIQUE INDEX bank_line_one_match_per_batch ON recon.bank_line(matched_batch_id)
    WHERE match_status = 'MATCHED';
CREATE INDEX bank_line_waiting ON recon.bank_line(wait_until, file_id)
    WHERE match_status = 'WAITING';

CREATE FUNCTION recon.require_accepted_bank_file() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM io.imported_file WHERE id = NEW.file_id
        AND status = 'ACCEPTED' AND kind = 'BANK_STATEMENT'
        AND statement_date = NEW.period_start AND version = NEW.version) THEN
        RAISE EXCEPTION 'bank statement requires an accepted matching file' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER bank_statement_accepted BEFORE INSERT ON recon.bank_statement
    FOR EACH ROW EXECUTE FUNCTION recon.require_accepted_bank_file();
REVOKE ALL ON FUNCTION recon.require_accepted_bank_file() FROM PUBLIC;

CREATE FUNCTION recon.protect_bank_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'bank_line' THEN
        IF (to_jsonb(NEW) - 'match_status' - 'matched_batch_id') =
           (to_jsonb(OLD) - 'match_status' - 'matched_batch_id') THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'bank evidence is immutable' USING ERRCODE = 'LL004';
END;
$$;
CREATE TRIGGER imported_file_no_rewrite BEFORE UPDATE OR DELETE ON io.imported_file
    FOR EACH ROW EXECUTE FUNCTION recon.protect_bank_evidence();
CREATE TRIGGER imported_file_no_truncate BEFORE TRUNCATE ON io.imported_file
    FOR EACH STATEMENT EXECUTE FUNCTION recon.protect_bank_evidence();
CREATE TRIGGER bank_statement_no_rewrite BEFORE UPDATE OR DELETE ON recon.bank_statement
    FOR EACH ROW EXECUTE FUNCTION recon.protect_bank_evidence();
CREATE TRIGGER bank_statement_no_truncate BEFORE TRUNCATE ON recon.bank_statement
    FOR EACH STATEMENT EXECUTE FUNCTION recon.protect_bank_evidence();
CREATE TRIGGER bank_line_no_rewrite BEFORE UPDATE OR DELETE ON recon.bank_line
    FOR EACH ROW EXECUTE FUNCTION recon.protect_bank_evidence();
CREATE TRIGGER bank_line_no_truncate BEFORE TRUNCATE ON recon.bank_line
    FOR EACH STATEMENT EXECUTE FUNCTION recon.protect_bank_evidence();
REVOKE ALL ON FUNCTION recon.protect_bank_evidence() FROM PUBLIC;

CREATE VIEW recon.v_bank_statement AS
    SELECT file_id, account_ref, currency, opening_balance, closing_balance,
        period_start, period_end, version, recorded_at FROM recon.bank_statement;
CREATE VIEW recon.v_bank_line AS
    SELECT import_order, bank_txn_id, version, file_id, direction, currency, status, amount, reference,
        return_of, reason_code, booking_date, value_date, memo, match_status,
        matched_batch_id, wait_until, recorded_at FROM recon.bank_line;
GRANT SELECT, INSERT ON io.imported_file, recon.bank_statement, recon.bank_line TO app;
GRANT UPDATE (match_status, matched_batch_id) ON recon.bank_line TO app;
GRANT SELECT ON recon.v_bank_statement, recon.v_bank_line TO readonly;
