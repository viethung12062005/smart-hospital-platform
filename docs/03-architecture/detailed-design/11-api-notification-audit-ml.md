# 11 — API notification, audit, ML

Quy ước: [07-api-conventions.md](07-api-conventions.md).

## 1. Notification (`/api/v1/notifications`)

Phần lớn hoạt động là **bất đồng bộ qua event**; API chỉ phục vụ tùy chọn người dùng, thiết bị và vận hành.

| # | Method + đường dẫn | Mục đích | Quyền |
|---|---|---|---|
| N1 | `PUT /api/v1/notifications/devices` | Đăng ký/làm mới token thiết bị | P, D, N (của mình) |
| N2 | `DELETE /api/v1/notifications/devices/{tokenId}` | Hủy đăng ký | chủ token |
| N3 | `GET /api/v1/notifications/preferences` | Xem tùy chọn nhận thông báo | P |
| N4 | `PUT /api/v1/notifications/preferences` | Đổi kênh/ngôn ngữ/khung giờ yên tĩnh | P |
| N5 | `GET /api/v1/notifications/reminders?appointment={id}` | Xem các lần nhắc của một lịch hẹn | N, A |
| N6 | `GET /api/v1/notifications/dead-letters` | Danh sách nhắc lỗi hẳn | A |
| N7 | `POST /api/v1/notifications/dead-letters/{id}/retry` | Đưa lại hàng đợi | A |

### N1 `PUT /api/v1/notifications/devices`
`{ "platform": "ios|android|web", "token": "<FCM/APNs token>" }` → 200 `{ "tokenId" }`. Upsert theo `token`; chủ sở hữu lấy từ
`fhirUser` của người gọi (không nhận từ body). 400 nếu token rỗng hoặc > 4.096 ký tự.

### N4 `PUT /api/v1/notifications/preferences`
```json
{ "channels": ["push", "email", "sms"], "locale": "vi", "quietHours": { "start": "22:00", "end": "07:00" } }
```
`channels` ≥ 1 phần tử, thứ tự = ưu tiên (phần tử sau là dự phòng). Nhắc `t-2h` **bỏ qua** khung giờ yên tĩnh. 400 nếu kênh ngoài {push, sms, email}.

### N6/N7 Dead-letter
Item: `{ "reminderId", "kind", "subject": "Appointment/...", "attempts": 5, "lastError": "provider-timeout", "deadLetteredAt" }`
— **không** chứa thông tin liên hệ. `retry` đặt `status=scheduled`, `attempts=0`; 409 nếu lịch hẹn đã qua giờ khám.

Nội dung thông báo (đa ngôn ngữ) lấy từ bộ template version hóa trong repo `templates/<kind>.<locale>.txt`, biến chèn:
`{doctorName}`, `{startTime}`, `{place}`; không chèn chẩn đoán hay kết quả vào nội dung (chỉ "có kết quả mới, mở ứng dụng để xem").

## 2. Audit (`/api/v1/audit`)

Quyền: **DPO** (đọc, xuất), **A** (đọc, **không** thấy trường `reason` của break-glass và không truy vấn theo bệnh nhân khi
không có yêu cầu hợp lệ — ghi chú vận hành). Không có endpoint ghi/sửa/xóa (AC-09.2); ghi chỉ qua event.

| # | Method + đường dẫn | Mục đích |
|---|---|---|
| A1 | `GET /api/v1/audit/events` | Tra cứu |
| A2 | `POST /api/v1/audit/exports` | Tạo báo cáo xuất |
| A3 | `GET /api/v1/audit/exports/{id}` | Trạng thái/ link tải (SAS 5 phút) |
| A4 | `GET /api/v1/audit/integrity` | Kết quả kiểm tra chuỗi hash gần nhất |
| A5 | `GET /api/v1/audit/break-glass` | Danh sách break-glass chờ DPO xem xét |

### A1 `GET /api/v1/audit/events`
| Tham số | Ghi chú |
|---|---|
| `patientId` | tùy chọn |
| `actor` | `Practitioner/...` hoặc subject |
| `action`, `outcome`, `purposeOfUse` | enum (nhiều giá trị phân cách bằng phẩy) |
| `from`, `to` | **bắt buộc**, khoảng ≤ 90 ngày |
| `limit` ≤ 200, `cursor` | |

Response `{ "items": [ <bản ghi theo 05 §1.1> ], "nextCursor" }`. Truy vấn thực hiện trên Log Analytics (KQL) hoặc Loki; việc
tra cứu **cũng bị audit** (`action = audit-query`). Độ trễ dữ liệu ≤ 1 phút (NFR-SEC5).

### A2 `POST /api/v1/audit/exports`
`{ "from": "...", "to": "...", "patientId"?: "...", "format": "csv" }` → 202 `{ "exportId" }`. Job nền xuất file vào container
`audit-exports` (retention 7 ngày) và ghi audit `export`. Tối đa 3 export đồng thời/người dùng; khoảng ≤ 366 ngày.

### A5 `GET /api/v1/audit/break-glass`
Trạng thái `new` → `acknowledged` (`PUT .../{id}` với `{ "decision": "justified|unjustified", "note" }`, chỉ DPO). Mỗi lần
break-glass mới gửi thông báo cho DPO ≤ 5 phút (AC-10.1) qua notification (kênh email + push).

## 3. ML (`/api/v1/ml` + nội bộ)

ML hoạt động chủ yếu qua event (xem [04](04-db-notification-ml.md) §2.4). API công khai chỉ để quan sát:

| # | Method + đường dẫn | Mục đích | Quyền |
|---|---|---|---|
| M1 | `GET /api/v1/ml/predictions?appointment={id}` | Dự đoán đã lưu cho một lịch hẹn | N, A |
| M2 | `GET /api/v1/ml/model` | Phiên bản model đang chạy, metric lúc đăng ký | A |
| M3 | `POST /internal/v1/ml/predict` | Dự đoán đồng bộ (chỉ để kiểm thử/eval) | service nội bộ, CI |

### M1 response
```json
{ "appointmentId": "0192...", "score": 0.71, "threshold": 0.60, "highRisk": true,
  "modelName": "shp-no-show", "modelVersion": "7", "predictedAt": "2026-11-20T02:15:03Z",
  "features": { "lead_time_days": 21, "prior_noshow": 2, "prior_booked": 4, "start_hour": 8, "specialty": "cardiology" } }
```
(AC-15.2: mọi dự đoán lưu phiên bản model và đặc trưng.) 404 nếu chưa có dự đoán (ML tắt/lỗi — không ảnh hưởng nhắc lịch, AC-15.1).

### M3 `POST /internal/v1/ml/predict`
Body: vector đặc trưng (theo [04](04-db-notification-ml.md) §2.3) → `{ "score", "highRisk", "modelVersion" }`; p95 ≤ 100 ms.
Mô hình tải từ MLflow registry theo alias `production`; đổi alias (sau khi vượt cổng chất lượng AUC ≥ 0,75 và hơn baseline,
NFR-AI6) → pod tự nạp lại trong ≤ 60 giây mà không cần deploy lại.

Ngưỡng `threshold` là cấu hình (mặc định 0,60), chọn trên tập validation để đạt precision ≥ 0,5 ở recall tối đa; lưu cùng model version.
