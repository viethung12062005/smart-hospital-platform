# 02 — Thiết kế DB: scheduling (PostgreSQL 16)

Database `scheduling`, schema `scheduling`. Azure Database for PostgreSQL Flexible Server (local: `postgres:16`).
Đáp ứng: UC-02, UC-03, BR-01..04, ADR-0005, NFR-P1, P2.

## 1. Nguyên tắc

- **Mỗi resource type một bảng**: cột có kiểu cho mọi trường dùng để tìm kiếm/ràng buộc + `content jsonb` chứa resource FHIR
  đầy đủ. Cột có kiểu và `content` luôn được ghi trong cùng một câu lệnh (service là nơi duy nhất ghi).
- **Quy tắc nghiệp vụ quan trọng được bảo đảm ở tầng DB**, không chỉ ở code: không chồng lấn slot, tối đa một lịch hẹn
  hiệu lực cho mỗi slot, lý do hủy bắt buộc khi hủy.
- Optimistic concurrency: cột `version_id`; cập nhật luôn có điều kiện `WHERE id = $id AND version_id = $expected`.

## 2. DDL

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;          -- cho exclusion constraint (uuid =, range &&)
CREATE SCHEMA IF NOT EXISTS scheduling;
SET search_path = scheduling;

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

-- Lịch hẹn (FHIR Appointment)
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
    booked_by        text        NOT NULL,                 -- OIDC subject của người đặt
    cancel_reason    text,
    rescheduled_from uuid        REFERENCES appointment (id),
    content          jsonb       NOT NULL,
    CONSTRAINT ck_appointment_cancel_reason CHECK ((status = 'cancelled') = (cancel_reason IS NOT NULL))
);
-- BR-01 ở tầng DB: tối đa một lịch hẹn hiệu lực cho mỗi slot (lớp bảo vệ cuối cùng chống double-booking)
CREATE UNIQUE INDEX ux_appointment_one_active_per_slot
    ON appointment (slot_id) WHERE status IN ('booked', 'arrived', 'fulfilled');
CREATE INDEX ix_appointment_patient_start      ON appointment (patient_id, start_at DESC);
CREATE INDEX ix_appointment_practitioner_start ON appointment (practitioner_id, start_at);
-- Job đánh dấu no-show chỉ quét lịch hẹn còn 'booked'
CREATE INDEX ix_appointment_booked_end         ON appointment (end_at) WHERE status = 'booked';

-- Lịch sử phiên bản (FHIR _history) cho Schedule và Appointment
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

-- Transactional outbox: event được ghi cùng transaction với thay đổi dữ liệu
CREATE TABLE outbox (
    id            uuid        PRIMARY KEY,                -- = CloudEvents id
    event_type    text        NOT NULL,                   -- ví dụ shp.scheduling.appointment-booked.v1
    subject       text        NOT NULL,                   -- ví dụ Appointment/{id}
    payload       jsonb       NOT NULL,                   -- CloudEvent đầy đủ
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz,
    attempts      smallint    NOT NULL DEFAULT 0,
    last_error    text
);
CREATE INDEX ix_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;

-- Idempotency cho $book, $cancel, $reschedule
CREATE TABLE idempotency_key (
    subject        text        NOT NULL,                  -- OIDC subject của người gọi
    key            text        NOT NULL CHECK (length(key) BETWEEN 8 AND 128),
    operation      text        NOT NULL,                  -- ví dụ 'Appointment/$book'
    request_hash   bytea       NOT NULL,                  -- SHA-256 của body chuẩn hóa
    resource_id    uuid        NOT NULL,                  -- ID lịch hẹn được cấp trước khi xử lý
    created_at     timestamptz NOT NULL,
    PRIMARY KEY (subject, key)
);
CREATE INDEX ix_idempotency_created ON idempotency_key (created_at);

-- Inbox: chống xử lý trùng event nhận vào (ví dụ EncounterFinished)
CREATE TABLE inbox (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
```

> Slot **không** lưu lịch sử phiên bản: khối lượng lớn (~1,2 triệu/năm) mà thay đổi trạng thái slot đã truy được qua
> lịch sử Appointment và audit.

## 3. Các thao tác ghi chính

### 3.1 `$book` (một transaction, mức cô lập READ COMMITTED)

| Bước | SQL | Kết quả khi thất bại |
|---|---|---|
| 1. Giành idempotency key (ID lịch hẹn cấp trước) | `INSERT INTO idempotency_key (...) VALUES (...) ON CONFLICT DO NOTHING RETURNING resource_id` | Không có dòng trả về → đọc bản ghi cũ: khác `request_hash` → **422** `idempotency-key-reused`; trùng → `ROLLBACK`, trả lại lịch hẹn cũ (**200**). Nếu request cùng key đang chạy song song, câu `INSERT` chờ request kia commit rồi mới quyết định |
| 2. Tuần tự hóa theo bệnh nhân | `SELECT pg_advisory_xact_lock(hashtextextended('appt-patient:' \|\| $patient_id, 0))` | — |
| 3. Kiểm tra BR-03 | `SELECT count(*) FROM appointment WHERE patient_id = $p AND status = 'booked' AND start_at > now()` | ≥ 3 → **422** `max-future-appointments` |
| 4. Giành slot (compare-and-set, không giữ lock lâu) | `UPDATE slot SET status = 'busy', version_id = version_id + 1, last_updated = now(), content = jsonb_set(content, '{status}', '"busy"') WHERE id = $slot AND status = 'free' AND start_at > now() AND start_at < now() + interval '30 days' RETURNING practitioner_id, specialty, start_at, end_at` | 0 dòng → đọc lại slot để phân biệt: không tồn tại **404**, ngoài cửa sổ 30 ngày **422** `outside-booking-window`, đã bị đặt **409** `slot-unavailable` |
| 5. Tạo lịch hẹn | `INSERT INTO appointment (...)` | Vi phạm `ux_appointment_one_active_per_slot` (SQLSTATE 23505) → **409** (không bao giờ xảy ra nếu bước 4 đúng, đây là lớp bảo vệ cuối) |
| 6. Lịch sử + outbox | `INSERT INTO resource_history ...; INSERT INTO outbox ... ('shp.scheduling.appointment-booked.v1')` | — |
| 7. `COMMIT` | | |

Đổi lịch (`$reschedule`) = bước 1–6 cho slot mới + hủy lịch cũ (cập nhật có điều kiện `version_id`) trong **cùng
transaction**, phát `appointment-rescheduled.v1`. Lỗi ở bất kỳ bước nào thì rollback toàn bộ, lịch cũ giữ nguyên.

### 3.2 `$cancel`

```sql
UPDATE appointment
   SET status = 'cancelled', cancel_reason = $reason, version_id = version_id + 1, last_updated = now(), content = $content
 WHERE id = $id AND version_id = $expected AND status = 'booked'
   AND ($is_staff OR start_at - now() >= interval '2 hours')        -- BR-02
RETURNING slot_id, start_at;
-- trả slot về free nếu hủy trước giờ khám
UPDATE slot SET status = 'free', version_id = version_id + 1, last_updated = now(), content = jsonb_set(content, '{status}', '"free"')
 WHERE id = $slot_id AND status = 'busy' AND start_at > now();
-- + resource_history + outbox ('shp.scheduling.appointment-cancelled.v1')
```

### 3.3 Job định kỳ

| Job | Lịch | SQL chính | Ghi chú |
|---|---|---|---|
| Sinh slot | 01:00 hằng ngày (giờ VN) | `INSERT INTO slot ... ON CONFLICT DO NOTHING` cho từng mẫu tuần, các ngày từ `horizon_end + 1` đến hôm nay + 30 | Exclusion constraint chặn trùng; chạy lại an toàn |
| Đánh dấu no-show | mỗi 5 phút | `UPDATE appointment SET status = 'noshow' ... WHERE id IN (SELECT id FROM appointment WHERE status = 'booked' AND end_at < now() - interval '30 minutes' LIMIT 500 FOR UPDATE SKIP LOCKED)` | Phát `appointment-status-changed.v1` |
| Phát outbox | liên tục (poll 1 giây) | `SELECT ... FROM outbox WHERE published_at IS NULL ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED` | Nhiều replica chạy song song không trùng |
| Dọn dẹp | 02:00 hằng ngày | xóa `outbox` đã phát > 7 ngày, `idempotency_key` > 24 giờ, slot `free` đã qua > 90 ngày | |

## 4. Truy vấn đọc chính và index dùng

| Truy vấn | SQL rút gọn | Index |
|---|---|---|
| Tìm slot trống (NFR-P1) | `WHERE status = 'free' AND specialty = $s AND start_at >= $from AND start_at < $to [AND practitioner_id = $p] ORDER BY start_at LIMIT $n` | `ix_slot_free_by_specialty` (partial: chỉ chứa slot trống nên nhỏ) |
| Lịch hẹn của bệnh nhân | `WHERE patient_id = $p ORDER BY start_at DESC` | `ix_appointment_patient_start` |
| Lịch hẹn của bác sĩ theo ngày | `WHERE practitioner_id = $d AND start_at >= $day AND start_at < $day + 1` | `ix_appointment_practitioner_start` |
| Slot của một Schedule | `WHERE schedule_id = $s AND tstzrange(start_at, end_at) && $range` | GiST của `ex_slot_no_overlap` |

Mọi truy vấn trên được kiểm bằng `EXPLAIN (ANALYZE, BUFFERS)` trên dữ liệu giả cỡ năm 1 (task SCH-03), kết quả
lưu vào tài liệu hiệu năng.

## 5. Dung lượng, retention, quyền

| Hạng mục | Giá trị |
|---|---|
| Kích thước năm 1 | slot ~1,2 triệu dòng (~1,2 GB), appointment ~330k (~0,5 GB), history ~1 GB |
| Partition | Chưa cần ở quy mô này; xem xét partition `slot`/`appointment` theo tháng khi > 50 triệu dòng |
| Backup | PITR 7 ngày (Flexible Server); prod bật geo-redundant backup |
| Role DB | `scheduling_app` (managed identity của service): `SELECT/INSERT/UPDATE` mọi bảng, `DELETE` chỉ trên `outbox`, `idempotency_key`, `inbox`, `slot` (job dọn dẹp); không có quyền DDL. `scheduling_migrator`: chạy migration (Kubernetes Job trước khi deploy, sync wave của Argo CD) |
| Migration | File SQL đánh số (`V001__init.sql`, ...), chạy bằng DbUp; chỉ thay đổi tương thích ngược (expand → migrate → contract) để deploy không downtime |

## 6. Kiểm chứng thiết kế (đã chạy)

DDL ở mục 2 đã được chạy trên PostgreSQL 16.15 cùng bộ kiểm tra sau (26/10/2026):

| Kiểm tra | Kết quả |
|---|---|
| Áp dụng DDL | Thành công, không lỗi |
| Tạo slot chồng lấn trong cùng Schedule | Bị chặn bởi `ex_slot_no_overlap` |
| Lịch hẹn `cancelled` không có lý do | Bị chặn bởi `ck_appointment_cancel_reason` |
| **50 phiên song song cùng đặt một slot** theo SQL ở mục 3.1 | **Đúng 1 lịch hẹn**, slot `busy`, `version_id = 2` |
| Chèn lịch hẹn hiệu lực thứ hai vào slot đã đặt (bỏ qua bước giành slot) | Bị chặn bởi `ux_appointment_one_active_per_slot` |
| `EXPLAIN` truy vấn tìm slot trống | `Index Scan using ix_slot_free_by_specialty` |
