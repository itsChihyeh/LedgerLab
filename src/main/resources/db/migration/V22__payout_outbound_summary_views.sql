-- P5 T04: migrator-owned audit views; readonly never receives io privileges.
-- app retains its existing base-table grants and receives no new privileges.
CREATE VIEW core.v_payout_command_summary AS
    SELECT id AS command_id, subject_id AS payout_id, kind, status, attempts,
           next_attempt_at, first_sent_at, created_at, recorded_at, last_outcome
    FROM io.outbound_command
    WHERE kind IN ('SUBMIT_PAYOUT', 'QUERY_PAYOUT');

CREATE VIEW core.v_payout_attempt_summary AS
    SELECT c.subject_id AS payout_id, a.command_id, a.attempt_no,
           a.sent_at, a.finished_at, a.recorded_at, a.response_status,
           CASE WHEN a.response_status IS NULL THEN 'NO_RESPONSE'
                ELSE 'HTTP_' || a.response_status::text END AS result_code
    FROM io.outbound_attempt a
    JOIN io.outbound_command c ON c.id = a.command_id
    WHERE c.kind IN ('SUBMIT_PAYOUT', 'QUERY_PAYOUT');

GRANT SELECT ON core.v_payout_command_summary, core.v_payout_attempt_summary TO readonly;
