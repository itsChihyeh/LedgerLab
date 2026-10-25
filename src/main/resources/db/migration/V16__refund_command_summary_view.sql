-- T05: expose command progress through a narrow readonly view, never the io schema.
-- migrator owns the view; app retains its existing base-table privileges.
CREATE VIEW core.v_refund_command_summary AS
    SELECT id AS command_id, subject_id AS refund_id, status, attempts,
           next_attempt_at, first_sent_at, created_at, recorded_at, last_outcome
    FROM io.outbound_command
    WHERE kind = 'SUBMIT_REFUND';

GRANT SELECT ON core.v_refund_command_summary TO readonly;
