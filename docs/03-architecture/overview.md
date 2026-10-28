# Kiến trúc tổng quan — Smart Hospital Platform

> Trạng thái: **Approved — G3 qua ngày 2026-10-26**.
>
> Project 5 — Cloud Application Development. Một hệ thống microservice gọn, FHIR-native, chạy trên Azure nhưng không khóa chặt vào Azure.
> Chỉ dùng **dữ liệu bệnh nhân giả (synthetic)**.

Mục lục: [1. Sơ đồ tổng thể](#1-sơ-đồ-kiến-trúc-tổng-thể) · [2. Giao tiếp giữa các module](#2-giao-tiếp-giữa-các-module) ·
[3. Module mã nguồn](#3-module-mã-nguồn) · [4. Bên trong một service Java](#4-bên-trong-một-service-java) · [5. Luồng nghiệp vụ](#5-luồng-nghiệp-vụ-end-to-end-vertical-slice-chính) ·
[6. Service catalog](#6-service-catalog) · [7. Hạ tầng Azure ↔ portable](#7-ánh-xạ-hạ-tầng-azure--portable) · [8. Bảo mật](#8-bảo-mật-tóm-tắt--chi-tiết-ở-threat-model-p3)

## 1. Sơ đồ kiến trúc tổng thể

Hệ thống gồm **8 service** chạy trên Kubernetes:
- **6 service Java**: `api-gateway` + 5 service nghiệp vụ.
- **2 service Python**: `ai`, `ml`.

Các service nói chuyện với nhau theo hai cách:
- **Đồng bộ**: REST, luôn qua gateway từ phía client.
- **Bất đồng bộ**: event CloudEvents trên message bus.

Mỗi service **sở hữu kho dữ liệu riêng**.

```mermaid
flowchart LR
  subgraph USERS["Người dùng — Web PWA (desktop + mobile)"]
    direction TB
    PAT["Bệnh nhân"]
    STF["Bác sĩ · Điều dưỡng"]
    ADM["Admin · DPO"]
  end

  IDP["Danh tính OIDC<br/>Keycloak (local) · Entra ID (cloud)"]

  subgraph EDGE["Rìa"]
    direction TB
    WAF["Front Door + WAF<br/>(chỉ cloud)"] --> ING["Ingress TLS"]
  end

  GW["api-gateway<br/>Spring Cloud Gateway<br/>route · kiểm JWT · chặn /internal"]

  subgraph JAVA["Service nghiệp vụ — Java 21 · Spring Boot 4"]
    direction TB
    SCH["scheduling<br/>lịch, slot, đặt lịch"]
    CLI["clinical<br/>hồ sơ FHIR, liên kết tài khoản"]
    DOC["document<br/>tài liệu, SAS"]
    NTF["notification<br/>nhắc lịch, gửi tin"]
    AUD["audit<br/>nhật ký bất biến"]
  end

  subgraph PY["Service AI/ML — Python 3.12"]
    direction TB
    AI["ai — FastAPI<br/>RAG, tóm tắt, trích xuất"]
    ML["ml — FastAPI + LightGBM<br/>dự đoán no-show"]
  end

  BUS[["Message bus — CloudEvents<br/>RabbitMQ (local) · Service Bus (cloud)"]]

  subgraph DATA["Kho dữ liệu (mỗi service một database riêng)"]
    direction TB
    PG[("PostgreSQL<br/>scheduling")]
    RD[("Redis<br/>cache slot trống")]
    MG[("MongoDB<br/>clinical · document · notification<br/>audit · ai · ml")]
    BL[("Blob Storage<br/>tài liệu · audit WORM · Parquet")]
    LA[("Log Analytics<br/>tra cứu audit (KQL)")]
    SR[("AI Search / OpenSearch<br/>kho tri thức RAG")]
    MF[("MLflow<br/>model registry")]
  end

  subgraph EXT["Dịch vụ ngoài / mô hình"]
    direction TB
    LLM["LLM: DeepSeek API → Gemini free tier"]
    OLL["Ollama trong cụm<br/>embedding bge-m3 · fallback cuối"]
    MSG["Push FCM/APNs · SMS · Email"]
  end

  USERS --> WAF
  USERS -. đăng nhập .-> IDP
  ING --> GW
  GW -. JWKS .-> IDP
  GW --> SCH & CLI & DOC & NTF & AUD & AI & ML

  SCH & CLI & DOC & ML <--> BUS
  BUS --> NTF & AUD & AI

  SCH --> PG & RD
  CLI & DOC & NTF & AUD --> MG
  DOC --> BL
  AUD --> BL & LA
  AI --> MG & SR
  ML --> MG & BL & MF
  USERS -. "upload/tải trực tiếp bằng SAS ≤ 5 phút" .-> BL

  AI --> LLM & OLL
  NTF --> MSG
```

Ghi chú về sơ đồ:
- **Ranh giới tin cậy.** Mọi request từ ngoài đi qua WAF (cloud), Ingress, rồi gateway.
  - Gateway kiểm token ở biên.
  - Từng service **kiểm lại token** và phân quyền chi tiết theo [ma trận](detailed-design/15-authorization.md).
  - Đường `/internal/**` không có route ở gateway; chỉ các service trong cụm gọi được.
- **Dữ liệu ra ngoài cụm.**
  - Chỉ `ai` gọi LLM bên ngoài, và phải che PII trước khi gửi.
  - Chỉ `notification` gọi nhà cung cấp tin nhắn.
  - Quy tắc cho values uat/prod: chỉ hai service này được mở egress ra Internet (chart `microservice` có `networkPolicy.egress`).
    Local (kind) tắt NetworkPolicy.
- **Quan sát.** Mọi service xuất trace/metric/log bằng OpenTelemetry (không vẽ cho gọn), xem §7.

## 2. Giao tiếp giữa các module

### 2.1 Bất đồng bộ — event trên bus
Mô hình **outbox → bus → inbox**:
- Producer ghi dữ liệu và event trong **cùng một transaction**.
- Bus giao ít nhất một lần.
- Consumer xử lý mỗi event **đúng một lần** nhờ inbox.

Mỗi hình lục giác là một nhóm event (topic `shp-events`, lọc theo `type`). Chi tiết: [13-events.md](detailed-design/13-events.md).

```mermaid
flowchart LR
  SCH["scheduling"]
  CLI["clinical"]
  DOC["document"]
  ML["ml"]
  ALL(["mọi service"])
  NTF["notification"]
  AI["ai"]
  AUD["audit"]

  EA{{"E1–E5 lịch hẹn<br/>booked · cancelled · rescheduled<br/>checked-in · status-changed"}}
  EB{{"E6 encounter-finished"}}
  EC{{"E7 patient-merged"}}
  ED{{"E8 document-uploaded"}}
  EE{{"E9 document-released"}}
  EF{{"E10 no-show-scored"}}
  EG{{"E11 audit.recorded"}}

  SCH --> EA --> NTF & ML & CLI
  CLI --> EB --> SCH
  CLI --> EC --> SCH & NTF & ML & DOC
  DOC --> ED --> AI
  DOC --> EE --> NTF
  ML --> EF --> NTF
  ALL --> EG --> AUD
```

Ai nhận event nào trong nhóm E1–E5 (sơ đồ gộp cho gọn):

| Event | Consumer |
|---|---|
| E1–E3 | notification, ml, clinical |
| E4 checked-in | clinical |
| E5 status-changed | ml, clinical |

### 2.2 Đồng bộ — REST nội bộ `/internal/v1/**`
- Chỉ gọi được từ trong cụm; gateway không có route tới đây.
- Có timeout; mỗi lần đọc dữ liệu nhạy cảm đều ghi audit.
- Endpoint: [09-api-clinical.md](detailed-design/09-api-clinical.md) §4.

`ai` gọi API công khai của clinical **bằng token của người dùng** (on-behalf-of), không dùng quyền riêng.

```mermaid
flowchart LR
  SCH["scheduling"] -- "danh tính · vai trò bác sĩ · nhóm tuổi" --> CLI["clinical"]
  NTF["notification"] -- "thông tin liên hệ (endpoint duy nhất có SĐT/email)" --> CLI
  DOC["document"] -- "kiểm quan hệ điều trị" --> CLI
  AI["ai"] -- "đọc hồ sơ bằng token người dùng" --> CLI
  CLI -- "danh sách tài liệu cho timeline" --> DOC
```

## 3. Module mã nguồn

Repo ứng dụng là một **Maven multi-module** cho phần Java, cộng hai project Python độc lập. Mỗi service có:
- Dockerfile và pipeline `.github/workflows/svc-<tên>.yml` riêng;
- `deploy/values-local.yaml` cho cụm kind.

```text
pom.xml                         # Maven gốc: Spring Boot 4.1.2, Java 21, Spotless, JaCoCo ≥ 70%, -Werror
libs/java/shp-*/                # 7 thư viện dùng chung (SA viết, dev chỉ dùng)
services/<6 service Java>/      # gateway, scheduling, notification, clinical, document, audit
services/ai/  services/ml/      # Python 3.12 (pyproject, ruff, mypy, pytest)
contracts/                      # nguồn sự thật: openapi, events (JSON Schema), authz/matrix.yaml, terminology, db
dev/                            # cụm kind + Helm cho chạy local (make -C dev ...)
tools/                          # scaffold service, kiểm hợp đồng, sinh kế hoạch
docs/                           # BA, yêu cầu, kiến trúc, DevOps, kế hoạch
```

Quan hệ phụ thuộc giữa các module (mũi tên = "dùng"):

```mermaid
flowchart TB
  subgraph SVC["services/ — dev viết logic nghiệp vụ"]
    direction LR
    GW["gateway"]
    SCH["scheduling"]
    NTF["notification"]
    CLI["clinical"]
    DOC["document"]
    AUD["audit"]
    AI["ai (Python)"]
    ML["ml (Python)"]
  end

  subgraph LIB["libs/java/ — nền tảng dùng chung (SA)"]
    direction LR
    DP["shp-data-postgres<br/>outbox · inbox · Idempotency-Key<br/>Flyway V0_1"]
    DM["shp-data-mongo<br/>outbox · inbox · Idempotency-Key<br/>transaction có thử lại"]
    WEB["shp-web<br/>lỗi chuẩn (Problem/OperationOutcome)<br/>@RequireShpAuthz · danh tính · SecurityFilterChain"]
    SEC["shp-security<br/>JWT: issuer · audience · MFA nhân viên"]
    EV["shp-events<br/>CloudEvents · kiểm schema · bản ghi audit"]
    AZ["shp-authz<br/>ma trận + engine phân quyền"]
    SD["shp-service-defaults<br/>health · info · OTel · virtual threads"]
  end

  subgraph CON["contracts/ — hợp đồng, review trước khi code"]
    direction LR
    CEV["events/*.json"]
    CAZ["authz/matrix.yaml"]
    COA["openapi/*.yaml · db/*.js"]
  end

  SCH --> DP
  NTF & CLI & DOC & AUD --> DM
  DP & DM --> WEB
  DP & DM --> EV
  GW --> SEC & SD
  WEB --> SEC & EV & SD
  EV --> AZ
  EV -. nhúng lúc build .-> CEV
  AZ -. nhúng lúc build .-> CAZ
  AI & ML -.-> CON
```

Ghi chú:
- Service chỉ khai báo trực tiếp `shp-data-*`, phần còn lại (`shp-web`, `shp-events`, …) đi kèm theo.
  Gateway là WebFlux nên chỉ dùng `shp-security` + `shp-service-defaults`, không kéo Spring MVC.
- Schema event và ma trận phân quyền được **nhúng vào jar lúc build**, nên service luôn chạy đúng bản đã review.
- Mọi service hiện thực đúng `contracts/openapi`; `contracts/db/*.js` khởi tạo collection cho notification/ml.
- `ai`, `ml` đọc cùng schema/matrix qua thư viện Python (PY-01..05, D3).

| Module | Vai trò | Ai sửa |
|---|---|---|
| `shp-service-defaults` | Cấu hình chung mọi service: actuator `/health/{liveness,readiness}`, `/info`, `/prometheus`; OTel; tắt êm; virtual threads | SA |
| `shp-authz` | Đọc `contracts/authz/matrix.yaml` (nhúng), `AuthorizationEngine.decide` — mặc định từ chối | SA |
| `shp-events` | `CloudEvents.create` (UUIDv7, traceparent, ≤ 64 KB), `EventSchemas.validate`, `AuditRecordBuilder` (che IP) | SA |
| `shp-security` | Quy tắc JWT dùng chung cho servlet (service) và reactive (gateway) | SA |
| `shp-web` | Bắt lỗi → Problem Details; `@RequireShpAuthz` (test đỏ nếu endpoint quên); phân giải danh tính `sub → Patient/…`; `IdempotencyKeys` | SA |
| `shp-data-postgres` | Outbox + relay (`SKIP LOCKED`), inbox, Idempotency-Key, sink audit — bảng do Flyway tạo | SA |
| `shp-data-mongo` | Như trên cho MongoDB (`findAndModify` + `lockedUntil`), `MongoMessaging.inTransaction` | SA |
| `services/*` (trừ gateway) | Controller, nghiệp vụ, truy cập dữ liệu, consumer của service | D1 (scheduling, notification) · D2 (clinical, document, audit) · D3 (ai, ml) |

## 4. Bên trong một service Java

Mọi service Java dùng cùng một bố cục gói (khung do `tools/scaffold/java_service.py` sinh). Dev chỉ thêm code vào các gói
`api`, `app`, `domain`, `infra`, `messaging`. Gói `config` là của SA.

```mermaid
flowchart LR
  REQ(["HTTP từ gateway<br/>hoặc /internal"]) --> F
  subgraph SVC["org.shp.&lt;service&gt;"]
    direction LR
    F["shp-web filter + interceptor<br/>JWT → danh tính → @RequireShpAuthz<br/>(có sẵn)"]
    API["api/<br/>*Controller: DTO, mã lỗi,<br/>ETag, Idempotency-Key"]
    APP["app/<br/>use case @Transactional"]
    DOM["domain/<br/>model + quy tắc nghiệp vụ<br/>(không phụ thuộc Spring)"]
    INF["infra/<br/>repository (JDBC/Mongo),<br/>client REST nội bộ, Blob"]
    MSG["messaging/<br/>consumer: inbox.handleOnce"]
    CFG["config/PlatformConfig<br/>outbox · inbox · idempotency<br/>(SA, không sửa)"]
  end
  DB[("DB của service<br/>+ outbox · inbox")]
  RELAY["Outbox relay<br/>(có sẵn)"]
  BUS[["bus"]]

  F --> API --> APP --> DOM
  APP --> INF --> DB
  APP -- "outbox.add(event)<br/>cùng transaction" --> DB
  DB --> RELAY --> BUS
  BUS --> MSG --> APP
  CFG -. cấp bean .-> APP
```

Quy tắc:
- **Controller** chỉ làm việc HTTP: map DTO và gọi use case. Mọi endpoint có `@RequireShpAuthz`.
- **Use case** (`app/`) là nơi duy nhất mở transaction. Thay đổi dữ liệu và `outbox.add(...)` nằm trong **cùng** một transaction.
- **domain/** không import Spring/JDBC/Mongo, nên test thuần JUnit, nhanh.
- **Test:**
  - Unit test cho `domain/` và `app/`.
  - Test tích hợp (Testcontainers, DB thật) cho `infra/`, API và consumer.
  - Coverage dòng ≥ 70% mỗi module (CI chặn).
- Service Python (`ai`, `ml`) chia module con trong `src/shp_ai`, `src/shp_ml` theo cùng cách: `api/` (FastAPI router), `app/`, `domain/`, `infra/`, `messaging/`.

## 5. Luồng nghiệp vụ end-to-end (vertical slice chính)

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

## 6. Service catalog

| Service | Ngôn ngữ | Sở hữu dữ liệu (FHIR) | Đồng bộ / bất đồng bộ | Dev |
|---|---|---|---|---|
| `api-gateway` | Java (Spring Cloud Gateway) | — | Định tuyến, xác thực JWT, rate limit | SA |
| `clinical-service` | Java | Patient, Practitioner, PractitionerRole, Encounter, Observation, Condition, AllergyIntolerance + liên kết tài khoản + quan hệ điều trị | REST FHIR; nghe event lịch hẹn | D2 |
| `scheduling-service` | Java | Schedule, Slot, Appointment | REST FHIR + outbox; nghe `encounter-finished` | D1 |
| `document-service` | Java | DocumentReference + file trên Blob | REST + event `document-uploaded`, `document-released` | D2 |
| `notification-service` | Java worker | Nhắc lịch (`reminder`), lần gửi, tùy chọn nhận tin | Consume event, retry + DLQ | D1 |
| `audit-service` | Java worker | AuditEvent (append-only) | Consume event | D2 |
| `ai-service` | Python (FastAPI) | Kho tri thức RAG (AI Search) | REST; gọi các service khác bằng token của người dùng | D3 |
| `ml-service` | Python (LightGBM, MLflow) | Đặc trưng, dự đoán, model registry (MLflow) | Consume event, phát `no-show-scored` | D3 |

Nguyên tắc: **mỗi service sở hữu kho dữ liệu riêng, chọn theo đặc tính tải**
(xem [ADR-0008](adr/0008-polyglot-persistence.md)); không join chéo service, chỉ tham chiếu
bằng FHIR `Reference` (`Patient/123`).

| Service | Kho dữ liệu |
|---|---|
| scheduling | PostgreSQL (ACID, chống double-booking) + Redis (cache slot trống) |
| clinical | MongoDB API / Cosmos DB for MongoDB vCore (document FHIR, partition theo bệnh nhân) |
| notification | MongoDB (nhắc lịch: lấy việc nguyên tử + `lockedUntil`, TTL) — ADR-0014 |
| document | MongoDB (metadata) + Azure Blob Storage (SAS ngắn hạn, lifecycle Hot→Cool) |
| audit | Blob immutable (WORM) + Log Analytics (KQL); MongoDB chỉ cho inbox/outbox |
| ai | Azure AI Search (hybrid + rerank) + MongoDB (hội thoại, job trích xuất) |
| ml | MongoDB (trạng thái đặc trưng, nhật ký dự đoán) + Parquet trên Blob + MLflow — ADR-0014 |

Thiết kế chi tiết từng kho dữ liệu, API, event và phân quyền: [detailed-design](detailed-design/README.md).

Ranh giới service dựa trên **khác biệt về tải và yêu cầu phi chức năng**, không chia theo
từng resource: scheduling có tranh chấp ghi (concurrency), document có I/O file lớn,
AI có độ trễ/chi phí token và phụ thuộc nhà cung cấp ngoài, notification phải chịu được
provider lỗi. Xem [ADR-0001](adr/0001-microservice-boundaries.md).

## 7. Ánh xạ hạ tầng: Azure ↔ portable

| Năng lực | Azure (production) | Portable / local (docker-compose, K8s bất kỳ) | Lớp trừu tượng |
|---|---|---|---|
| Compute | AKS (2 cluster: nonprod, prod) + Argo CD + KEDA | kind / K8s bất kỳ, cùng Helm chart | Container OCI + Helm |
| DB quan hệ | Azure Database for PostgreSQL Flexible (PITR) | PostgreSQL 16 | Spring JDBC/Flyway (`shp-data-postgres`) |
| DB document | Cosmos DB for MongoDB vCore | MongoDB 8 | Spring Data MongoDB (`shp-data-mongo`, `FhirStore` — SHR-10) |
| Search/vector | Azure AI Search | OpenSearch | `Retriever` (Python, ai) |
| Audit | Blob immutable + Log Analytics | Azurite (không có WORM) + Loki (profile `full`) | `AuditSink` (`shp-events`) |
| Message bus | Azure Service Bus | RabbitMQ | Spring Cloud Stream (Java) · client AMQP (Python) |
| Object storage | Azure Blob Storage | Azurite (giả lập Azure Blob, cùng SDK) — ADR-0012 | Azure Storage SDK (`BlobStore`) |
| Cache | Azure Cache for Redis | Redis | Spring Data Redis |
| Identity | Microsoft Entra ID (OIDC) | Keycloak (OIDC) | JWT Bearer chuẩn OIDC |
| Secret | Key Vault (CSI) + Workload Identity | env / K8s Secret | biến môi trường → Spring `Environment` |
| Observability | Azure Monitor / App Insights | Grafana + Tempo + Prometheus | OpenTelemetry (OTLP) |
| LLM | DeepSeek API → Gemini free tier → Ollama trong cụm (embedding bge-m3) — ADR-0013 | Ollama | router của `ai` |
| ML | Azure ML / MLflow | MLflow | MLflow model format |

Xem [ADR-0004](adr/0004-azure-first-portable.md).

## 8. Bảo mật (tóm tắt — chi tiết ở threat model P3)

- OIDC + JWT; role: `patient`, `practitioner`, `nurse`, `admin`. Claim `fhir_user`
  (`Patient/123`) dùng cho phân quyền theo đối tượng (patient chỉ thấy dữ liệu của mình).
- Ma trận phân quyền được kiểm thử tự động (xem `tests/`).
- Tài liệu chỉ truy cập qua SAS URL ngắn hạn (≤ 5 phút), không public blob.
- Mã hóa at-rest (Postgres, Blob) và TLS in-transit; secret chỉ nằm trong Key Vault.
- AI: redact PII trước khi gửi tới provider ngoài Azure, chống prompt injection, giới hạn
  tool theo quyền của người gọi.

