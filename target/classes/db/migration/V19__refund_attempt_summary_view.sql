-- T12: readonly gets only audit identifiers, times and result codes.
-- migrator owns the view; app keeps its existing base-table grants.
CREATE VIEW core.v_refund_attempt_summary AS
    SELECT c.subject_id AS refund_id, a.command_id, a.attempt_no,
           a.sent_at, a.finished_at, a.recorded_at, a.response_status,
           CASE WHEN a.response_status IS NULL THEN 'NO_RESPONSE'
                ELSE 'HTTP_' || a.response_status::text END AS result_code
    FROM io.outbound_attempt a
    JOIN io.outbound_command c ON c.id = a.command_id
    WHERE c.kind = 'SUBMIT_REFUND';

GRANT SELECT ON core.v_refund_attempt_summary TO readonly;
