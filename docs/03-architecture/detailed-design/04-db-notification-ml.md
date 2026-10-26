# 04 — Thiết kế DB: notification và ML (PostgreSQL 16)

Hai database riêng trên cùng PostgreSQL Flexible Server với scheduling: `notification` và `ml`. Mỗi service một role, không
truy cập chéo database.

## 1. Notification

### 1.1 Nguyên tắc
- **Không lưu PII**: không lưu số điện thoại/email. Lúc gửi, worker gọi API nội bộ của clinical để lấy thông tin liên hệ
  (xem [09-api-clinical.md](09-api-clinical.md) §4) và chỉ giữ trong bộ nhớ.
- Lập lịch bằng bảng `reminder` + worker poll `FOR UPDATE SKIP LOCKED`: hủy nhắc = cập nhật một dòng; chạy giống nhau trên
  RabbitMQ (local) và Service Bus (cloud).
- Giao **ít nhất một lần**, chống gửi trùng bằng trạng thái `sending` + `locked_until` và truyền `reminder.id` làm khóa
  idempotency cho nhà cung cấp (nếu nhà cung cấp hỗ trợ).

### 1.2 DDL

```sql
CREATE SCHEMA IF NOT EXISTS notification;
SET search_path = notification;

CREATE TABLE notification_preference (
    patient_id   uuid        PRIMARY KEY,
    channels     text[]      NOT NULL DEFAULT ARRAY['push', 'sms', 'email'],   -- thứ tự ưu tiên, kênh sau là dự phòng
    locale       text        NOT NULL DEFAULT 'vi' CHECK (locale IN ('vi', 'en')),
    quiet_start  time,                                                     -- không gửi trong khung giờ yên tĩnh (trừ T-2h)
    quiet_end    time,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT ck_pref_channels CHECK (cardinality(channels) >= 1 AND channels <@ ARRAY['push', 'sms', 'email'])
);

CREATE TABLE device_token (
    id            uuid        PRIMARY KEY,
    owner_ref     text        NOT NULL CHECK (owner_ref ~ '^(Patient|Practitioner)/[0-9a-f-]{36}$'),
    platform      text        NOT NULL CHECK (platform IN ('ios', 'android', 'web')),
    token         text        NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL,
    last_seen_at  timestamptz NOT NULL
);
CREATE INDEX ix_device_token_owner ON device_token (owner_ref);

CREATE TABLE reminder (
    id                 uuid        PRIMARY KEY,
    subject_ref        text        NOT NULL,          -- Appointment/{id} hoặc DocumentReference/{id}
    patient_id         uuid        NOT NULL,
    kind               text        NOT NULL CHECK (kind IN ('booking-confirmation', 't-24h', 't-6h-risk', 't-2h',
                                                            'cancellation', 'result-available')),
    appointment_start  timestamptz,                   -- null với result-available
    due_at             timestamptz NOT NULL,
    status             text        NOT NULL CHECK (status IN ('scheduled', 'sending', 'sent', 'cancelled', 'dead-lettered')),
    attempts           smallint    NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    locked_until       timestamptz,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT ux_reminder_once UNIQUE (subject_ref, kind)          -- event lặp không tạo nhắc trùng
);
CREATE INDEX ix_reminder_due      ON reminder (due_at)       WHERE status = 'scheduled';
CREATE INDEX ix_reminder_stuck    ON reminder (locked_until) WHERE status = 'sending';
CREATE INDEX ix_reminder_subject  ON reminder (subject_ref);

CREATE TABLE delivery_attempt (
    id                   uuid        PRIMARY KEY,
    reminder_id          uuid        NOT NULL REFERENCES reminder (id),
    attempt_no           smallint    NOT NULL,
    channel              text        NOT NULL CHECK (channel IN ('push', 'sms', 'email')),
    provider             text        NOT NULL,
    outcome              text        NOT NULL CHECK (outcome IN ('accepted', 'failed-transient', 'failed-permanent')),
    provider_message_id  text,
    error_code           text,
    latency_ms           integer,
    attempted_at         timestamptz NOT NULL,
    CONSTRAINT ux_attempt UNIQUE (reminder_id, attempt_no, channel)
);

CREATE TABLE inbox (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
```

### 1.3 Thao tác chính

| Thao tác | Cách làm |
|---|---|
| Nhận `appointment-booked` | Transaction: `INSERT inbox` (trùng → bỏ qua) → `INSERT reminder` cho `booking-confirmation` (due ngay), `t-24h`, `t-2h` với `ON CONFLICT (subject_ref, kind) DO NOTHING`; bỏ qua mốc đã ở quá khứ |
| Nhận `no-show-scored` với `highRisk = true` | `INSERT reminder` kind `t-6h-risk` (`ON CONFLICT DO NOTHING`) |
| Nhận `appointment-cancelled` | `UPDATE reminder SET status = 'cancelled' WHERE subject_ref = $a AND status = 'scheduled'` + tạo `cancellation` (AC-07.1) |
| Nhận `appointment-rescheduled` | Hủy nhắc của lịch cũ, tạo nhắc cho lịch mới |
| Nhận `document-released` | `INSERT reminder` kind `result-available` |
| Lấy việc (mỗi 10 giây, nhiều replica) | `UPDATE reminder SET status = 'sending', locked_until = now() + interval '2 minutes', attempts = attempts + 1 WHERE id IN (SELECT id FROM reminder WHERE status = 'scheduled' AND due_at <= now() ORDER BY due_at LIMIT 100 FOR UPDATE SKIP LOCKED) RETURNING *` ; truy vấn thứ hai cùng dạng thu hồi dòng `sending` có `locked_until < now()` (worker chết giữa chừng) |
| Gửi thành công | `INSERT delivery_attempt (accepted)`; `UPDATE reminder SET status = 'sent'` |
| Lỗi tạm thời | Thử kênh tiếp theo trong `channels`; hết kênh thì `status = 'scheduled'`, `due_at = now() + backoff` (1 → 5 → 15 → 60 phút) |
| Hết 5 lần | `status = 'dead-lettered'`, phát metric + cảnh báo (OBS-03) |

## 2. ML

### 2.1 Nguyên tắc
- ML **tự xây dữ liệu** từ event (`appointment-booked`, `appointment-status-changed`, `appointment-cancelled`), không đọc
  DB của service khác.
- Mỗi lịch hẹn có một dòng `appointment_fact` (đặc trưng + kết quả thật) dùng cho cả dự đoán, huấn luyện lại và theo dõi drift.
- Không có PII: chỉ ID và nhóm tuổi 10 năm (`age_band`) do scheduling đưa vào event.

### 2.2 DDL

```sql
CREATE SCHEMA IF NOT EXISTS ml;
SET search_path = ml;

CREATE TABLE attendance_stats (
    patient_id         uuid        PRIMARY KEY,
    booked_total       integer     NOT NULL DEFAULT 0,
    fulfilled_total    integer     NOT NULL DEFAULT 0,
    noshow_total       integer     NOT NULL DEFAULT 0,
    late_cancel_total  integer     NOT NULL DEFAULT 0,      -- hủy < 24 giờ trước giờ khám
    last_noshow_at     timestamptz,
    last_visit_at      timestamptz,
    updated_at         timestamptz NOT NULL
);

CREATE TABLE appointment_fact (
    appointment_id   uuid        PRIMARY KEY,
    patient_id       uuid        NOT NULL,
    practitioner_id  uuid        NOT NULL,
    specialty        text        NOT NULL,
    channel          text        NOT NULL,
    age_band         text        NOT NULL CHECK (age_band ~ '^[0-9]{1,3}-[0-9]{1,3}$|^unknown$'),
    booked_at        timestamptz NOT NULL,
    start_at         timestamptz NOT NULL,
    outcome          text        NOT NULL DEFAULT 'pending' CHECK (outcome IN ('pending', 'fulfilled', 'noshow', 'cancelled')),
    outcome_at       timestamptz
);
CREATE INDEX ix_fact_start   ON appointment_fact (start_at);
CREATE INDEX ix_fact_patient ON appointment_fact (patient_id, start_at DESC);

CREATE TABLE prediction (
    id              uuid        PRIMARY KEY,
    appointment_id  uuid        NOT NULL UNIQUE REFERENCES appointment_fact (appointment_id),
    model_name      text        NOT NULL,                  -- tên trong MLflow registry, ví dụ shp-no-show
    model_version   text        NOT NULL,
    score           real        NOT NULL CHECK (score BETWEEN 0 AND 1),
    threshold       real        NOT NULL CHECK (threshold BETWEEN 0 AND 1),
    high_risk       boolean     NOT NULL,
    features        jsonb       NOT NULL,                  -- vector đặc trưng đã dùng (truy vết AC-15.2)
    predicted_at    timestamptz NOT NULL,
    latency_ms      integer
);
CREATE INDEX ix_prediction_model ON prediction (model_version, predicted_at);

CREATE TABLE inbox (
    consumer      text        NOT NULL,
    message_id    uuid        NOT NULL,
    processed_at  timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
CREATE TABLE outbox (
    id            uuid        PRIMARY KEY,
    event_type    text        NOT NULL,
    subject       text        NOT NULL,
    payload       jsonb       NOT NULL,
    occurred_at   timestamptz NOT NULL,
    published_at  timestamptz
);
CREATE INDEX ix_outbox_unpublished ON outbox (occurred_at) WHERE published_at IS NULL;
```

### 2.3 Đặc trưng (feature) của mô hình no-show

| Đặc trưng | Nguồn | Kiểu |
|---|---|---|
| `lead_time_days` | `start_at - booked_at` | số |
| `start_hour`, `weekday` | `start_at` (giờ VN) | phân loại |
| `specialty`, `channel`, `age_band` | `appointment_fact` | phân loại |
| `prior_booked`, `prior_noshow`, `prior_late_cancel` | `attendance_stats` tại thời điểm dự đoán | số |
| `noshow_rate_smoothed` | `(prior_noshow + 1) / (prior_booked + 5)` (làm trơn cho bệnh nhân mới) | số |
| `days_since_last_noshow` | `attendance_stats.last_noshow_at` (không có → 9999) | số |
| `is_first_visit` | `prior_booked = 0` | bool |

Tập huấn luyện = `appointment_fact` có `outcome` khác `pending` (dữ liệu giả sinh bởi ML-01 + dữ liệu phát sinh khi chạy),
xuất Parquet, chia train/validation theo **thời gian** (không xáo trộn) để tránh rò rỉ thông tin tương lai.

### 2.4 Thao tác chính

| Event | Xử lý |
|---|---|
| `appointment-booked` | `INSERT appointment_fact`; tính đặc trưng; chạy model; `INSERT prediction`; phát `shp.ml.no-show-scored.v1` qua outbox (cùng transaction) |
| `appointment-status-changed` (`fulfilled`/`noshow`) | Cập nhật `outcome`; cộng dồn `attendance_stats` |
| `appointment-cancelled` | `outcome = cancelled`; hủy muộn (< 24 giờ) thì `late_cancel_total + 1` |

## 3. Kiểm chứng thiết kế (đã chạy)

Hai khối DDL trên đã chạy thành công trên PostgreSQL 16.15. Đã kiểm: event lặp không tạo nhắc trùng (`ux_reminder_once`),
truy vấn lấy việc chọn đúng nhắc đã đến hạn, kênh không hợp lệ bị chặn (`ck_pref_channels`), `age_band` sai định dạng bị chặn.
