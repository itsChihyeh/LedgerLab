-- P10 T03: the inbox backlog metrics need status, age and due time, but readonly has no io privileges and
-- core.v_event hides next_attempt_at. This migrator-owned view exposes only those three columns for the two
-- unfinished statuses: no event id, payload, signature, lease or error text. app gets no new privileges.
CREATE VIEW core.v_inbox_backlog AS
    SELECT status, received_at, next_attempt_at
    FROM io.inbox_event
    WHERE status IN ('RECEIVED', 'PARKED');

GRANT SELECT ON core.v_inbox_backlog TO readonly;
