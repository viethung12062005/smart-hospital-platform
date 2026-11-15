SET search_path = scheduling, public;

-- Appointment (FHIR Appointment)
CREATE TABLE appointment (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    status           text        NOT NULL CHECK (status IN ('booked', 'arrived', 'fulfilled', 'cancelled', 'noshow')),
    slot_id          uuid        NOT NULL REFERENCES slot (id),
    patient_id       uuid        NOT NULL,
    practitioner_id  uuid        NOT NULL,
    specialty        text        NOT NULL,
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    channel          text        NOT NULL CHECK (channel IN ('web', 'chatbot', 'staff')),
    booked_by        text        NOT NULL,                 -- OIDC subject of the booker
    cancel_reason    text,
    rescheduled_from uuid        REFERENCES appointment (id),
    content          jsonb       NOT NULL,
    CONSTRAINT ck_appointment_cancel_reason CHECK ((status = 'cancelled') = (cancel_reason IS NOT NULL))
);
-- BR-01 at the DB layer: at most one active appointment per slot (final safeguard against double-booking)
CREATE UNIQUE INDEX ux_appointment_one_active_per_slot
    ON appointment (slot_id) WHERE status IN ('booked', 'arrived', 'fulfilled');
CREATE INDEX ix_appointment_patient_start      ON appointment (patient_id, start_at DESC);
CREATE INDEX ix_appointment_practitioner_start ON appointment (practitioner_id, start_at);
-- No-show marking job only scans appointments still 'booked'
CREATE INDEX ix_appointment_booked_end         ON appointment (end_at) WHERE status = 'booked';

-- Version history (FHIR _history) for Schedule and Appointment
CREATE TABLE resource_history (
    resource_type text        NOT NULL CHECK (resource_type IN ('Schedule', 'Appointment')),
    id            uuid        NOT NULL,
    version_id    integer     NOT NULL,
    last_updated  timestamptz NOT NULL,
    operation     text        NOT NULL CHECK (operation IN ('create', 'update')),
    changed_by    text        NOT NULL,
    content       jsonb       NOT NULL,
    PRIMARY KEY (resource_type, id, version_id)
);

-- Transactional outbox: event written in the same transaction as data changes
CREATE TABLE IF NOT EXISTS outbox (
    id            uuid        PRIMARY KEY,                -- = CloudEvents id
    event_type    text        NOT NULL,                   -- for example shp.scheduling.appointment-booked.v1
    subject       text        NOT NULL,                   -- for example Appointment/{id}
    payload       jsonb       NOT NULL,                   -- full CloudEvent
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz,
    attempts      smallint    NOT NULL DEFAULT 0,
    last_error    text
);
CREATE INDEX IF NOT EXISTS ix_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;

-- Idempotency for $book, $cancel, $reschedule
CREATE TABLE IF NOT EXISTS idempotency_key (
    subject        text        NOT NULL,                  -- OIDC subject of the caller
    key            text        NOT NULL CHECK (length(key) BETWEEN 8 AND 128),
    operation      text        NOT NULL,                  -- for example 'Appointment/$book'
    request_hash   bytea       NOT NULL,                  -- SHA-256 of the canonicalized body
    resource_id    uuid        NOT NULL,                  -- appointment ID allocated before processing
    created_at     timestamptz NOT NULL,
    PRIMARY KEY (subject, key)
);
CREATE INDEX IF NOT EXISTS ix_idempotency_created ON idempotency_key (created_at);

-- Inbox: prevent duplicate processing of incoming events (for example EncounterFinished)
CREATE TABLE IF NOT EXISTS inbox (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
