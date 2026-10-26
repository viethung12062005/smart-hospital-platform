# 10 — API document

Quy ước: [07-api-conventions.md](07-api-conventions.md). DB và Blob: [03](03-db-clinical-document.md) §3, [05](05-storage-audit-blob.md) §2.
Quyền: **P** Patient, **D** Doctor, **N** Nurse. Care/own như [15-authorization.md](15-authorization.md).

## 1. Tổng hợp endpoint

| # | Method + đường dẫn | Mục đích | Quyền | UC |
|---|---|---|---|---|
| D1 | `POST /api/v1/documents/uploads` | Bắt đầu upload, nhận SAS | D (Care), N | UC-05 |
| D2 | `POST /api/v1/documents/uploads/{uploadId}/complete` | Xác nhận đã upload xong | người đã tạo upload | UC-05 |
| D3 | `GET /fhir/DocumentReference/{id}` | Đọc metadata | P (own + đã phát hành), D (Care/BG), N | UC-05 |
| D4 | `GET /fhir/DocumentReference` | Tìm tài liệu | như D3 | UC-05, UC-06 |
| D5 | `POST /api/v1/documents/{id}/download-url` | Cấp link tải 5 phút | như D3 | UC-05 |
| D6 | `POST /api/v1/documents/{id}/release` | Phát hành cho bệnh nhân | D (Care) | UC-05 |
| D7 | `PUT /fhir/DocumentReference/{id}` | Đánh dấu nhập nhầm (`entered-in-error`) | D (Care, người tải lên) | UC-05 |
| D8 | `GET /internal/v1/documents?patient={id}` | Danh sách cho timeline | service `clinical` | UC-06 |
| D9 | `POST /internal/v1/documents/{id}/read-url` | Link đọc cho OCR (60 giây) | service `ai` | UC-13 |

## 2. Upload (hai bước, file đi thẳng lên Blob)

### D1 `POST /api/v1/documents/uploads`
Header: `Idempotency-Key`. Body:
```json
{
  "patientId": "0192f8a4-...",
  "encounterId": "0192f8a4-...",
  "type": "11502-2",
  "title": "Kết quả xét nghiệm máu 20/11/2026",
  "description": "Công thức máu, đường huyết",
  "fileName": "xet-nghiem-mau.pdf",
  "contentType": "application/pdf",
  "size": 482113,
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
}
```
Kiểm tra tại chỗ, **trước khi cấp SAS**:
| Điều kiện | HTTP / `code` |
|---|---|
| `contentType` không thuộc `application/pdf`, `image/jpeg`, `image/png` | 415 `invalid-file-type` (AC-05.1) |
| `size` > 20.971.520 byte hoặc ≤ 0 | 413 `file-too-large` (AC-05.1) |
| `type` ngoài value set; `fileName` có ký tự đường dẫn | 400 `validation-failed` |
| Người gọi không có Care với bệnh nhân (D) hoặc `encounterId` không thuộc bệnh nhân | 403 `no-care-relationship` / 400 |
| 429 | quá 30 upload/giờ/người dùng |

201:
```json
{
  "uploadId": "0192f8a4-...",
  "documentId": "0192f8a4-...",
  "upload": {
    "method": "PUT",
    "url": "https://stshpdocs....blob.core.windows.net/documents/patients/.../original.pdf?<user-delegation-sas>",
    "headers": { "x-ms-blob-type": "BlockBlob", "Content-Type": "application/pdf" },
    "expiresAt": "2026-11-20T02:25:00Z"
  }
}
```
SAS chỉ cho `create + write` đúng một blob, hiệu lực 10 phút, ràng buộc `Content-Type`; ghi `upload_sessions` (TTL 15 phút).
Document được tạo với `docStatus = preliminary`, `processing.state = pending-upload`.

### D2 `POST /api/v1/documents/uploads/{uploadId}/complete`
Server kiểm blob thật sự tồn tại, **`size` và SHA-256 khớp** giá trị khai báo, `contentType` thật (đọc magic bytes) khớp.
| Kết quả | |
|---|---|
| 202 | `processing.state = scanning`; trả `{ "documentId", "state": "scanning" }` |
| 422 `upload-incomplete` | Chưa có blob, hoặc size/hash/type không khớp (blob bị xóa) |
| 409 | Phiên đã `completed` hoặc hết hạn (`expired`) |

Khi trình quét trả kết quả: sạch → `available`, `docStatus = final`, phát `shp.document.document-uploaded.v1`;
nhiễm → chuyển blob sang container `quarantine`, `quarantined`, phát audit và cảnh báo (không phát `document-uploaded`).
Client theo dõi bằng `GET /fhir/DocumentReference/{id}` (không trả `content` cho tới khi `available`).

## 3. Đọc và tìm

### D3 `GET /fhir/DocumentReference/{id}`
Trả metadata, **không có URL tải**. `content[0].attachment` có `contentType`, `size`, `hash`, `title`. Patient chỉ thấy khi
`released-to-patient` đã đặt và là tài liệu của mình, ngược lại 404. Tài liệu `quarantined`/`pending-upload` chỉ người tải lên và Admin bảo mật thấy trạng thái.

### D4 `GET /fhir/DocumentReference`
Tham số: `patient` (**bắt buộc**), `encounter`, `type`, `date` (ge/le), `status`, `_count`, `_page_token`. Patient bị ép
`patient = mình` và chỉ tài liệu đã phát hành.

### D5 `POST /api/v1/documents/{id}/download-url`
Header: `Idempotency-Key` không cần (không thay đổi trạng thái). Response:
```json
{ "url": "https://stshpdocs....blob.core.windows.net/documents/...?<sas>", "expiresAt": "2026-11-20T02:20:00Z", "fileName": "xet-nghiem-mau.pdf", "contentType": "application/pdf" }
```
- SAS **read-only cho đúng một blob, hiệu lực 5 phút**, `Content-Disposition: attachment` (BR-08, AC-05.2): sau 5 phút Blob trả 403.
- Chỉ cấp khi `processing.state = available`; ngược lại 409 `document-not-available`.
- **Mỗi lần cấp** phát `audit-recorded` (`download-url`) — kể cả bị từ chối.
- Giới hạn 30 lần/phút/người dùng.

### D6 `POST /api/v1/documents/{id}/release`
Body `{ "note": "Kết quả bình thường" }` tùy chọn. Đặt `released-to-patient` (người, thời điểm), phát
`shp.document.document-released.v1` → notification gửi "có kết quả mới" (UC-07). 409 nếu đã phát hành hoặc chưa `available`.
Không thu hồi được sau khi phát hành (chỉ `entered-in-error`).

## 4. API nội bộ

| Endpoint | Gọi bởi | Ghi chú |
|---|---|---|
| D8 `GET /internal/v1/documents?patient={id}&limit=` | clinical | metadata đã rút gọn để dựng timeline |
| D9 `POST /internal/v1/documents/{id}/read-url` | ai | SAS đọc 60 giây cho đúng blob của job trích xuất; audit actor `system` |

## 5. Xử lý lỗi và dọn dẹp

| Tình huống | Hành vi |
|---|---|
| Client upload xong nhưng không gọi `complete` | Blob được dọn sau khi `upload_sessions` hết hạn (job mỗi giờ xóa blob mồ côi + `pending-upload` > 24 giờ) |
| Trình quét lỗi/timeout | Retry 3 lần; vẫn lỗi → giữ `scanning`, cảnh báo vận hành (OBS-03); tài liệu **không** được cấp link tải |
| Upload cùng `Idempotency-Key` hai lần | Trả lại cùng `documentId`/SAS chưa hết hạn |
