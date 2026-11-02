-- Preserve the cause and the exact fetch used by historical HELD states.
ALTER TABLE core.batch_transition
    ADD COLUMN hold_reason text,
    ADD COLUMN lines_fetch_id bigint REFERENCES io.processor_fetch(id);
ALTER TABLE core.batch_transition ADD CONSTRAINT batch_transition_held_reason
    CHECK (to_status <> 'HELD' OR hold_reason IS NOT NULL) NOT VALID;

CREATE OR REPLACE VIEW core.v_batch_transition AS
    SELECT id, subject_id, from_status, to_status, evidence_type, evidence_id, occurred_at, recorded_at,
        hold_reason, lines_fetch_id FROM core.batch_transition;
-- migrator owns these objects; existing app SELECT/INSERT covers the added columns.
-- No UPDATE/DELETE/TRUNCATE is granted, and readonly has no base-table access.
GRANT SELECT ON core.v_batch_transition TO readonly;
