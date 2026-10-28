# 07 — Quy ước API

## 1. Điểm vào và định tuyến

Client chỉ gọi **API Gateway**: `https://api.<env>.shp.example.org`. Gateway định tuyến theo bảng sau; mọi đường dẫn
`/internal/*` **không** được định tuyến ra ngoài (chỉ gọi được trong cluster, bị NetworkPolicy giới hạn).

| Đường dẫn | Service |
|---|---|
| `/fhir/Patient*`, `/fhir/Practitioner*`, `/fhir/PractitionerRole*`, `/fhir/Encounter*`, `/fhir/Observation*`, `/fhir/Condition*`, `/fhir/AllergyIntolerance*` | clinical |
| `/fhir/Schedule*`, `/fhir/Slot*`, `/fhir/Appointment*` | scheduling |
| `/fhir/DocumentReference*` | document |
| `/api/v1/registration`, `/api/v1/me`, `/api/v1/clinical/*`, `/api/v1/admin/*` | clinical |
| `/api/v1/scheduling/*` | scheduling |
| `/api/v1/documents/*` | document |
| `/api/v1/notifications/*` | notification |
| `/api/v1/audit/*` | audit |
| `/api/v1/ai/*` | ai |
| `/api/v1/ml/*` | ml |

Hai kiểu API:
- **FHIR REST** (`/fhir/...`): `Content-Type: application/fhir+json`, lỗi trả `OperationOutcome`.
- **API ứng dụng** (`/api/v1/...`): cho thao tác không có trong FHIR (upload, AI, audit...); `application/json`, lỗi trả
  **Problem Details** (RFC 9457, `application/problem+json`) có trường `code` theo danh mục ở §5.

## 2. Xác thực

- `Authorization: Bearer <access token>` OIDC (Entra ID trên cloud, Keycloak local). Token sống ≤ 15 phút.
- Gateway kiểm chữ ký, `iss`, `aud`, `exp`; **service kiểm lại** (phòng thủ nhiều lớp, không tin header từ gateway).
- Role lấy từ claim `roles` (Entra app roles / Keycloak client roles): `patient`, `doctor`, `nurse`, `admin`, `dpo`.
- `fhirUser` (ví dụ `Patient/0192...`) **không** nằm trong token; service tra theo `sub` qua clinical
  (`/internal/v1/identity/{subject}`), cache 60 giây. Tài khoản bị khóa bị từ chối trong ≤ 60 giây (AC-08.1). Chi tiết ở
  [15-authorization.md](15-authorization.md).

## 3. Header

| Header | Hướng | Quy tắc |
|---|---|---|
| `Idempotency-Key` | request | **Bắt buộc** với `$book`, `$cancel`, `$reschedule`, xác nhận hành động chatbot, tạo upload session. 8–128 ký tự (khuyên dùng UUID). Lưu 24 giờ theo (người gọi, key). Gửi lại cùng key và cùng body → trả lại kết quả cũ; cùng key khác body → 422 `idempotency-key-reused` |
| `If-Match` | request | **Bắt buộc** với mọi `PUT` và thao tác đổi trạng thái (`$cancel`, `$reschedule`, `$check-in`, `$finish`): `W/"<versionId>"`. Thiếu → 428 `precondition-required`; sai version → 412 `version-conflict` |
| `ETag` | response | `W/"<versionId>"` trên mọi response trả một resource |
| `Location` | response | trên 201: `/fhir/<Type>/<id>/_history/<version>` |
| `traceparent` | cả hai | W3C Trace Context; gateway tạo nếu client không gửi |
| `X-Request-Id` | response | = trace id, để người dùng báo lỗi |
| `Accept-Language` | request | `vi` (mặc định) hoặc `en`, cho thông báo lỗi |
| `Retry-After` | response | trên 429 và 503 |

## 4. Đọc, tìm kiếm, phân trang

- Đọc: `GET /fhir/<Type>/<id>` → resource kèm `meta.versionId`, `meta.lastUpdated`. Resource đã xóa/không có quyền đọc
  → **404** (không lộ sự tồn tại), riêng trường hợp có quan hệ cần break-glass → 403 `break-glass-required`.
- Lịch sử: `GET /fhir/<Type>/<id>/_history` (chỉ với resource có lưu history).
- Tìm kiếm: `GET /fhir/<Type>?<param>=...` → `Bundle` `type=searchset`.
  - **Tìm theo dữ liệu cá nhân** (tên, SĐT, ngày sinh) **bắt buộc dùng `POST /fhir/Patient/_search`** với body
    `application/x-www-form-urlencoded`, để PII không xuất hiện trong URL và access log.
  - `_count`: mặc định 20, tối đa 100. Phân trang **keyset**: `Bundle.link[next]` chứa `_page_token` mờ (opaque), không
    dùng offset. `Bundle.total` không trả (tốn chi phí), trừ khi gửi `_total=accurate` ở endpoint cho phép.
  - Tham số không được hỗ trợ → **400** (strict). Mỗi endpoint liệt kê tham số hỗ trợ.
- API ứng dụng: `?limit=` (≤ 100) và `?cursor=`; response `{ "items": [...], "nextCursor": "..." }`.

## 5. Lỗi

| HTTP | Khi nào | `code` |
|---|---|---|
| 400 | Sai cú pháp, sai profile FHIR, tham số không hỗ trợ | `validation-failed`, `unsupported-parameter` |
| 401 | Thiếu/sai token | `unauthenticated` |
| 403 | Không có quyền | `forbidden`, `no-care-relationship`, `break-glass-required` |
| 404 | Không tồn tại hoặc không được phép biết sự tồn tại | `not-found` |
| 409 | Xung đột trạng thái | `slot-unavailable`, `slot-has-appointment`, `invalid-state-transition` |
| 412 | `If-Match` sai | `version-conflict` |
| 413 | File quá lớn | `file-too-large` |
| 415 | Loại file không cho phép | `invalid-file-type` |
| 422 | Vi phạm quy tắc nghiệp vụ | `max-future-appointments`, `outside-booking-window`, `cancellation-too-late`, `idempotency-key-reused`, `duplicate-patient-suspected`, `upload-incomplete`, `confirmation-expired` |
| 428 | Thiếu `If-Match` / `Idempotency-Key` | `precondition-required`, `idempotency-key-required` |
| 429 | Vượt giới hạn tốc độ | `rate-limited` |
| 503 | Phụ thuộc lỗi, đã hết fallback | `dependency-unavailable`, `ai-unavailable`, `ai-budget-exceeded` |

Mã `code` nằm trong `OperationOutcome.issue[].details.coding` (system `https://shp.example.org/fhir/CodeSystem/error`) hoặc
trường `code` của Problem Details. `diagnostics`/`detail` là thông điệp cho người dùng theo `Accept-Language`, **không chứa
PII hay stack trace**.

Ví dụ:
```json
{
  "resourceType": "OperationOutcome",
  "issue": [{
    "severity": "error",
    "code": "conflict",
    "details": { "coding": [{ "system": "https://shp.example.org/fhir/CodeSystem/error", "code": "slot-unavailable" }] },
    "diagnostics": "Khung giờ này vừa có người đặt. Vui lòng chọn khung giờ khác."
  }]
}
```

## 6. Giới hạn tốc độ (tại gateway, theo `sub`)

| Nhóm | Giới hạn |
|---|---|
| Mặc định | 120 request/phút, burst 30 |
| `$book`, `$reschedule` | 10/phút |
| `POST /fhir/Patient/_search` | 60/phút (chống dò dữ liệu) |
| AI (`/api/v1/ai/*`) | 20/phút, tối đa 3 stream đồng thời |
| Đăng ký (`/api/v1/registration`) | 5/giờ theo IP |

Lúc mở slot (07:00) khi tải vượt ngưỡng, gateway bật **waiting room**: trả 429 kèm `Retry-After` và vị trí hàng đợi (GW-03).

## 7. Timeout và streaming

| Loại | Timeout tại gateway |
|---|---|
| FHIR và API thường | 10 giây |
| AI (không stream) | 30 giây |
| AI stream (SSE `text/event-stream`) | 60 giây, heartbeat mỗi 15 giây |

## 8. API nội bộ (service ↔ service)

- Đường dẫn `/internal/v1/...`, cổng riêng của service; NetworkPolicy chỉ cho phép đúng service gọi được liệt kê trong từng
  tài liệu API.
- Xác thực bằng token của **workload identity** (client credentials, `aud` = app ID service đích) với app role
  `internal.<tên>`; không dùng token người dùng.
- Khi AI cần đọc dữ liệu bệnh nhân **thay mặt người dùng**, AI dùng **on-behalf-of** (Entra) / token exchange RFC 8693
  (Keycloak) để gọi API công khai, nên mọi kiểm tra quyền của người dùng vẫn áp dụng.

## 9. Health và metric

`GET /health/live`, `GET /health/ready` (kiểm DB/bus), `GET /metrics` (Prometheus) trên cổng quản trị, không đi qua gateway.

## 10. Versioning

- FHIR: R4 cố định. API ứng dụng: version trong URL (`/api/v1`); thay đổi phá vỡ tương thích → `/api/v2`, giữ v1 tối thiểu
  một sprint.
- Event: version trong tên (`...v1`), xem [13-events.md](13-events.md).
- Hợp đồng chính thức: file OpenAPI 3.1 cho từng service, sinh từ các tài liệu 08–12 (task ARC-06); CI kiểm tra code khớp hợp đồng.
