-- Eventuate Tram common tables — created automatically on service startup.
-- All four services need these.

CREATE TABLE IF NOT EXISTS message (
    id                VARCHAR(767) PRIMARY KEY,
    destination       TEXT         NOT NULL,
    headers           TEXT         NOT NULL,
    payload           TEXT         NOT NULL,
    published         SMALLINT     DEFAULT 0,
    message_partition SMALLINT,
    creation_time     BIGINT
);

CREATE INDEX IF NOT EXISTS message_published_idx ON message (published, creation_time);

CREATE TABLE IF NOT EXISTS received_messages (
    consumer_id   VARCHAR(255) NOT NULL,
    message_id    VARCHAR(767) NOT NULL,
    creation_time BIGINT,
    PRIMARY KEY (consumer_id, message_id)
);
