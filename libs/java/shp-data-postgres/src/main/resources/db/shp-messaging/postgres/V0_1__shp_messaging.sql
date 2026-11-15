-- Bảng nền tảng nhắn tin (02-db-scheduling.md). Thêm vào Flyway của service:
--   spring.flyway.locations=classpath:db/migration,classpath:db/shp-messaging/postgres
-- Version 0.1 chạy trước mọi migration V1+ của service.
CREATE TABLE IF NOT EXISTS outbox (
    id            uuid        PRIMARY KEY,
    event_type    text        NOT NULL,
    subject       text        NOT NULL,
    payload       jsonb       NOT NULL,
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz,
    attempts      smallint    NOT NULL DEFAULT 0,
    last_error    text
);
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;

CREATE TABLE IF NOT EXISTS inbox (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);

CREATE TABLE IF NOT EXISTS idempotency_key (
    subject        text        NOT NULL,
    key            text        NOT NULL CHECK (length(key) BETWEEN 8 AND 128),
    operation      text        NOT NULL,
    request_hash   bytea       NOT NULL,
    resource_id    uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    PRIMARY KEY (subject, key)
);
CREATE INDEX IF NOT EXISTS ix_idempotency_created ON idempotency_key (created_at);
