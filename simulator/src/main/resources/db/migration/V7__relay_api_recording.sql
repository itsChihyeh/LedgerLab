CREATE TABLE sim_chaos.api_exchange (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    received_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    method text NOT NULL CHECK (method ~ '^[A-Z]+$'),
    path text NOT NULL CHECK (path LIKE '/%' AND position('?' in path) = 0),
    query_parameter_names jsonb NOT NULL CHECK (jsonb_typeof(query_parameter_names) = 'array'),
    upstream_status integer CHECK (upstream_status BETWEEN 100 AND 599),
    action text NOT NULL CHECK (action IN ('PASS', 'DROP_RESPONSE', 'FAIL_BEFORE_SEND')),
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    request_sha256 text NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    response_sha256 text CHECK (response_sha256 ~ '^[0-9a-f]{64}$'),
    request_body bytea,
    response_body bytea,
    CHECK (action <> 'FAIL_BEFORE_SEND' OR upstream_status IS NULL),
    CHECK (response_body IS NULL OR response_sha256 IS NOT NULL)
);
CREATE INDEX api_exchange_received_at ON sim_chaos.api_exchange (received_at);

CREATE TABLE sim_chaos.inbound_recording (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id text,
    event_type text,
    received_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    body bytea NOT NULL
);
REVOKE ALL ON sim_chaos.api_exchange, sim_chaos.inbound_recording
    FROM PUBLIC, migrator, app, readonly, simulator;
GRANT INSERT, SELECT ON sim_chaos.api_exchange, sim_chaos.inbound_recording TO simulator;
REVOKE ALL ON SEQUENCE sim_chaos.api_exchange_id_seq, sim_chaos.inbound_recording_id_seq
    FROM PUBLIC, migrator, app, readonly, simulator;
GRANT USAGE, SELECT ON SEQUENCE sim_chaos.api_exchange_id_seq, sim_chaos.inbound_recording_id_seq TO simulator;
