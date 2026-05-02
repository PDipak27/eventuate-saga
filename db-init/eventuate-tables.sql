-- ============================================================
-- Eventuate Tram required tables
-- Run this script in ALL FOUR databases:
--   orderdb, consumerdb, kitchendb, accountingdb
-- Hibernate will auto-create domain tables (orders, consumers,
-- tickets, accounts) via ddl-auto=update on first startup.
-- ============================================================

-- Transactional outbox: domain changes + messages written atomically here.
-- CDC service polls this table and publishes rows to RabbitMQ.
CREATE TABLE IF NOT EXISTS message (
    id               VARCHAR(767) PRIMARY KEY,
    destination      TEXT         NOT NULL,
    headers          TEXT         NOT NULL,
    payload          TEXT         NOT NULL,
    published        SMALLINT     DEFAULT 0,
    message_partition SMALLINT,
    creation_time    BIGINT
);

CREATE INDEX IF NOT EXISTS message_published_idx ON message (published, creation_time);

-- Deduplication: Eventuate writes (consumer_id, message_id) here
-- in the same transaction as the command handler's domain update.
-- On retry, the handler checks this table first — if found, skips.
CREATE TABLE IF NOT EXISTS received_messages (
    consumer_id   VARCHAR(255) NOT NULL,
    message_id    VARCHAR(767) NOT NULL,
    creation_time BIGINT,
    PRIMARY KEY (consumer_id, message_id)
);

-- ============================================================
-- Saga tables — required in orderdb ONLY (saga orchestrator lives there).
-- Run the block below only against orderdb.
-- ============================================================

-- Persists the saga instance state between steps.
-- saga_data column holds JSON of CreateOrderSagaData.
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

-- Tracks which channels/resources a saga has sent commands to,
-- used for routing replies back to the correct saga instance.
CREATE TABLE IF NOT EXISTS saga_instance_participants (
    saga_type   VARCHAR(255) NOT NULL,
    saga_id     VARCHAR(255) NOT NULL,
    destination VARCHAR(255) NOT NULL,
    resource    VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS saga_instance_participants_idx
    ON saga_instance_participants (saga_type, saga_id);

-- Optimistic lock table used by Eventuate for saga state updates.
CREATE TABLE IF NOT EXISTS saga_lock_table (
    target    VARCHAR(255) PRIMARY KEY,
    saga_type VARCHAR(255),
    saga_id   VARCHAR(255)
);

-- Outbox for saga command messages (separate from domain outbox for clarity).
CREATE TABLE IF NOT EXISTS saga_stash_instance (
    saga_type VARCHAR(255) NOT NULL,
    saga_id   VARCHAR(255) NOT NULL,
    messages  TEXT,
    PRIMARY KEY (saga_type, saga_id)
);
