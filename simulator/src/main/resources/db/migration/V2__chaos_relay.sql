CREATE TABLE sim_chaos.relay_log (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id uuid NOT NULL,
    event_id text,
    action text NOT NULL,
    attempt_no integer NOT NULL,
    http_status integer,
    error text,
    recorded_at timestamptz NOT NULL,
    payload_sha256 text NOT NULL,
    CONSTRAINT relay_attempt_unique UNIQUE (receipt_id, attempt_no),
    CONSTRAINT relay_action CHECK (action IN ('PASS', 'DUPLICATE', 'DELAY', 'HOLD_AND_REORDER', 'DROP', 'HOLD_TIMEOUT_RELEASE')),
    CONSTRAINT relay_attempt_nonnegative CHECK (attempt_no >= 0),
    CONSTRAINT relay_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT relay_http_status CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT relay_result CHECK (
        (attempt_no = 0 AND http_status IS NULL AND error IS NULL AND action <> 'HOLD_TIMEOUT_RELEASE') OR
        (attempt_no > 0 AND ((http_status IS NULL) <> (error IS NULL)) AND action <> 'DROP')
    )
);
REVOKE ALL ON sim_chaos.relay_log FROM PUBLIC, migrator, app, readonly;
REVOKE ALL ON SEQUENCE sim_chaos.relay_log_id_seq FROM PUBLIC, migrator, app, readonly;
