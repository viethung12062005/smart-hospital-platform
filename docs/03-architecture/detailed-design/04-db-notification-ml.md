# 04 — Thiết kế DB: notification và ML (MongoDB 8 / Cosmos DB for MongoDB vCore)

> Theo [ADR-0014](../adr/0014-database-per-service-review.md) (thay thiết kế PostgreSQL trước đây). Hai database riêng `notification` và `ml`
> trên cùng cụm MongoDB với clinical/document/ai; mỗi service một user chỉ có quyền trên database của mình, không truy cập chéo.
> notification hiện thực bằng Java (Spring Data MongoDB), ml bằng Python (pymongo) — [ADR-0015](../adr/0015-java-for-all-services.md). Script khởi tạo: [§3](#3-script-khởi-tạo-và-kiểm-chứng).

Quy ước chung (giống [03](03-db-clinical-document.md)): `_id` là UUIDv7 dạng chuỗi; thời gian là `Date` UTC; mọi ghi nghiệp vụ + `outbox` nằm trong
**một transaction**; `inbox` có `_id = "<consumer>|<messageId>"` và TTL 14 ngày.

## 1. Notification

### 1.1 Nguyên tắc
- **Không lưu PII**: không lưu số điện thoại/email. Lúc gửi, worker gọi API nội bộ của clinical lấy thông tin liên hệ
  ([09-api-clinical.md](09-api-clinical.md) §4) và chỉ giữ trong bộ nhớ.
- Mỗi nhắc là **một document**; các lần gửi **nhúng** trong document (tối đa 5 lần × ≤ 3 kênh, không phình to).
- Lấy việc bằng **`findOneAndUpdate` nguyên tử** (thay `FOR UPDATE SKIP LOCKED`): hai worker không bao giờ lấy cùng một nhắc;
  `lockedUntil` cho phép thu hồi khi worker chết giữa chừng. Hủy nhắc = cập nhật một document.
- Chống tạo trùng bằng **unique index** `(subjectRef, kind)`; tự dọn nhắc đã xong bằng **TTL index** (90 ngày).

### 1.2 Collection

| Collection | Document | Index |
|---|---|---|
| `preferences` | `_id` = patientId; `channels` (mảng có thứ tự ⊂ `push`,`sms`,`email`, ≥ 1); `locale` `vi`\|`en`; `quietStart`, `quietEnd` (chuỗi `HH:mm`, tùy chọn); `updatedAt` | `_id` |
| `device_tokens` | `_id`; `ownerRef` (`Patient/{id}`\|`Practitioner/{id}`); `platform` `ios`\|`android`\|`web`; `token`; `createdAt`, `lastSeenAt` | unique `{token:1}`; `{ownerRef:1}` |
| `reminders` | xem dưới | xem dưới |
| `inbox`, `outbox` | như [03](03-db-clinical-document.md) §2 | như 03 |

`reminders`:
```json
{
  "_id": "0192f8a4-...", "subjectRef": "Appointment/0192...", "patientId": "0192...",
  "kind": "t-24h",                       // booking-confirmation | t-24h | t-6h-risk | t-2h | cancellation | result-available
  "appointmentStart": ISODate("2026-12-03T01:15:00Z"),   // null với result-available
  "dueAt": ISODate("2026-12-02T01:15:00Z"),
  "status": "scheduled",                 // scheduled | sending | sent | cancelled | dead-lettered
  "attempts": 0, "lockedUntil": null,
  "deliveries": [ { "attemptNo": 1, "channel": "push", "provider": "fcm", "outcome": "accepted",
                    "providerMessageId": "...", "errorCode": null, "latencyMs": 120, "attemptedAt": ISODate("...") } ],
  "createdAt": ISODate("..."), "updatedAt": ISODate("..."), "completedAt": null
}
```

| Index | Mục đích |
|---|---|
| unique `{subjectRef:1, kind:1}` | event lặp không tạo nhắc trùng |
| `{dueAt:1}` **partial** `{status:"scheduled"}` | lấy việc đến hạn chỉ quét nhắc đang chờ |
| `{lockedUntil:1}` **partial** `{status:"sending"}` | thu hồi nhắc treo |
| `{subjectRef:1}` | hủy theo lịch hẹn |
| **TTL** `{completedAt:1}` `expireAfterSeconds: 7776000` (90 ngày) | tự dọn nhắc `sent`/`cancelled`/`dead-lettered` (`completedAt` chỉ đặt khi kết thúc) |

Validator (`$jsonSchema`) bắt buộc `subjectRef`, `patientId`, `kind` ∈ danh sách, `status` ∈ danh sách, `attempts` 0..5, `dueAt` kiểu `date`.

### 1.3 Thao tác chính

| Thao tác | Cách làm |
|---|---|
| Nhận `appointment-booked` | Transaction: `insert inbox` (trùng khóa → bỏ qua cả event) → `insertMany` nhắc `booking-confirmation` (due ngay), `t-24h`, `t-2h`, `ordered:false`; lỗi trùng khóa (11000) của từng nhắc được bỏ qua; bỏ mốc đã ở quá khứ |
| `no-show-scored` với `highRisk` | thêm nhắc `t-6h-risk` (trùng → bỏ qua) |
| `appointment-cancelled` | `updateMany({subjectRef, status:"scheduled"}, {$set:{status:"cancelled", completedAt:now}})` + thêm nhắc `cancellation` (AC-07.1) |
| `appointment-rescheduled` | hủy nhắc của lịch cũ, tạo nhắc cho lịch mới (một event, một thông báo gộp) |
| `document-released` | thêm nhắc `result-available` |
| **Lấy việc** (vòng lặp, nhiều replica) | `findOneAndUpdate({status:"scheduled", dueAt:{$lte:now}}, {$set:{status:"sending", lockedUntil:now+2m}, $inc:{attempts:1}}, {sort:{dueAt:1}, returnDocument:"after"})`; lặp đến khi hết việc hoặc đủ lô 100 |
| Thu hồi nhắc treo | `updateMany({status:"sending", lockedUntil:{$lt:now}}, {$set:{status:"scheduled", lockedUntil:null}})` mỗi phút |
| Gửi thành công | `$push deliveries`, `status:"sent"`, `completedAt:now` (điều kiện `status:"sending"` để không ghi đè nhắc vừa bị hủy) |
| Lỗi tạm thời | thử kênh kế trong `channels`; hết kênh → `status:"scheduled"`, `dueAt = now + backoff` (1 → 5 → 15 → 60 phút) |
| Hết 5 lần | `status:"dead-lettered"`, `completedAt:now`, metric + cảnh báo (OBS-03) |

## 2. ML

### 2.1 Nguyên tắc
- ML **tự xây dữ liệu** từ event (`appointment-booked`, `appointment-status-changed`, `appointment-cancelled`), không đọc DB service khác.
- Dữ liệu **trực tuyến** (phục vụ dự đoán, giám sát) ở MongoDB; **tập huấn luyện** là snapshot **Parquet** bất biến trên Blob, băm nội dung
  ghi vào MLflow ([16-mlops.md](16-mlops.md) §2). Tính drift/AUC thực tế bằng **pandas/DuckDB** (Python) trên Parquet.
- Không có PII: chỉ ID và nhóm tuổi 10 năm (`ageBand`) do scheduling đưa vào event.

### 2.2 Collection

| Collection | Document | Index / ghi chú |
|---|---|---|
| `patient_stats` | `_id` = patientId; `bookedTotal`, `fulfilledTotal`, `noshowTotal`, `lateCancelTotal`; `lastNoshowAt`, `lastVisitAt`, `updatedAt` | cập nhật nguyên tử bằng `$inc`/`$max` với `upsert` — không cần khóa |
| `appointment_facts` | `_id` = appointmentId; `patientId`, `practitionerId`, `specialty`, `channel`, `ageBand` (`^\d{1,3}-\d{1,3}$` hoặc `unknown`), `bookedAt`, `startAt`, `outcome` (`pending`\|`fulfilled`\|`noshow`\|`cancelled`), `outcomeAt` | `{startAt:1}`; `{patientId:1, startAt:-1}`; validator `ageBand`, `outcome` |
| `predictions` | `appointmentId`, `modelName`, `modelVersion`, `score` 0..1, `threshold` 0..1, `highRisk`, `features` (đặc trưng đã dùng — truy vết AC-15.2), `predictedAt`, `latencyMs` | **time-series collection** (`timeField: predictedAt`, `metaField: modelVersion`, `granularity: minutes`) nếu Cosmos DB vCore hỗ trợ; nếu không: collection thường + `{modelVersion:1, predictedAt:1}` + unique `{appointmentId:1}`. TTL 400 ngày |
| `inbox`, `outbox` | như 03 §2 | |

Xuất snapshot huấn luyện: job `ml-export` đọc `appointment_facts` có `outcome ≠ pending` (+ đặc trưng tái tạo từ `patient_stats` **tại thời điểm
đặt lịch**, lưu sẵn trong `predictions.features` để không rò rỉ tương lai) → ghi `datasets/no-show/<yyyy-MM-dd>/<sha256>.parquet` bằng DuckDB `COPY ... TO ... (FORMAT parquet)`.

### 2.3 Đặc trưng (feature) của mô hình no-show

| Đặc trưng | Nguồn | Kiểu |
|---|---|---|
| `lead_time_days` | `startAt - bookedAt` | số |
| `start_hour`, `weekday` | `startAt` (giờ VN) | phân loại |
| `specialty`, `channel`, `age_band` | `appointment_facts` | phân loại |
| `prior_booked`, `prior_noshow`, `prior_late_cancel` | `patient_stats` tại thời điểm dự đoán | số |
| `noshow_rate_smoothed` | `(prior_noshow + 1) / (prior_booked + 5)` | số |
| `days_since_last_noshow` | `patient_stats.lastNoshowAt` (không có → 9999) | số |
| `is_first_visit` | `prior_booked = 0` | bool |

Chia train/validation theo **thời gian** (không xáo trộn) để tránh rò rỉ thông tin tương lai.

### 2.4 Thao tác chính

| Event | Xử lý (một transaction) |
|---|---|
| `appointment-booked` | `insert appointment_facts`; đọc `patient_stats`; tính đặc trưng; chạy model (LightGBM trong tiến trình); `insert predictions`; `insert outbox` `shp.ml.no-show-scored.v1` |
| `appointment-status-changed` (`fulfilled`/`noshow`) | cập nhật `outcome`; `$inc` `patient_stats` |
| `appointment-cancelled` | `outcome = cancelled`; hủy muộn (< 24 giờ) thì `$inc lateCancelTotal` |

## 3. Script khởi tạo và kiểm chứng

Script: [`contracts/db/mongo-notification-ml.js`](../../../contracts/db/mongo-notification-ml.js) (idempotent: chạy lại không lỗi). Đã chạy trên **MongoDB 8** (replica set 1 node)
và kiểm bằng [`contracts/db/mongo-notification-ml.test.js`](../../../contracts/db/mongo-notification-ml.test.js):
event lặp không tạo nhắc trùng; lấy việc nguyên tử (21 nhắc đến hạn, mỗi nhắc chỉ được lấy một lần, dùng index `ix_reminder_due`; chạy tuần tự trong mongosh — test **song song nhiều worker** nằm trong thư viện Java, Testcontainers); nhắc treo được thu hồi; kênh/`ageBand`/`kind` sai bị validator chặn;
TTL index tồn tại; transaction nhắc + outbox rollback toàn phần.
Chưa kiểm trên Cosmos DB for MongoDB vCore (time-series collection, transaction) — việc của task IAC dữ liệu khi dựng UAT.
