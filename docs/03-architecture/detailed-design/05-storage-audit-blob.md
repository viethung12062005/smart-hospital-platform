# 05 — Lưu trữ audit và Blob

## 1. Bản ghi audit

Mọi service phát event `shp.audit.recorded.v1` (qua outbox) cho **mỗi** thao tác đọc/ghi dữ liệu bệnh nhân, mỗi lần cấp
link tài liệu, mỗi truy vấn AI trên dữ liệu bệnh nhân và **mỗi lần bị từ chối (403)**. Audit service là consumer duy nhất.

### 1.1 Lược đồ bản ghi (không chứa nội dung lâm sàng)

| Trường | Kiểu | Ví dụ / ghi chú |
|---|---|---|
| `eventId` | uuid | = CloudEvents id (dùng để chống trùng) |
| `occurredAt` | datetime UTC | |
| `service` | string | `clinical` |
| `action` | enum | `read` · `search` · `create` · `update` · `delete` · `download-url` · `ai-query` · `ai-summary` · `break-glass-start` · `export` · `login` |
| `outcome` | enum | `success` · `denied` · `error` |
| `actor.subject` | string | OIDC subject |
| `actor.role` | enum | `patient` · `doctor` · `nurse` · `admin` · `dpo` · `system` |
| `actor.fhirUser` | string | `Practitioner/0192...` |
| `patientId` | uuid \| null | bệnh nhân bị truy cập (null nếu không liên quan bệnh nhân) |
| `resource` | string | `Encounter/0192...` hoặc `Patient?name=...` (với search chỉ ghi tên tham số, **không ghi giá trị**) |
| `purposeOfUse` | enum | `TREAT` (điều trị) · `ETREAT` (khẩn cấp, break-glass) · `HOPERAT` (vận hành) · `PATRQT` (bệnh nhân tự xem) |
| `reason` | string \| null | lý do break-glass hoặc lý do từ chối (`no-care-relationship`) |
| `traceId` | string | W3C trace id, nối với trace trong observability |
| `client.ip` | string | IP đã che 1 octet cuối |
| `client.userAgent` | string | rút gọn |

Có thể xuất sang FHIR `AuditEvent` khi cần trao đổi (ánh xạ 1-1 các trường trên).

### 1.2 Bản gốc bất biến (Blob WORM)

- Storage account `stshpaudit<suffix>`, container `audit`, **immutability policy theo thời gian** (prod: khóa, 10 năm theo
  giả định BR-11; non-prod: chưa khóa, 1 ngày để hủy được môi trường).
- Audit service gom bản ghi thành batch mỗi **60 giây hoặc 5.000 bản ghi**, ghi một **append blob** NDJSON:
  `audit/v1/yyyy/MM/dd/HH/<instance>-<sequence>.ndjson`.
- **Chuỗi hash** chống sửa/xóa lén: dòng đầu mỗi file là header
  `{"type":"batch-header","prevBatch":"<path>","prevHash":"<sha256>","count":N}`; hash của batch =
  `SHA-256(prevHash + nội dung các dòng)`, lưu vào blob metadata `batchHash` và làm `prevHash` cho batch kế tiếp
  (theo từng instance). Công cụ `audit-verify` (AUD-06) duyệt toàn chuỗi và báo đứt gãy.
- Chỉ audit service có quyền `Storage Blob Data Contributor` trên container; không ai có quyền xóa trong thời gian khóa (kể cả Owner).

### 1.3 Bảng truy vấn (Log Analytics)

Custom table `SHPAudit_CL`, ghi qua Logs Ingestion API (Data Collection Rule). Độ trễ mục tiêu ≤ 1 phút (NFR-SEC5).
Local: Loki với cùng nhãn.

| Cột | Kiểu |
|---|---|
| `TimeGenerated` | datetime |
| `EventId`, `Service`, `Action`, `Outcome`, `ActorSubject`, `ActorRole`, `ActorFhirUser`, `PatientId`, `Resource`, `PurposeOfUse`, `Reason`, `TraceId`, `ClientIp` | string |

Retention: 90 ngày tương tác + 2 năm lưu trữ (archive); bản gốc dài hạn nằm ở Blob WORM.

Truy vấn mẫu cho DPO (UC-09):

```kusto
// Ai đã xem hồ sơ bệnh nhân X trong 30 ngày qua?
SHPAudit_CL
| where TimeGenerated > ago(30d) and PatientId == "0192f8a4-..."
| summarize Lượt = count(), LầnCuối = max(TimeGenerated) by ActorFhirUser, ActorRole, Action, PurposeOfUse
| order by LầnCuối desc

// Tất cả lần break-glass trong tuần
SHPAudit_CL | where TimeGenerated > ago(7d) and Action == "break-glass-start"

// Dò quyền: người bị từ chối nhiều lần trong 1 giờ
SHPAudit_CL | where Outcome == "denied" | summarize n = count() by ActorSubject, bin(TimeGenerated, 1h) | where n > 20
```

## 2. Blob cho tài liệu lâm sàng

| Storage account | Container | Đường dẫn | Ghi chú |
|---|---|---|---|
| `stshpdocs<suffix>` | `documents` | `patients/{patientId}/{documentId}/v{version}/original.{pdf\|jpg\|png}` | Tắt shared key, chỉ danh tính managed identity (Entra); private endpoint; versioning + soft delete 14 ngày |
| | `quarantine` | như trên | File nhiễm mã độc chuyển sang đây; chỉ admin bảo mật đọc được |
| | `kb` | `kb/{kbDocumentId}/v{version}/{filename}` | Tài liệu gốc của kho tri thức RAG (ai service) |
| `stshpaudit<suffix>` | `audit` | xem §1.2 | WORM |

- **Upload**: client nhận **user delegation SAS** chỉ có quyền `create/write` cho đúng một blob, hiệu lực 10 phút, giới
  hạn `Content-Type`; client upload thẳng lên Blob (không qua API, không tốn băng thông service).
- **Tải về**: user delegation SAS chỉ `read` cho đúng một blob, **hiệu lực 5 phút** (BR-08), kèm
  `Content-Disposition: attachment`; mỗi lần cấp đều ghi audit `download-url`.
- **Quét mã độc**: Microsoft Defender for Storage (cloud) / ClamAV (local) quét khi blob được tạo; kết quả đẩy về document
  service qua Event Grid → queue (local: webhook giả lập).
- **Lifecycle**: `documents` chuyển Cool sau 30 ngày, Cold sau 180 ngày; prod dùng GZRS, non-prod dùng ZRS.
