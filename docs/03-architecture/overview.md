# Kiến trúc tổng quan — Smart Hospital Platform

> Trạng thái: **Proposed — chờ G3**, chốt sau khi requirements được duyệt.
>
> Project 5 — Cloud Application Development. Viết lại từ PoC CareMate (Medplum fork) thành
> một hệ thống microservice gọn, FHIR-native, chạy trên Azure nhưng không khóa chặt vào Azure.
> Chỉ dùng **dữ liệu bệnh nhân giả (synthetic)**.

## 1. Luồng nghiệp vụ end-to-end (vertical slice chính)

```
Bệnh nhân tìm slot trống ──► đặt lịch (idempotent, chống double-booking)
        │                              │ AppointmentBooked (outbox → message bus)
        │                              ▼
        │                   notification-service lên lịch nhắc (T-24h, T-2h)
        │                              │  ml-service: điểm rủi ro no-show → nhắc thêm nếu cao
        ▼                              ▼
Bác sĩ khám ──► Encounter ──► upload kết quả (PDF/ảnh) ──► document-service (Blob)
                                                  │ DocumentUploaded
                                                  ▼
                                    ai-service: trích xuất trường (OCR + LLM, human review)
Bác sĩ hỏi AI: tóm tắt hồ sơ / tra cứu phác đồ (RAG có trích dẫn)
Mọi lần đọc/ghi dữ liệu nhạy cảm ──► audit-service (append-only)
```

## 2. Service catalog

| Service | Ngôn ngữ | Sở hữu dữ liệu (FHIR) | Đồng bộ / bất đồng bộ |
|---|---|---|---|
| `api-gateway` | .NET (YARP) | — | Định tuyến, xác thực JWT, rate limit |
| `clinical-service` | .NET | Patient, Practitioner, PractitionerRole, Encounter, Observation, Condition, AllergyIntolerance + liên kết tài khoản + quan hệ điều trị | REST FHIR; nghe event lịch hẹn |
| `scheduling-service` | .NET | Schedule, Slot, Appointment | REST FHIR + outbox; nghe `encounter-finished` |
| `document-service` | .NET | DocumentReference + file trên Blob | REST + event `document-uploaded`, `document-released` |
| `notification-service` | .NET worker | Nhắc lịch (`reminder`), lần gửi, tùy chọn nhận tin | Consume event, retry + DLQ |
| `audit-service` | .NET worker | AuditEvent (append-only) | Consume event |
| `ai-service` | Python (FastAPI) | Kho tri thức RAG (AI Search) | REST; gọi các service khác bằng token của người dùng |
| `ml-service` | Python | Đặc trưng, dự đoán, model registry (MLflow) | Consume event, phát `no-show-scored` |

Nguyên tắc: **mỗi service sở hữu kho dữ liệu riêng, chọn theo đặc tính tải**
(xem [ADR-0008](adr/0008-polyglot-persistence.md)); không join chéo service, chỉ tham chiếu
bằng FHIR `Reference` (`Patient/123`).

| Service | Kho dữ liệu |
|---|---|
| scheduling | PostgreSQL (ACID, chống double-booking) + Redis (cache slot trống) |
| clinical | MongoDB API / Cosmos DB for MongoDB vCore (document FHIR, partition theo bệnh nhân) |
| notification | PostgreSQL (trạng thái nhắc lịch, `SKIP LOCKED`) |
| document | MongoDB (metadata) + Azure Blob Storage (SAS ngắn hạn, lifecycle Hot→Cool) |
| audit | Blob immutable (WORM) + Log Analytics (KQL) |
| ai | Azure AI Search (hybrid + rerank) + MongoDB (hội thoại, job trích xuất) |
| ml | PostgreSQL (đặc trưng, dự đoán) + MLflow |

Thiết kế chi tiết từng kho dữ liệu, API, event và phân quyền: [detailed-design](detailed-design/README.md).

Ranh giới service dựa trên **khác biệt về tải và yêu cầu phi chức năng**, không chia theo
từng resource: scheduling có tranh chấp ghi (concurrency), document có I/O file lớn,
AI có độ trễ/chi phí token và phụ thuộc nhà cung cấp ngoài, notification phải chịu được
provider lỗi. Xem [ADR-0001](adr/0001-microservice-boundaries.md).

## 3. Ánh xạ hạ tầng: Azure ↔ portable

| Năng lực | Azure (production) | Portable / local (docker-compose, K8s bất kỳ) | Lớp trừu tượng |
|---|---|---|---|
| Compute | AKS (2 cluster: nonprod, prod) + Argo CD + KEDA | kind / K8s bất kỳ, cùng Helm chart | Container OCI + Helm |
| DB quan hệ | Azure Database for PostgreSQL Flexible (PITR) | PostgreSQL 16 | `IFhirStore` |
| DB document | Cosmos DB for MongoDB vCore | MongoDB 8 | `IFhirStore` |
| Search/vector | Azure AI Search | OpenSearch | `IRetriever` |
| Audit | Blob immutable + Log Analytics | MinIO object lock + Loki | `IAuditSink` |
| Message bus | Azure Service Bus | RabbitMQ | MassTransit |
| Object storage | Azure Blob Storage | MinIO / S3 | `IBlobStore` |
| Cache | Azure Cache for Redis | Redis | StackExchange.Redis |
| Identity | Microsoft Entra ID (OIDC) | Keycloak (OIDC) | JWT Bearer chuẩn OIDC |
| Secret | Key Vault + Managed Identity | env / K8s Secret | `IConfiguration` |
| Observability | Azure Monitor / App Insights | Grafana + Tempo + Prometheus | OpenTelemetry (OTLP) |
| LLM | Azure OpenAI → Claude → model local | Ollama | `ai-service` router |
| ML | Azure ML / MLflow | MLflow | MLflow model format |

Xem [ADR-0004](adr/0004-azure-first-portable.md).

## 4. Bảo mật (tóm tắt — chi tiết ở threat model P3)

- OIDC + JWT; role: `patient`, `practitioner`, `nurse`, `admin`. Claim `fhir_user`
  (`Patient/123`) dùng cho phân quyền theo đối tượng (patient chỉ thấy dữ liệu của mình).
- Ma trận phân quyền được kiểm thử tự động (xem `tests/`).
- Tài liệu chỉ truy cập qua SAS URL ngắn hạn (≤ 5 phút), không public blob.
- Mã hóa at-rest (Postgres, Blob) và TLS in-transit; secret chỉ nằm trong Key Vault.
- AI: redact PII trước khi gửi tới provider ngoài Azure, chống prompt injection, giới hạn
  tool theo quyền của người gọi.

## 5. Khác biệt so với PoC CareMate

Xem [caremate-migration.md](caremate-migration.md).
