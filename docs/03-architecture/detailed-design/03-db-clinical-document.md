# 03 — Thiết kế DB: clinical và document (MongoDB)

Azure Cosmos DB for MongoDB vCore (local: `mongo:8` chạy replica set một node để có transaction).
Hai database tách biệt trong cùng cluster: `clinical` (clinical service) và `document` (document service). Mỗi service có
user DB riêng, chỉ có quyền trên database của mình.

## 1. Nguyên tắc

- **Mỗi resource type một collection**. Document gồm phần quản trị (ID, version, khóa phân vùng), `resource` là JSON FHIR
  đầy đủ, và `search` là các trường đã chuẩn hóa để tìm kiếm (chỉ server ghi).
- **Khóa phân vùng (shard key) `patientId`** cho mọi collection theo bệnh nhân: timeline của một bệnh nhân chỉ đọc một
  partition. MVP chạy một shard; thiết kế sẵn sàng scale ngang.
- Optimistic concurrency bằng `versionId`; mọi ghi resource + history + outbox nằm trong **một transaction**.
- Write concern `majority`; đọc từ primary (bảo đảm bác sĩ thấy ngay dữ liệu vừa ghi).

## 2. Database `clinical`

### 2.1 Hình dạng document chung

```json
{
  "_id": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f",
  "versionId": 3,
  "lastUpdated": { "$date": "2026-11-20T02:15:00Z" },
  "patientId": "0192f8a4-6c1e-7b2a-9f00-1a2b3c4d5e6f",
  "resource": { "resourceType": "Patient", "id": "0192f8a4-...", "meta": { "versionId": "3", "lastUpdated": "2026-11-20T02:15:00Z" } },
  "search": { },
  "deleted": false
}
```

### 2.2 Collection và index

| Collection | `search` (trường chuẩn hóa) | Index | Shard key |
|---|---|---|---|
| `patients` | `mrn`, `nameTokens[]` (chữ thường, bỏ dấu), `phone` (E.164), `birthDate`, `gender`, `active` | `{search.mrn:1}` **unique** · `{search.phone:1, search.birthDate:1}` (chống trùng AC-01.2) · `{search.nameTokens:1, search.birthDate:1}` | `{_id: "hashed"}` |
| `practitioners` | `staffCode`, `nameTokens[]`, `active` | `{search.staffCode:1}` **unique** · `{search.nameTokens:1}` | không (nhỏ) |
| `practitioner_roles` | `practitionerId`, `specialty`, `code`, `active` | `{search.practitionerId:1}` · `{search.specialty:1, search.code:1, search.active:1}` | không |
| `encounters` | `status`, `practitionerId`, `appointmentId`, `periodStart`, `periodEnd`, `serviceType` | `{patientId:1, search.periodStart:-1}` · `{search.practitionerId:1, search.periodStart:-1}` · `{search.appointmentId:1}` **unique** (tạo Encounter từ event là idempotent) | `{patientId: "hashed"}` |
| `observations` | `encounterId`, `category`, `code`, `effective`, `interpretation` | `{patientId:1, search.category:1, search.effective:-1}` · `{search.encounterId:1}` | `{patientId: "hashed"}` |
| `conditions` | `encounterId`, `category`, `code`, `clinicalStatus`, `recordedDate` | `{patientId:1, search.recordedDate:-1}` · `{search.encounterId:1}` | `{patientId: "hashed"}` |
| `allergy_intolerances` | `clinicalStatus`, `criticality` | `{patientId:1}` | `{patientId: "hashed"}` |
| `resource_history` | — (`_id` = `<type>/<id>/<version>`, kèm `resourceType`, `id`, `versionId`, `operation`, `changedBy`, `changedAt`, `resource`) | `{resourceType:1, id:1, versionId:-1}` | `{patientId: "hashed"}` |
| `identity_links` | `_id` = OIDC subject; `issuer`, `fhirUser` (`Patient/..` hoặc `Practitioner/..`), `disabled`, `createdAt`, `createdBy` | `{fhirUser:1}` **unique** | không |
| `care_relationships` | `_id` = `<practitionerId>\|<patientId>`; `practitionerId`, `patientId`, `sources[]` (`{type, id, status, at}`), `validUntil` | `{patientId:1}` · `{practitionerId:1, validUntil:-1}` | không |
| `break_glass_sessions` | `practitionerId`, `patientId`, `reason`, `startedAt`, `expiresAt` | `{practitionerId:1, patientId:1, expiresAt:-1}` · **TTL** trên `expiresAt` | không |
| `mrn_counters` | `_id` = năm, `seq` | (theo `_id`) | không |
| `outbox` | `_id` = event id; `eventType`, `subject`, `payload`, `occurredAt`, `publishedAt` | `{occurredAt:1}` partial `publishedAt: null` | không |
| `inbox` | `_id` = `<consumer>\|<messageId>`, `processedAt` | **TTL** 14 ngày trên `processedAt` | không |

### 2.3 Quy tắc chuẩn hóa tìm kiếm

- `nameTokens`: Unicode NFD → bỏ dấu → `đ`→`d` → chữ thường → tách theo khoảng trắng. Ví dụ "Nguyễn Văn An" →
  `["nguyen","van","an"]`.
- Tìm "nguyen va" (AC-06.1): mọi token trừ token cuối khớp chính xác, token cuối khớp tiền tố:
  `{ $and: [ { "search.nameTokens": "nguyen" }, { "search.nameTokens": { $regex: "^va" } } ] }`
  (regex có neo `^` nên dùng được index multikey).
- `phone`: chuẩn hóa về E.164 (`0901234567` → `+84901234567`).

### 2.4 Validation ở tầng DB (`$jsonSchema`, ví dụ `encounters`)

```json
{
  "$jsonSchema": {
    "bsonType": "object",
    "required": ["_id", "versionId", "lastUpdated", "patientId", "resource", "search"],
    "properties": {
      "_id":         { "bsonType": "string", "pattern": "^[0-9a-f-]{36}$" },
      "versionId":   { "bsonType": "int", "minimum": 1 },
      "lastUpdated": { "bsonType": "date" },
      "patientId":   { "bsonType": "string" },
      "resource": {
        "bsonType": "object",
        "required": ["resourceType", "status", "subject"],
        "properties": { "resourceType": { "enum": ["Encounter"] },
                        "status": { "enum": ["arrived", "in-progress", "finished", "cancelled"] } }
      },
      "search": { "bsonType": "object", "required": ["appointmentId", "practitionerId", "status"] }
    }
  }
}
```
Mỗi collection resource có validator tương tự (`validationLevel: strict`, `validationAction: error`). Validation
profile FHIR chi tiết (cardinality, value set) nằm ở tầng ứng dụng; validator DB chặn dữ liệu sai cấu trúc.

### 2.5 Thao tác ghi chính

| Thao tác | Cách làm |
|---|---|
| Tạo resource | Transaction: `insertOne` resource → `insertOne` history (`operation=create`) → `insertOne` outbox (nếu có event) |
| Cập nhật | Transaction: `updateOne({_id, versionId: expected}, {$set: {resource, search, lastUpdated}, $inc: {versionId: 1}})`; `matchedCount = 0` → **412** (If-Match sai) hoặc **409**; sau đó insert history + outbox |
| Sinh MRN | `findOneAndUpdate({_id: "2026"}, {$inc: {seq: 1}}, {upsert: true, returnDocument: "after"})` → `BN-2026-000123` |
| Tạo Encounter từ event check-in | `insertOne` với `search.appointmentId`; lỗi trùng khóa unique = event đã xử lý → bỏ qua (idempotent) |
| Cập nhật quan hệ điều trị | `updateOne({_id: "<prac>\|<pat>"}, {$set/$pull/$push sources...}, {upsert: true})`, rồi tính lại `validUntil = max(sources.at của lịch hẹn chưa hủy) + 365 ngày` |

### 2.6 Truy vấn chính

| Truy vấn | Bộ lọc | Index |
|---|---|---|
| Timeline bệnh nhân (NFR-P3 ≤ 500 ms) | 4 truy vấn song song theo `patientId` trên `encounters`, `observations`, `conditions`, `allergy_intolerances` (+ gọi document service), sắp theo thời gian, giới hạn 50 mục/trang | index `{patientId, <time>}` của từng collection |
| Kiểm tra quan hệ điều trị | `findOne({_id: "<prac>\|<pat>", validUntil: {$gt: now}})` hoặc phiên break-glass còn hạn | `_id`, index break-glass |
| Chống trùng khi đăng ký | `find({search.phone, search.birthDate, search.active: true})` | `{search.phone, search.birthDate}` |
| Danh sách bệnh nhân hôm nay của bác sĩ | `encounters.find({search.practitionerId, search.periodStart: {$gte: today}})` | `{search.practitionerId, search.periodStart}` |

## 3. Database `document`

| Collection | Trường | Index |
|---|---|---|
| `document_references` | `_id`, `versionId`, `lastUpdated`, `patientId`, `resource` (FHIR DocumentReference), `processing.state` (`pending-upload` · `scanning` · `available` · `quarantined`), `processing.scanResult`, `processing.scannedAt`, `blob` (`container`, `path`, `contentType`, `size`, `sha256`), `releasedToPatient` (bool), `search` (`encounterId`, `type`, `date`, `authorId`) | `{patientId:1, search.date:-1}` · `{search.encounterId:1}` · `{processing.state:1, lastUpdated:1}` |
| `upload_sessions` | `_id` (upload id), `documentId`, `patientId`, `createdBy`, `blobPath`, `maxSize`, `allowedTypes[]`, `state` (`open` · `completed` · `expired`), `expiresAt` | **TTL** trên `expiresAt` (15 phút) |
| `resource_history`, `outbox`, `inbox` | như database `clinical` | như trên |

Shard key `document_references`: `{patientId: "hashed"}`.

## 4. Dung lượng, backup, quyền

| Hạng mục | Giá trị |
|---|---|
| Kích thước năm 1 | clinical ~10 GB (kèm history, index); document metadata < 1 GB (file nằm trên Blob) |
| Backup | Cosmos DB for MongoDB vCore: backup liên tục, khôi phục theo thời điểm 7 ngày (diễn tập ở PRD-03) |
| User DB | `clinical_app` chỉ `readWrite` trên `clinical`; `document_app` chỉ `readWrite` trên `document`; mật khẩu sinh bởi Terraform, lưu Key Vault, nạp qua CSI driver |
| Migration | Script tạo collection + validator + index, chạy idempotent bằng Kubernetes Job trước khi deploy |

## 5. Kiểm chứng thiết kế (đã chạy)

Đã chạy trên MongoDB 8 (replica set một node) ngày 26/10/2026:

| Kiểm tra | Kết quả |
|---|---|
| Validator `$jsonSchema` của `encounters` (mục 2.4) | Chấp nhận document hợp lệ; từ chối `status` ngoài value set (mã lỗi 121) |
| Unique `search.appointmentId` | Encounter thứ hai cho cùng lịch hẹn bị từ chối (mã 11000), nên tạo Encounter từ event là idempotent |
| Optimistic concurrency | Cập nhật với version đúng khớp 1 document; với version cũ khớp 0 document (→ 412/409) |
| Tìm "nguyen va" (mục 2.3) | Chỉ trả "Nguyễn Văn An"; kế hoạch truy vấn dùng `IXSCAN` |
| Transaction resource + outbox | Commit thành công |
