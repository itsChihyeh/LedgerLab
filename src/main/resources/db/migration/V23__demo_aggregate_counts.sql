-- P7 T02: the public demo keeps only per-day aggregates (AC-9.6): no IP, user agent or cookie, ever.
-- The tables exist in every profile (migrations are profile-blind); only the demo profile writes to them.
CREATE SCHEMA demo;

GRANT USAGE ON SCHEMA demo TO app, readonly;

CREATE TABLE demo.action_count (
    day         date        NOT NULL,
    action      text        NOT NULL CHECK (action IN
        ('pay', 'replay-webhook', 'batch-first', 'double-refund', 'drop-refund-response',
         'bank-drop-receipt', 'kill-worker', 'bad-statement', 'over-refund-rejected')),
    count       bigint      NOT NULL CHECK (count > 0),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (day, action)
);

CREATE TABLE demo.page_view (
    day         date        PRIMARY KEY,
    count       bigint      NOT NULL CHECK (count > 0),
    recorded_at timestamptz NOT NULL
);

-- INSERT ... ON CONFLICT DO UPDATE needs SELECT and UPDATE on the counter column only; the day, the action
-- and the first-write time can never be rewritten, and nothing can be deleted.
GRANT SELECT, INSERT, UPDATE (count) ON demo.action_count, demo.page_view TO app;

CREATE VIEW demo.v_action_count AS
    SELECT day, action, count, recorded_at FROM demo.action_count;
CREATE VIEW demo.v_page_view AS
    SELECT day, count, recorded_at FROM demo.page_view;

-- Inbox status changes without payloads or errors, so the demo can count events that were parked and then
-- resolved. migrator owns the view; readonly still has no privileges on io.
CREATE VIEW core.v_inbox_status_history AS
    SELECT id, event_id, from_status, to_status, result, parked_on, recorded_at
    FROM io.inbox_status_history;

GRANT SELECT ON demo.v_action_count, demo.v_page_view, core.v_inbox_status_history TO readonly;
