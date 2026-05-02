-- Eventuate Tram saga tables — created automatically on order-service startup.
-- Only order-service (the saga orchestrator) needs these.

CREATE TABLE IF NOT EXISTS saga_instance (
    saga_type       VARCHAR(255) NOT NULL,
    saga_id         VARCHAR(255) NOT NULL,
    state_name      VARCHAR(255),
    last_request_id VARCHAR(255),
    end_state       BOOLEAN      DEFAULT FALSE,
    compensating    BOOLEAN      DEFAULT FALSE,
    saga_data_type  VARCHAR(255),
    saga_data       JSON,
    PRIMARY KEY (saga_type, saga_id)
);

CREATE TABLE IF NOT EXISTS saga_instance_participants (
    saga_type   VARCHAR(255) NOT NULL,
    saga_id     VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    resource    VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS saga_instance_participants_idx
    ON saga_instance_participants (saga_type, saga_id);

CREATE TABLE IF NOT EXISTS saga_lock_table (
    target    VARCHAR(255) PRIMARY KEY,
    saga_type VARCHAR(255),
    saga_id   VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS saga_stash_instance (
    saga_type VARCHAR(255) NOT NULL,
    saga_id   VARCHAR(255) NOT NULL,
    messages  TEXT,
    PRIMARY KEY (saga_type, saga_id)
);
