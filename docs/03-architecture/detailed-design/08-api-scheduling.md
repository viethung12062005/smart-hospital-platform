# 08 — API scheduling

Quy ước chung: [07-api-conventions.md](07-api-conventions.md). DB: [02-db-scheduling.md](02-db-scheduling.md).
Ký hiệu quyền: **P** Patient, **D** Doctor, **N** Nurse, **A** Admin. `own` = chỉ dữ liệu của chính mình.

## 1. Tổng hợp endpoint

| # | Method + đường dẫn | Mục đích | Quyền | Use case |
|---|---|---|---|---|
| S1 | `POST /fhir/Schedule` | Tạo lịch làm việc của bác sĩ | N, A | UC-02 |
| S2 | `GET /fhir/Schedule/{id}` | Đọc lịch | N, A; D (của mình) | UC-02 |
| S3 | `GET /fhir/Schedule` | Tìm lịch | N, A; D (của mình) | UC-02 |
| S4 | `PUT /fhir/Schedule/{id}` | Sửa lịch (bật/tắt, ghi chú) | N, A | UC-02 |
| S5 | `PUT /api/v1/scheduling/schedules/{id}/templates` | Đặt mẫu lịch tuần | N, A | UC-02 |
| S6 | `GET /api/v1/scheduling/schedules/{id}/templates` | Xem mẫu lịch tuần | N, A; D (của mình) | UC-02 |
| S7 | `POST /api/v1/scheduling/schedules/{id}/slots:generate` | Sinh slot theo mẫu cho khoảng ngày | N, A | UC-02 |
| S8 | `GET /fhir/Slot` | Tìm slot | P, N, A, D | UC-03 |
| S9 | `POST /fhir/Slot/{id}/$block` | Chặn slot | N, A | UC-02 |
| S10 | `POST /fhir/Slot/{id}/$unblock` | Bỏ chặn slot | N, A | UC-02 |
| S11 | `POST /fhir/Appointment/$book` | Đặt lịch | P (own), N | UC-03 |
| S12 | `GET /fhir/Appointment/{id}` | Đọc lịch hẹn | P (own), D (của mình), N | UC-03 |
| S13 | `GET /fhir/Appointment` | Tìm lịch hẹn | P (own), D (của mình), N | UC-03 |
| S14 | `POST /fhir/Appointment/{id}/$cancel` | Hủy lịch | P (own, BR-02), N | UC-03 |
| S15 | `POST /fhir/Appointment/{id}/$reschedule` | Đổi lịch | P (own), N | UC-03 |
| S16 | `POST /fhir/Appointment/{id}/$check-in` | Check-in | N | UC-04 |
| S17 | `GET /fhir/Appointment/{id}/_history` | Lịch sử phiên bản | N, A | UC-03 |
| S18 | `GET /internal/v1/appointments/{id}` | Đọc lịch hẹn (service khác) | service `clinical`, `ml` | — |

Doctor **không** được đặt, hủy, đổi lịch; Admin không xem lịch hẹn của bệnh nhân (theo [ma trận phân quyền](../../02-requirements/access-control-matrix.md)).

## 2. Lịch làm việc

### S1 `POST /fhir/Schedule`
Request (`Schedule`, `id`/`meta` do server cấp):
```json
{
  "resourceType": "Schedule",
  "active": true,
  "actor": [{ "reference": "Practitioner/0192f8a4-...", "display": "BS. Lê Minh Quân" }],
  "specialty": [{ "coding": [{ "system": "https://shp.example.org/fhir/CodeSystem/specialty", "code": "cardiology" }] }],
  "comment": "Phòng khám 204"
}
```
| Kết quả | |
|---|---|
| 201 | Resource kèm `id`, `ETag`, `Location`; `planningHorizon` do server điền (hôm nay → +30 ngày) |
| 400 | `actor` không đúng 1 phần tử, `specialty` ngoài value set, `Practitioner` không tồn tại hoặc không có `PractitionerRole` khớp chuyên khoa (server kiểm bằng `/internal/v1/practitioners/{id}/roles` của clinical) |
| 409 | Đã có Schedule hoạt động cho cùng bác sĩ và chuyên khoa (`ux_schedule_active_practitioner_specialty`) |
| 403 | Role không phải N/A |

### S3 `GET /fhir/Schedule`
Tham số: `actor=Practitioner/{id}` · `specialty={code}` · `active=true|false` · `_count` · `_page_token`.
Bác sĩ gọi mà không có `actor` của chính mình → server tự thêm `actor=<mình>`.

### S5 `PUT /api/v1/scheduling/schedules/{id}/templates`
Thay **toàn bộ** mẫu tuần của Schedule (`If-Match` bắt buộc trên Schedule).
```json
{
  "templates": [
    { "weekday": 1, "startTime": "08:00", "endTime": "11:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null },
    { "weekday": 3, "startTime": "13:30", "endTime": "16:30", "slotMinutes": 15, "validFrom": "2026-12-01", "validTo": null }
  ]
}
```
| Kết quả | |
|---|---|
| 200 | `{ "templates": [...], "affectedFutureSlots": 0 }`; không tự xóa slot đã sinh |
| 400 | `startTime ≥ endTime`; `slotMinutes` ngoài {10,15,20,30}; khoảng thời gian không chia hết cho `slotMinutes`; hai mẫu cùng thứ chồng giờ |
| 412 | `If-Match` sai |

### S7 `POST /api/v1/scheduling/schedules/{id}/slots:generate`
```json
{ "from": "2026-12-01", "to": "2026-12-31" }
```
- `to` tối đa hôm nay + 30 ngày (BR-04), tối đa 31 ngày mỗi lần gọi, `from ≥` hôm nay.
- Sinh slot theo mẫu có hiệu lực, giờ địa phương `Asia/Ho_Chi_Minh`; `ON CONFLICT DO NOTHING` nên gọi lại an toàn.
- 200: `{ "created": 14, "skippedExisting": 0 }` — ví dụ AC-02.1: mẫu 08:00–11:30 / 15 phút → **14 slot `free`** cho mỗi ngày.
- 400: khoảng ngày vi phạm giới hạn. 404: Schedule không tồn tại hoặc `active=false` → 422 `schedule-inactive`.

## 3. Slot

### S8 `GET /fhir/Slot` — tìm slot trống (đường nóng, NFR-P1)
| Tham số | Kiểu | Ghi chú |
|---|---|---|
| `status` | token | mặc định `free` cho Patient; N/A/D được dùng `busy`, `busy-unavailable` |
| `specialty` | token | **bắt buộc** nếu không có `schedule` hoặc `practitioner` |
| `practitioner` | reference | `Practitioner/{id}` |
| `schedule` | reference | `Schedule/{id}` |
| `start` | date | `ge`, `gt`, `le`, `lt`; ví dụ `start=ge2026-12-01&start=lt2026-12-08`; khoảng tối đa 31 ngày |
| `_count`, `_page_token` | | keyset theo (`start_at`, `id`) |

Response: `Bundle searchset` sắp theo `start` tăng dần; mỗi `Slot` là bản rút gọn (không có `comment`). Bệnh nhân luôn bị ép
`status=free` và `start ≥ now`. Cache Redis 5 giây theo khóa truy vấn chuẩn hóa; vô hiệu hóa khi có event `appointment-*`
của cùng bác sĩ/ngày (SCH-15). 400 nếu khoảng ngày > 31 hoặc thiếu bộ lọc chuyên khoa/bác sĩ/lịch.

### S9 `POST /fhir/Slot/{id}/$block`
Body: `{ "reason": "Bác sĩ nghỉ phép" }` (1–200 ký tự). Header `If-Match`.
| Kết quả | |
|---|---|
| 200 | Slot `busy-unavailable`, `comment = reason` |
| 409 `slot-has-appointment` | Slot đang `busy` bởi lịch hẹn hiệu lực — phải hủy/đổi lịch hẹn trước (AC-02.2) |
| 412 | `If-Match` sai |

`$unblock`: chuyển `busy-unavailable → free`; 409 `invalid-state-transition` nếu slot không ở trạng thái đó.

## 4. Lịch hẹn

### S11 `POST /fhir/Appointment/$book`
Header bắt buộc: `Idempotency-Key`. Body là `Parameters`:
```json
{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "slot", "valueReference": { "reference": "Slot/0192f8a4-..." } },
    { "name": "patient", "valueReference": { "reference": "Patient/0192f8a4-..." } },
    { "name": "comment", "valueString": "Đau ngực khi gắng sức" },
    { "name": "channel", "valueCode": "web" }
  ]
}
```
- `patient`: Patient role chỉ được đặt cho **chính mình** (so với `fhirUser`), ngược lại 403 (AC-03.6). Nurse đặt cho bất kỳ bệnh nhân (`channel=staff` bị ép).
- `comment` tùy chọn ≤ 500 ký tự; `channel` ∈ {`web`,`chatbot`,`staff`} (Patient chỉ được `web`/`chatbot`).

Kết quả:
| HTTP | Điều kiện | `code` |
|---|---|---|
| **201** | Đặt thành công; trả `Appointment` (`status=booked`), `Location`, `ETag` | |
| **200** | Cùng `Idempotency-Key` + cùng body: trả đúng lịch hẹn đã tạo (AC-03.2) | |
| 400 | Thiếu tham số / Parameters sai cấu trúc | `validation-failed` |
| 403 | Đặt cho người khác | `forbidden` |
| 404 | Slot hoặc bệnh nhân không tồn tại | `not-found` |
| **409** | Slot đã bị đặt, bị chặn, đã qua | `slot-unavailable` — kèm `OperationOutcome` có `extension[suggested-slots]`: 3 slot trống gần nhất cùng bác sĩ/chuyên khoa |
| 422 | Bệnh nhân đã có 3 lịch hẹn tương lai (BR-03) | `max-future-appointments` |
| 422 | Slot ngoài cửa sổ 30 ngày (BR-04) | `outside-booking-window` |
| 422 | Cùng key, body khác (AC-03.3) | `idempotency-key-reused` |
| 428 | Thiếu `Idempotency-Key` | `idempotency-key-required` |
| 429 | Quá 10 lần/phút | `rate-limited` |

Hiệu ứng phụ: slot → `busy`; ghi `resource_history`; phát `shp.scheduling.appointment-booked.v1` (cùng transaction).
Độ trễ mục tiêu p95 ≤ 800 ms tại 50 RPS (NFR-P2). Xử lý chi tiết từng bước: [02-db-scheduling.md](02-db-scheduling.md) §3.1.

### S13 `GET /fhir/Appointment`
| Tham số | Ghi chú |
|---|---|
| `patient=Patient/{id}` | Patient: ép = mình; D: phải là bệnh nhân có quan hệ điều trị |
| `practitioner=Practitioner/{id}` | D: ép = mình |
| `status` | `booked,arrived,fulfilled,cancelled,noshow` (phân cách bằng dấu phẩy = OR) |
| `date` | `ge/le/gt/lt` theo `start`; khoảng ≤ 92 ngày |
| `specialty` | |
| `_sort` | `date` hoặc `-date` (mặc định `date`) |
| `_count`, `_page_token` | keyset |

N không bị ép bộ lọc. Phải có ít nhất một trong `patient`, `practitioner`, `date`.

### S14 `POST /fhir/Appointment/{id}/$cancel`
Header: `Idempotency-Key`, `If-Match`. Body:
```json
{ "resourceType": "Parameters", "parameter": [ { "name": "reason", "valueCode": "patient-request" } ] }
```
| Kết quả | |
|---|---|
| 200 | `Appointment.status=cancelled`; slot → `free` nếu còn trước giờ khám; phát `appointment-cancelled.v1` |
| 403 | Patient hủy lịch của người khác |
| 409 `invalid-state-transition` | Lịch hẹn không còn `booked` (đã `arrived`, `cancelled`...). Nurse được hủy lịch `arrived` kèm `reason=other` |
| 412 | `If-Match` sai |
| 422 `cancellation-too-late` | Patient hủy khi còn **< 2 giờ** (BR-02, AC-03.5); Nurse luôn được hủy |

### S15 `POST /fhir/Appointment/{id}/$reschedule`
Header: `Idempotency-Key`, `If-Match`. Body: `{ "slot": Reference(Slot), "comment"? }`.
Atomic: đặt slot mới + hủy lịch cũ (`cancelationReason = rescheduled`) + lịch mới có `extension[rescheduled-from]`.
Lỗi ở bất kỳ bước nào (slot mới bị đặt, quy tắc BR-02 với lịch cũ, v.v.) → **không thay đổi gì**. BR-03 không tính lịch cũ đang đổi.
Kết quả: 201 trả lịch hẹn **mới**; lỗi như `$book` và `$cancel`. Phát `appointment-rescheduled.v1`.

### S16 `POST /fhir/Appointment/{id}/$check-in`
Chỉ Nurse. `If-Match` bắt buộc. `booked → arrived` trong khoảng từ **60 phút trước** đến **30 phút sau** `start`,
ngoài khoảng → 422 `checkin-out-of-window`. Phát `appointment-checked-in.v1` (clinical nghe để tạo `Encounter`).

## 5. API nội bộ

### S18 `GET /internal/v1/appointments/{id}`
Trả `Appointment` đầy đủ. Chỉ gọi từ `clinical` (xác thực Encounter) và `ml`. Không có kiểm tra quyền người dùng
(đã tin cậy theo service identity); mọi truy cập bị audit với `actor.role = system`.

## 6. Ánh xạ trạng thái ↔ event

| Hành động | Event phát ra |
|---|---|
| `$book` | `shp.scheduling.appointment-booked.v1` |
| `$cancel` | `shp.scheduling.appointment-cancelled.v1` |
| `$reschedule` | `shp.scheduling.appointment-rescheduled.v1` (+ `appointment-cancelled`/`booked` **không** phát riêng để tránh nhắc trùng) |
| `$check-in`, job no-show, `fulfilled` (nhận `encounter-finished`) | `shp.scheduling.appointment-status-changed.v1` (`checked-in` dùng `appointment-checked-in.v1`) |

Mỗi thao tác cũng phát `shp.audit.recorded.v1`. Schema chi tiết: [13-events.md](13-events.md).
