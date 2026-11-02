CREATE TABLE recon.run (
    id text PRIMARY KEY CHECK (id ~ '^rr_[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    kind text NOT NULL CHECK (kind IN ('PROCESSOR', 'BANK', 'AGING')),
    status text NOT NULL CHECK (status IN ('RUNNING', 'FINISHED', 'FAILED')),
    input_file_ids jsonb NOT NULL CHECK (jsonb_typeof(input_file_ids) = 'array'),
    input_hashes jsonb NOT NULL CHECK (jsonb_typeof(input_hashes) = 'object'),
    rule_version text NOT NULL CHECK (rule_version ~ '[^[:space:]]'),
    cutoff_at timestamptz NOT NULL,
    evidence_as_of timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    summary jsonb NOT NULL CHECK (jsonb_typeof(summary) = 'object'),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT run_cutoff_evidence CHECK (cutoff_at <= evidence_as_of)
);

ALTER TABLE recon.issue
    ADD COLUMN fingerprint text CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    ADD COLUMN reopen_count integer NOT NULL DEFAULT 0 CHECK (reopen_count >= 0),
    ADD CONSTRAINT issue_first_run_fk FOREIGN KEY (first_run_id) REFERENCES recon.run(id),
    ADD CONSTRAINT issue_last_run_fk FOREIGN KEY (last_run_id) REFERENCES recon.run(id);

CREATE TABLE recon.finding (
    id text PRIMARY KEY CHECK (id ~ '^fnd_[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    run_id text NOT NULL REFERENCES recon.run(id),
    category text NOT NULL CHECK (category ~ '[^[:space:]]'),
    subject_type text NOT NULL CHECK (subject_type ~ '[^[:space:]]'),
    subject_id text NOT NULL CHECK (subject_id ~ '[^[:space:]]'),
    expected jsonb,
    actual jsonb,
    evidence_refs jsonb NOT NULL CHECK (jsonb_typeof(evidence_refs) = 'array'),
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    due_at timestamptz,
    issue_id text NOT NULL REFERENCES recon.issue(id),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT finding_identity UNIQUE (run_id, category, subject_type, subject_id),
    CONSTRAINT finding_due CHECK ((category = 'TIMING_DIFFERENCE') = (due_at IS NOT NULL))
);

CREATE TABLE recon.issue_transition (
    id text PRIMARY KEY CHECK (id ~ '^itr_[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    issue_id text NOT NULL REFERENCES recon.issue(id),
    from_status text CHECK (from_status IN ('OPEN', 'EXPLAINED', 'ACCEPTED', 'RESOLVED')),
    to_status text NOT NULL CHECK (to_status IN ('OPEN', 'EXPLAINED', 'ACCEPTED', 'RESOLVED')),
    run_id text REFERENCES recon.run(id),
    reason text NOT NULL CHECK (reason IN ('DETECTED', 'REOPENED', 'EXPLAINED', 'OPERATOR')),
    explanation text NOT NULL CHECK (explanation ~ '[^[:space:]]'),
    evidence_refs jsonb NOT NULL CHECK (jsonb_typeof(evidence_refs) = 'array'),
    recorded_at timestamptz NOT NULL
);

CREATE FUNCTION recon.reject_history_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on %.% is not allowed: reconciliation history is append-only',
        TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = 'LL004';
END;
$$;
REVOKE ALL ON FUNCTION recon.reject_history_rewrite() FROM PUBLIC;
CREATE TRIGGER finding_no_rewrite BEFORE UPDATE OR DELETE ON recon.finding
    FOR EACH ROW EXECUTE FUNCTION recon.reject_history_rewrite();
CREATE TRIGGER finding_no_truncate BEFORE TRUNCATE ON recon.finding
    FOR EACH STATEMENT EXECUTE FUNCTION recon.reject_history_rewrite();
CREATE TRIGGER issue_transition_no_rewrite BEFORE UPDATE OR DELETE ON recon.issue_transition
    FOR EACH ROW EXECUTE FUNCTION recon.reject_history_rewrite();
CREATE TRIGGER issue_transition_no_truncate BEFORE TRUNCATE ON recon.issue_transition
    FOR EACH STATEMENT EXECUTE FUNCTION recon.reject_history_rewrite();

CREATE VIEW recon.v_run AS
    SELECT id, kind, status, input_file_ids, input_hashes, rule_version, cutoff_at, evidence_as_of,
        started_at, finished_at, summary, recorded_at FROM recon.run;
CREATE VIEW recon.v_finding AS
    SELECT id, run_id, category, subject_type, subject_id, expected, actual, evidence_refs,
        fingerprint, due_at, issue_id, recorded_at FROM recon.finding;
CREATE VIEW recon.v_issue_transition AS
    SELECT id, issue_id, from_status, to_status, run_id, reason, explanation, evidence_refs, recorded_at
    FROM recon.issue_transition;
CREATE OR REPLACE VIEW recon.v_issue AS
    SELECT id, category, subject_type, subject_id, detected_by, first_run_id, last_run_id,
        status, expected, actual, evidence_refs, explanation, resolution_correction_id,
        recorded_at, updated_at, fingerprint, reopen_count FROM recon.issue;

CREATE VIEW recon.v_input_evidence AS
    SELECT id, 'BANK'::text AS kind, sha256, received_at AS observed_at, recorded_at,
        status = 'ACCEPTED' AS ready FROM io.imported_file WHERE kind = 'BANK_STATEMENT'
    UNION ALL
    SELECT 'fetch:' || id, 'PROCESSOR', response_sha256, fetched_at, recorded_at, complete
        FROM io.processor_fetch WHERE purpose = 'RECON_SNAPSHOT';

GRANT SELECT, INSERT, UPDATE (status, finished_at, summary) ON recon.run TO app;
GRANT UPDATE (fingerprint, reopen_count) ON recon.issue TO app;
GRANT SELECT, INSERT ON recon.finding, recon.issue_transition TO app;
GRANT SELECT ON recon.v_input_evidence TO app, readonly;
GRANT SELECT ON recon.v_run, recon.v_finding, recon.v_issue_transition TO readonly;
