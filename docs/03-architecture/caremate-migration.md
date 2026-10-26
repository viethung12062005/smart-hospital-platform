# Từ CareMate PoC sang Smart Hospital Platform

CareMate là một PoC dựa trên Medplum (monolith Node.js, ~30 package, 15 portal). Dự án này
**viết lại**, chỉ giữ ý tưởng và mô hình dữ liệu FHIR, không copy code.

## Giữ lại (ý tưởng / mô hình)

| CareMate | Smart Hospital |
|---|---|
| Lưu resource FHIR dạng JSONB + bảng tham số tìm kiếm (Medplum) | `SmartHospital.Fhir`: cùng ý tưởng, viết lại bằng .NET, chỉ các SearchParameter cần dùng |
| `appointment-bots`, `send-appointment-reminders` | `scheduling-service` + `notification-service` |
| AccessPolicy theo project | Phân quyền theo role + `fhir_user` claim, ma trận có test |
| AuditEvent | `audit-service` append-only |
| Terraform AWS (VPC/ALB/ECS/RDS/S3/CloudFront) | Terraform `azurerm` (VNet/AKS/Postgres Flexible/Cosmos DB for MongoDB/Blob/Front Door) + Argo CD |

## Sửa các điểm yếu của PoC

| Vấn đề trong PoC | Cách xử lý mới |
|---|---|
| Bot gọi HTTP đồng bộ để gửi notification, lỗi bị `catch` rồi bỏ qua | Transactional outbox → message bus → consumer có retry + dead-letter |
| Đặt lịch không kiểm tra slot đã bị đặt (race condition) | Optimistic concurrency theo `versionId` + transaction, trả `409 Conflict` |
| Không có idempotency cho request đặt lịch | Header `Idempotency-Key`, lưu kết quả để replay |
| Reminder chạy cron quét toàn bộ appointment 24h | Lên lịch nhắc theo event (scheduled message), không quét bảng |
| RDS `backup_retention_period = 0`, `skip_final_snapshot = true` | Postgres Flexible có PITR 7 ngày + kịch bản restore được demo |
| Private key, service account nằm trong repo | Key Vault + Managed Identity; `.gitignore` chặn `*.pem`, `*.key`, `.env` |
| 15 portal riêng | 1 web app (PWA) với giao diện theo role |
