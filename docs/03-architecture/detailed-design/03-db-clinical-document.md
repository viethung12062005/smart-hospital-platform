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
| `patients` | `mrn`, `nameTokens[]` (chữ thường, bỏ dấu), `phone` (E.164), `birthDate`, `gender`, `active`, `nationalIdEnc` (AES-256-GCM, tùy chọn), `nationalIdLast4` | `{search.mrn:1}` **unique** · `{search.phone:1, search.birthDate:1}` (chống trùng AC-01.2) · `{search.nationalIdHash:1}` **unique, partial** (chỉ khi có; HMAC-SHA-256, không lưu số rõ) · `{search.nameTokens:1, search.birthDate:1}` | `{_id: "hashed"}` |
| `practitioners` | `staffCode`, `nameTokens[]`, `active` | `{search.staffCode:1}` **unique** · `{search.nameTokens:1}` | không (nhỏ) |
| `practitioner_roles` | `practitionerId`, `specialty`, `code`, `active` | `{search.practitionerId:1}` · `{search.specialty:1, search.code:1, search.active:1}` | không |
| `encounters` | `status`, `practitionerId`, `appointmentId`, `periodStart`, `periodEnd`, `serviceType` | `{patientId:1, search.periodStart:-1}` · `{search.practitionerId:1, search.periodStart:-1}` · `{search.appointmentId:1}` **unique** (tạo Encounter từ event là idempotent) | `{patientId: "hashed"}` |
| `observations` | `encounterId`, `category`, `code`, `effective`, `interpretation` | `{patientId:1, search.category:1, search.effective:-1}` · `{search.encounterId:1}` | `{patientId: "hashed"}` |
| `conditions` | `encounterId`, `category`, `code`, `clinicalStatus`, `recordedDate` | `{patientId:1, search.recordedDate:-1}` · `{search.encounterId:1}` | `{patientId: "hashed"}` |
| `allergy_intolerances` | `clinicalStatus`, `criticality` | `{patientId:1}` | `{patientId: "hashed"}` |
| `resource_history` | — (`_id` = `<type>/<id>/<version>`, kèm `resourceType`, `id`, `versionId`, `operation`, `changedBy`, `changedAt`, `resource`) | `{resourceType:1, id:1, versionId:-1}` | `{patientId: "hashed"}` |
| `identity_links` | `_id` = OIDC subject; `issuer`, `fhirUser` (`Patient/..` hoặc `Practitioner/..`), `disabled`, `createdAt`, `createdBy` | `{fhirUser:1}` **unique** | không |
| `profile_links` | `accountSub`, `patientId`, `relationship` (`guardian` · `proxy`), `scope` (`booking` · `records` · `full`), `status` (`pending` · `active` · `revoked` · `expired`), `establishedBy` (`otp` · `counter` · `guardian-declaration`), `consentRef`, `expiresAt`, `createdAt`, `revokedAt` | `{accountSub:1, status:1}` · `{patientId:1, status:1}` · `{accountSub:1, patientId:1}` **unique** | không |
| `link_attempts` | `accountSub`, `kind` (`claim` · `link`), `phoneHash`, `at` | `{accountSub:1, at:1}` · `{phoneHash:1, at:1}` · TTL 24 giờ trên `at` | không |
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

### 2.7 Collection nền tảng: `outbox`, `inbox`, `idempotency_keys`

Định nghĩa chung cho **mọi** database MongoDB (clinical, document, notification, audit, ml, ai). Các tài liệu 04, 05, 06 tham chiếu mục này.
Bản PostgreSQL tương đương (bảng `outbox`, `inbox`, `idempotency_key`) ở [02](02-db-scheduling.md).

| Collection | Trường | Index / TTL | Thư viện |
|---|---|---|---|
| `outbox` | `_id` = event id (UUIDv7); `eventType` (`^shp\.[a-z]+\.[a-z-]+\.v[0-9]+$`), `subject`, `payload` (CloudEvent đầy đủ), `occurredAt`, `publishedAt` (`null` khi chưa phát), `attempts` | `ix_outbox_unpublished`: `{occurredAt:1}` partial `publishedAt: null` | Java `shp-data-mongo` · Python `shp_platform.mongo` |
| `inbox` | `_id` = `<consumer>\|<messageId>`, `processedAt` | `ttl_inbox_14d`: **TTL** 14 ngày trên `processedAt` | Java `shp-data-mongo` · Python `shp_platform.mongo` |
| `idempotency_keys` | `_id` = `<subject>\|<key>` (`subject` = OIDC `sub` của người gọi, `key` = header `Idempotency-Key`, 8–128 ký tự); `operation` (tên thao tác, vd. `create-upload-session`); `requestHash` (SHA-256 của body, kiểu Binary); `resourceId` (UUIDv7 của tài nguyên được tạo); `createdAt` | `ttl_idempotency_24h`: **TTL** 24 giờ trên `createdAt` | Java `MongoIdempotency` (SHR-12). Python dùng collection `idempotency` khác schema, xem [06](06-data-ai.md) §5.1 |

Quy tắc `idempotency_keys` (cùng ngữ nghĩa với PostgreSQL và [07](07-api-conventions.md)):
- Ghi bản ghi **trong cùng transaction** với thay đổi dữ liệu; request song song cùng key gây xung đột, lần thử lại thấy bản ghi đã commit.
- Cùng key + cùng `operation` + cùng `requestHash` trong 24 giờ → trả lại đúng tài nguyên cũ (`resourceId`), không tạo mới.
- Cùng key nhưng khác body hoặc khác thao tác → **422** `idempotency-key-reused`.
- Khóa gồm `subject`, nên key của người này không bao giờ trả về tài nguyên của người khác. Không lưu body, chỉ lưu hash (không chứa PII).

Database nào có collection nào (chỉ tạo collection mà database thật sự dùng):

| Database | `outbox` | `inbox` | `idempotency_keys` | Lý do |
|---|---|---|---|---|
| scheduling (PostgreSQL) | ✓ | ✓ | ✓ (`idempotency_key`) | Phát E1–E5; nhận E6, E7; `$book`, `$cancel`, `$reschedule` bắt buộc key |
| clinical | ✓ | ✓ | — | Phát E6, E7; nhận E1–E5; không có API bắt buộc key |
| document | ✓ | ✓ | ✓ | Phát E8, E9; nhận E7; D1 tạo upload session bắt buộc key |
| notification | ✓ | ✓ | — | Phát E11; nhận E1–E3, E7, E9, E10 |
| audit | ✓ | ✓ | — | Nhận E11; phát E11 khi export audit ([11](11-api-notification-audit-ml.md)) |
| ml | ✓ | ✓ | — | Phát E10; nhận E1–E3, E5, E7 |
| ai | ✓ | ✓ | ✓ (`idempotency`, biến thể có trạng thái) | Phát E11; nhận E8; AI4, AI6, AI8 bắt buộc key ([06](06-data-ai.md) §5.1) |

Mọi service còn phát E11 `audit.recorded` qua `outbox` của chính mình ([13](13-events.md)).

## 3. Database `document`

| Collection | Trường | Index |
|---|---|---|
| `document_references` | `_id`, `versionId`, `lastUpdated`, `patientId`, `resource` (FHIR DocumentReference), `processing.state` (`pending-upload` · `scanning` · `available` · `quarantined`), `processing.scanResult`, `processing.scannedAt`, `blob` (`container`, `path`, `contentType`, `size`, `sha256`), `releasedToPatient` (bool), `search` (`encounterId`, `type`, `date`, `authorId`) | `{patientId:1, search.date:-1}` · `{search.encounterId:1}` · `{processing.state:1, lastUpdated:1}` |
| `upload_sessions` | `_id` (upload id), `documentId`, `patientId`, `createdBy`, `blobPath`, `maxSize`, `allowedTypes[]`, `state` (`open` · `completed` · `expired`), `expiresAt` | **TTL** trên `expiresAt` (15 phút) |
| `resource_history` | như database `clinical` | như trên |
| `outbox`, `inbox`, `idempotency_keys` | như §2.7 | như §2.7 |

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
