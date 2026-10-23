-- T13b: safe event evidence for investigation, owned by migrator like V5.
-- Use owner permissions; readonly still has no USAGE or table privileges on io.
CREATE VIEW core.v_batch_delivery AS
    SELECT d.id, d.event_id, e.event_type, d.outcome, d.received_at, e.status, e.result,
           convert_from(e.payload, 'UTF8')::json #>> '{data,object,id}' AS batch_id
    FROM io.webhook_delivery d JOIN io.inbox_event e ON e.event_id = d.event_id
    WHERE e.event_type LIKE 'payout.%';

CREATE VIEW core.v_event AS
    SELECT e.event_id, e.event_type, e.api_version, e.livemode, e.occurred_at, e.received_at,
           e.status, e.result,
           CASE WHEN e.event_type LIKE 'charge.%' THEN 'payment'
                WHEN e.event_type LIKE 'payout.%' THEN 'batch'
           END AS subject,
           CASE WHEN e.event_type LIKE 'charge.%' OR e.event_type LIKE 'payout.%'
                THEN convert_from(e.payload, 'UTF8')::json #>> '{data,object,id}'
           END AS subject_id
    FROM io.inbox_event e;

CREATE VIEW core.v_event_delivery AS
    SELECT d.id, d.event_id, e.event_type, d.outcome, d.received_at, e.status, e.result,
           e.subject
    FROM io.webhook_delivery d JOIN core.v_event e ON e.event_id = d.event_id;

-- No new app privileges. No raw payload, signature, hash or conflict content.
GRANT SELECT ON core.v_batch_delivery, core.v_event, core.v_event_delivery TO readonly;
