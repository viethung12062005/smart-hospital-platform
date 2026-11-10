CREATE EXTENSION IF NOT EXISTS btree_gist;          -- cho exclusion constraint (uuid =, range &&)
CREATE SCHEMA IF NOT EXISTS scheduling;
SET search_path = scheduling, public;

-- Lịch làm việc của một bác sĩ cho một chuyên khoa (FHIR Schedule)
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
-- Mỗi bác sĩ tối đa 1 Schedule đang hoạt động cho mỗi chuyên khoa
CREATE UNIQUE INDEX ux_schedule_active_practitioner_specialty
    ON schedule (practitioner_id, specialty) WHERE active;

-- Mẫu lịch tuần (không phải FHIR) dùng để sinh Slot
CREATE TABLE schedule_template (
    id            uuid        PRIMARY KEY,
    schedule_id   uuid        NOT NULL REFERENCES schedule (id),
    weekday       smallint    NOT NULL CHECK (weekday BETWEEN 1 AND 7),   -- ISO 8601: 1 = thứ Hai
    start_time    time        NOT NULL,                                   -- giờ địa phương Asia/Ho_Chi_Minh
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

-- Khung giờ khám (FHIR Slot)
CREATE TABLE slot (
    id               uuid        PRIMARY KEY,
    version_id       integer     NOT NULL DEFAULT 1 CHECK (version_id > 0),
    last_updated     timestamptz NOT NULL,
    schedule_id      uuid        NOT NULL REFERENCES schedule (id),
    practitioner_id  uuid        NOT NULL,                 -- sao chép từ schedule để lọc không cần join
    specialty        text        NOT NULL,                 -- sao chép từ schedule
    status           text        NOT NULL CHECK (status IN ('free', 'busy', 'busy-unavailable')),
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    content          jsonb       NOT NULL,
    CONSTRAINT ck_slot_period CHECK (end_at > start_at),
    -- Không có 2 slot chồng lấn trong cùng một Schedule
    CONSTRAINT ex_slot_no_overlap EXCLUDE USING gist (schedule_id WITH =, tstzrange(start_at, end_at) WITH &&)
);
-- Truy vấn nóng nhất (500 RPS lúc 07:00): tìm slot trống theo chuyên khoa và khoảng ngày
CREATE INDEX ix_slot_free_by_specialty ON slot (specialty, start_at) WHERE status = 'free';
-- Lịch của một bác sĩ (màn hình quản lý lịch, lọc theo bác sĩ)
CREATE INDEX ix_slot_by_practitioner ON slot (practitioner_id, start_at);
