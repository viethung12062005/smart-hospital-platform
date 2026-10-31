# 09 — API clinical

Quy ước: [07-api-conventions.md](07-api-conventions.md). DB: [03-db-clinical-document.md](03-db-clinical-document.md).
Ký hiệu: **P** Patient, **D** Doctor, **N** Nurse, **A** Admin, **DPO**. **Care** = có quan hệ điều trị
([15-authorization.md](15-authorization.md)); **BG** = hoặc đang có phiên break-glass còn hạn.

## 1. Tổng hợp endpoint

| # | Method + đường dẫn | Mục đích | Quyền | UC |
|---|---|---|---|---|
| C1 | `POST /api/v1/registration` | Bệnh nhân tự đăng ký | tài khoản mới (token OIDC chưa có `fhirUser`) | UC-01 |
| C2 | `GET /api/v1/me` | Tôi là ai (role, `fhirUser`) | mọi người dùng | UC-08 |
| C3 | `POST /fhir/Patient` | Nurse tạo hồ sơ tại quầy | N | UC-01 |
| C4 | `GET /fhir/Patient/{id}` | Đọc hồ sơ | P (own), D (Care/BG), N | UC-01 |
| C5 | `PUT /fhir/Patient/{id}` | Sửa hồ sơ | P (own, trường hành chính), N | UC-01 |
| C6 | `POST /fhir/Patient/_search` | Tìm bệnh nhân | D, N | UC-06 |
| C7 | `GET /fhir/Patient/{id}/_history` | Lịch sử phiên bản | N | UC-01 |
| C8 | `POST /fhir/Patient/{id}/$merge` | Gộp hồ sơ trùng | N | UC-01 |
| C9 | `GET /fhir/Practitioner`, `/{id}`, `GET /fhir/PractitionerRole` | Danh bạ bác sĩ | mọi người dùng đã đăng nhập | UC-03 |
| C10 | `POST /fhir/Practitioner`, `POST /fhir/PractitionerRole`, `PUT` | Quản lý nhân sự | A | UC-08 |
| C11 | `POST /fhir/Encounter/{id}/$start` | Bác sĩ mở lần khám | D (Care) | UC-04 |
| C12 | `GET /fhir/Encounter/{id}`, `GET /fhir/Encounter` | Đọc/tìm lần khám | P (own, đã `finished`), D (Care/BG), N | UC-04 |
| C13 | `PUT /fhir/Encounter/{id}` | Ghi bệnh sử/lý do khám | D (Care, trước `finished`) | UC-04 |
| C14 | `POST /fhir/Encounter/{id}/$finish` | Kết thúc lần khám | D (Care) | UC-04 |
| C15 | `POST /fhir/Encounter/{id}/$addendum` | Bổ sung sau khi kết thúc | D (Care) | UC-04 |
| C16 | `POST /fhir/Observation`, `GET`, `GET /{id}` | Sinh hiệu / kết quả | ghi: D (Care), N; đọc: P (own), D (Care/BG), N | UC-04 |
| C17 | `POST /fhir/Condition`, `GET`, `PUT` | Chẩn đoán / bệnh nền | ghi: D (Care); đọc: P (own), D (Care/BG), N | UC-04 |
| C18 | `POST /fhir/AllergyIntolerance`, `GET`, `PUT` | Dị ứng | ghi: D (Care), N; đọc như trên | UC-04 |
| C19 | `GET /api/v1/clinical/patients/{id}/timeline` | Timeline bệnh nhân | D (Care/BG), N | UC-06 |
| C20 | `POST /api/v1/clinical/break-glass` | Truy cập khẩn cấp | D | UC-10 |
| C21 | `GET/POST /api/v1/admin/users`, `PUT /api/v1/admin/users/{subject}` | Liên kết/khóa tài khoản | A | UC-08 |

## 2. Bệnh nhân

### C1 `POST /api/v1/registration`
Người dùng đã tạo tài khoản OIDC (role `patient`) nhưng chưa có hồ sơ. Body:
```json
{
  "name": { "family": "Nguyễn", "given": ["Văn", "An"] },
  "phone": "0901234567",
  "email": "an.nguyen@example.org",
  "gender": "male",
  "birthDate": "1985-03-14",
  "address": { "city": "Hà Nội", "district": "Cầu Giấy" },
  "language": "vi"
}
```
| HTTP | Điều kiện | `code` |
|---|---|---|
| 201 | Tạo `Patient`, sinh MRN, tạo `identity_links` cho `sub`; body `{ "patientId", "mrn" }` | |
| 400 | `phone` không phải số VN hợp lệ; `birthDate` ở tương lai hoặc tuổi > 120 | `validation-failed` |
| 409 | Token này đã có hồ sơ | `already-registered` |
| **422** | Đã có hồ sơ **cùng SĐT + ngày sinh** (AC-01.2): **không tạo mới**, trả `{ "code": "duplicate-patient-suspected" }` kèm hướng dẫn đến quầy để điều dưỡng xác minh; không lộ dữ liệu của hồ sơ trùng | `duplicate-patient-suspected` |

### C3 `POST /fhir/Patient`
Nurse tạo cho bệnh nhân chưa có tài khoản. Cùng kiểm tra trùng; Nurse có thể gửi `?forceCreate=true` + header
`X-Duplicate-Override-Reason` sau khi đã xác minh trực tiếp (ghi audit). 201 trả `Patient` có `identifier[mrn]`.

### C5 `PUT /fhir/Patient/{id}`
`If-Match` bắt buộc. Patient chỉ được sửa `telecom`, `address`, `contact`, `communication`; sửa `name`, `birthDate`,
`gender`, `identifier` → 403. **Số CCCD** (`identifier[cccd]`) chỉ Nurse đặt được, kèm `extension` đồng ý và (khi lệch giới tính hoặc năm sinh) lý do; 422 `duplicate-patient-suspected` nếu đã thuộc hồ sơ khác (so bằng băm), 400 `validation-failed` nếu không đủ 12 chữ số. Phản hồi chỉ trả bản che; xem đủ số cần `?reveal=national-id` và phát audit mỗi lần. Mỗi lần sửa tạo version mới trong `resource_history` (AC-01.3). 412 nếu version cũ.

### C6 `POST /fhir/Patient/_search` (form-urlencoded)
| Tham số | Ghi chú |
|---|---|
| `name` | tìm không dấu, token cuối khớp tiền tố (AC-06.1); tối thiểu 2 ký tự |
| `phone` | khớp đúng sau chuẩn hóa E.164 |
| `identifier` | `urn:shp:mrn\|BN-2026-000123` |
| `birthdate` | `eq` theo ngày |
| `_count`, `_page_token` | |

Bắt buộc có ít nhất một tham số. Response mỗi `Patient` **rút gọn**: `id`, `identifier[mrn]`, `name`, `birthDate` (chỉ **năm**
nếu người gọi là Doctor không có Care), `gender`. Doctor không Care vẫn thấy kết quả tìm nhưng mở hồ sơ (C4) thì 403
`no-care-relationship` (AC-06.2). Mỗi lần tìm ghi audit `search` (không ghi giá trị tìm).

### C8 `POST /fhir/Patient/{id}/$merge`
Body `{ "target": Reference(Patient) }`: `{id}` thành `active=false`, `link[replaced-by] → target`; các resource lâm sàng cũ
được chuyển sang `target` bằng job bất đồng bộ (phát `patient-merged.v1`; scheduling/notification cập nhật tham chiếu).
409 nếu một trong hai đã bị gộp. Cả hai hồ sơ giữ version history.

## 3. Lần khám và dữ liệu lâm sàng

`Encounter` được **tạo tự động** khi nhận `appointment-checked-in.v1` (status `arrived`); không có `POST /fhir/Encounter` công khai.

### C11 `POST /fhir/Encounter/{id}/$start`
`arrived → in-progress`, `attender` = bác sĩ gọi. 403 nếu không phải bác sĩ của lịch hẹn; 409 nếu trạng thái khác `arrived`.

### C13 `PUT /fhir/Encounter/{id}`
Chỉ được sửa `reasonCode`, `extension[clinical-note]`, `diagnosis`. `If-Match` bắt buộc. **409 `invalid-state-transition`**
nếu `status = finished` (AC-04.2) — dùng `$addendum`.

### C14 `POST /fhir/Encounter/{id}/$finish`
Điều kiện: có ít nhất 1 `Condition` loại `encounter-diagnosis` (422 `diagnosis-required`). Hiệu ứng: `status=finished`,
`period.end`; phát `shp.clinical.encounter-finished.v1` (scheduling chuyển Appointment sang `fulfilled`; ml cập nhật kết quả).

### C15 `POST /fhir/Encounter/{id}/$addendum`
Body `{ "text": "..." }` (1–4.000 ký tự). Thêm một phần tử `extension[addendum]` (tác giả, thời điểm); không sửa bản gốc.
Chỉ khi `finished`; 409 nếu chưa kết thúc.

### C16 Observation
`POST /fhir/Observation` — kiểm tra theo profile ([01](01-domain-model.md) §4.7): sinh hiệu bắt buộc `encounter`; đơn vị
đúng bảng; giá trị trong khoảng hợp lý (nhiệt độ 30–45 °C, mạch 20–250, SpO2 50–100, huyết áp tâm thu 40–300 / tâm trương
20–200). Ngoài khoảng → 422 `value-out-of-range`. `interpretation` do server tính. Sinh hiệu: N hoặc D (Care); xét nghiệm
tay: D (Care). Tìm: `patient`, `encounter`, `category`, `code`, `date`; Patient chỉ thấy quan sát thuộc Encounter đã `finished` của mình.

### C17 Condition
Chỉ D có Care (`recorder` = người gọi) → 403 `no-care-relationship` (AC-04.1). `code` phải là mã ICD-10 hợp lệ (kiểm
theo bảng mã nạp sẵn, 422 `invalid-code`). Hủy chẩn đoán sai: `PUT` với `verificationStatus=entered-in-error`, không xóa.

### C19 `GET /api/v1/clinical/patients/{id}/timeline`
| Tham số | Ghi chú |
|---|---|
| `types` | `encounter,observation,condition,allergy,document` (mặc định tất cả) |
| `from`, `to` | khoảng thời gian (ISO 8601) |
| `limit` ≤ 50, `cursor` | keyset theo (`time desc`, `id`) |

Response:
```json
{
  "items": [
    { "time": "2026-11-20T02:15:00Z", "type": "encounter", "id": "0192...", "title": "Khám Tim mạch", "summary": "Đau ngực khi gắng sức",
      "practitioner": { "id": "0192...", "display": "BS. Lê Minh Quân" }, "ref": "Encounter/0192..." },
    { "time": "2026-11-20T02:20:00Z", "type": "observation", "id": "0192...", "title": "Huyết áp", "summary": "128/82 mmHg", "ref": "Observation/0192..." }
  ],
  "nextCursor": "..."
}
```
Server gọi song song 4 truy vấn Mongo (theo `patientId`) và document service (`/internal/v1/documents?patient=`) với timeout
300 ms; document lỗi → vẫn trả phần còn lại kèm `partial: ["document"]` (xuống cấp có kiểm soát). Mục tiêu p95 ≤ 500 ms (NFR-P3). Ghi audit `read`.

### C20 `POST /api/v1/clinical/break-glass`
Body `{ "patientId": "0192...", "reason": "Cấp cứu tại khoa, bệnh nhân bất tỉnh, không có người nhà" }` (20–500 ký tự).
Chỉ D **không** có Care. Tạo phiên 60 phút (`break_glass_sessions`); 201 `{ "expiresAt" }`. Phát `audit-recorded` hành động
`break-glass-start` (purpose `ETREAT`) → audit service báo DPO ≤ 5 phút (AC-10.1). Mỗi lần đọc trong phiên đều audit
`ETREAT`. 400 nếu `reason` quá ngắn; 409 nếu đã có Care hoặc phiên còn hạn.

## 4. API nội bộ (không qua gateway)

| Endpoint | Gọi bởi | Trả về |
|---|---|---|
| `GET /internal/v1/identity/{subject}` | mọi service | `{ "fhirUser": "Patient/...", "roles": [...], "disabled": false }`; 404 nếu chưa liên kết |
| `GET /internal/v1/care-relationships/check?practitioner={id}&patient={id}` | document, ai, notification | `{ "care": true, "via": "appointment|encounter|break-glass", "validUntil": "..." }` |
| `GET /internal/v1/practitioners/{id}/roles` | scheduling | danh sách `PractitionerRole` (chuyên khoa, hoạt động) |
| `GET /internal/v1/patients/{id}/contact` | notification | `{ "phone", "email", "locale" }` — **endpoint duy nhất trả thông tin liên hệ**; chỉ role `internal.notification`, mỗi lần gọi có audit `read` |
| `GET /internal/v1/patients/{id}/age-band` | scheduling (khi tạo event `appointment-booked`) | `{ "ageBand": "30-39" }` |

## 5. Quản trị người dùng (C21)

`POST /api/v1/admin/users` `{ "subject": "<OIDC sub>", "fhirUser": "Practitioner/0192...", "roles": ["doctor"] }` liên kết
tài khoản nhân viên với `Practitioner` (phải khớp `PractitionerRole.code`); `PUT .../{subject}` `{ "disabled": true }` khóa
tài khoản. Cả hai ghi audit; khóa có hiệu lực ≤ 60 giây do cache identity (AC-08.1). Admin **không** đọc được dữ liệu lâm sàng.
