ALTER TABLE io.imported_file ADD COLUMN processor_fetch_ids jsonb NOT NULL DEFAULT '[]'
    CHECK (jsonb_typeof(processor_fetch_ids) = 'array');
ALTER TABLE io.imported_file ADD COLUMN processor_source jsonb NOT NULL DEFAULT '{}'
    CHECK (jsonb_typeof(processor_source) = 'object');

CREATE TABLE recon.statement_line (
    file_id text NOT NULL REFERENCES io.imported_file(id),
    line_seq integer NOT NULL CHECK (line_seq > 0),
    statement_line_id text NOT NULL CHECK (statement_line_id ~ '^txn_.+'),
    line_type text NOT NULL CHECK (line_type IN ('PAYMENT', 'REFUND', 'PAYOUT_FAILURE')),
    processor_ref text NOT NULL CHECK (btrim(processor_ref) <> ''),
    batch_id text NOT NULL CHECK (batch_id ~ '^po_.+'),
    gross bigint NOT NULL,
    fee bigint NOT NULL CHECK (fee BETWEEN 0 AND 99999999),
    net bigint NOT NULL CHECK (net BETWEEN -99999999 AND 99999999),
    occurred_at timestamptz NOT NULL,
    settlement_date date NOT NULL,
    memo text NOT NULL,
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (file_id, line_seq),
    CHECK ((line_type = 'REFUND' AND gross BETWEEN -99999999 AND -1)
        OR (line_type IN ('PAYMENT', 'PAYOUT_FAILURE') AND gross BETWEEN 1 AND 99999999)),
    CHECK (net = gross - fee)
);
CREATE INDEX statement_line_identity ON recon.statement_line(statement_line_id);
CREATE FUNCTION recon.require_processor_file() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM io.imported_file WHERE id = NEW.file_id
        AND kind = 'PROCESSOR_STATEMENT' AND status = 'ACCEPTED') THEN
        RAISE EXCEPTION 'statement line requires an accepted processor snapshot' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION recon.require_processor_file() FROM PUBLIC;
CREATE TRIGGER statement_line_accepted BEFORE INSERT ON recon.statement_line
    FOR EACH ROW EXECUTE FUNCTION recon.require_processor_file();
CREATE TRIGGER statement_line_no_rewrite BEFORE UPDATE OR DELETE ON recon.statement_line
    FOR EACH ROW EXECUTE FUNCTION recon.reject_history_rewrite();
CREATE TRIGGER statement_line_no_truncate BEFORE TRUNCATE ON recon.statement_line
    FOR EACH STATEMENT EXECUTE FUNCTION recon.reject_history_rewrite();
CREATE VIEW recon.v_statement_line AS
    SELECT file_id, line_seq, statement_line_id, line_type, processor_ref, batch_id,
        gross, fee, net, occurred_at, settlement_date, memo, recorded_at FROM recon.statement_line;
CREATE VIEW recon.v_processor_snapshot AS
    SELECT id AS file_id, received_at AS fetched_at, recorded_at,
        convert_from(raw, 'UTF8')::jsonb -> 'payouts' AS payouts
    FROM io.imported_file WHERE kind = 'PROCESSOR_STATEMENT' AND status = 'ACCEPTED';
CREATE OR REPLACE VIEW recon.v_input_evidence AS
    SELECT id, 'BANK'::text AS kind, sha256, received_at AS observed_at, recorded_at, status = 'ACCEPTED' AS ready
    FROM io.imported_file WHERE kind = 'BANK_STATEMENT'
    UNION ALL
    SELECT id, 'PROCESSOR', sha256, received_at, recorded_at, status = 'ACCEPTED'
    FROM io.imported_file WHERE kind = 'PROCESSOR_STATEMENT';
GRANT SELECT, INSERT ON recon.statement_line TO app;
GRANT SELECT ON recon.v_statement_line TO readonly;
GRANT SELECT ON recon.v_processor_snapshot TO app, readonly;
