CREATE EXTENSION IF NOT EXISTS btree_gist;          -- for exclusion constraint (uuid =, range &&)
CREATE SCHEMA IF NOT EXISTS scheduling;
SET search_path = scheduling, public;

-- A doctor's working schedule for one specialty (FHIR Schedule)
CREATE TABLE schedule (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    active           boolean     NOT NULL,
    practitioner_id  uuid        NOT NULL,
    specialty        text        NOT NULL,
    horizon_start    date        NOT NULL,
    horizon_end      date        NOT NULL,
    content          jsonb       NOT NULL,
    CONSTRAINT ck_schedule_horizon CHECK (horizon_end >= horizon_start)
);
-- Each doctor has at most 1 active Schedule per specialty
CREATE UNIQUE INDEX ux_schedule_active_practitioner_specialty
    ON schedule (practitioner_id, specialty) WHERE active;

-- Weekly schedule template (not FHIR) used to generate Slots
CREATE TABLE schedule_template (
    id            uuid        PRIMARY KEY,
    schedule_id   uuid        NOT NULL REFERENCES schedule (id),
    weekday       smallint    NOT NULL CHECK (weekday BETWEEN 1 AND 7),   -- ISO 8601: 1 = Monday
    start_time    time        NOT NULL,                                   -- local time Asia/Ho_Chi_Minh
    end_time      time        NOT NULL,
    slot_minutes  smallint    NOT NULL CHECK (slot_minutes IN (10, 15, 20, 30)),
    valid_from    date        NOT NULL,
    valid_to      date,
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    CONSTRAINT ck_template_time  CHECK (end_time > start_time),
    CONSTRAINT ck_template_valid CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_template_schedule ON schedule_template (schedule_id);

-- Examination time windows (FHIR Slot)
CREATE TABLE slot (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    schedule_id      uuid        NOT NULL REFERENCES schedule (id),
    practitioner_id  uuid        NOT NULL,                 -- copied from schedule to filter without a join
    specialty        text        NOT NULL,                 -- copied from schedule
    status           text        NOT NULL CHECK (status IN ('free', 'busy', 'busy-unavailable')),
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    content          jsonb       NOT NULL,
    CONSTRAINT ck_slot_period CHECK (end_at > start_at),
    -- No two overlapping slots within the same Schedule
    CONSTRAINT ex_slot_no_overlap EXCLUDE USING gist (schedule_id WITH =, tstzrange(start_at, end_at) WITH &&)
);
-- Hottest query (500 RPS at 07:00): find free slots by specialty and date range
CREATE INDEX ix_slot_free_by_specialty ON slot (specialty, start_at) WHERE status = 'free';
-- A doctor's schedule (schedule management screen, filtered by doctor)
CREATE INDEX ix_slot_by_practitioner ON slot (practitioner_id, start_at);
