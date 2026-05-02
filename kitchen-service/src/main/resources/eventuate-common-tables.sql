-- Eventuate Tram common tables — created automatically on service startup.
-- All four services need these.

CREATE TABLE public.message (
  id 				character varying(767) 		NULL,
  destination 		text 						NOT NULL,
  headers 			text 						NOT NULL,
  payload 			text 						NOT NULL,
  published 			smallint 					NULL,
  message_partition smallint 					NULL,
  creation_time 		bigint 						NULL,
  dbid 				bigint 						NOT NULL
);

CREATE INDEX IF NOT EXISTS message_published_idx ON message (published, creation_time);

CREATE TABLE IF NOT EXISTS received_messages (
    consumer_id   VARCHAR(255) NOT NULL,
    message_id    VARCHAR(767) NOT NULL,
    creation_time BIGINT,
    PRIMARY KEY (consumer_id, message_id)
);
