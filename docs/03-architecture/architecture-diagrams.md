# Sơ đồ kiến trúc chi tiết — Smart Hospital Platform

> Task: ARC-04. Bổ sung các **góc nhìn** mà [overview.md](overview.md) chưa vẽ: C4 Context/Container, triển khai trên Azure,
> vùng tin cậy mạng, danh tính và phân quyền, bảo vệ dữ liệu, CI/CD và GitOps, môi trường, quan sát, AI/ML.
>
> - Luồng nghiệp vụ chi tiết (đặt lịch, nhắc lịch, khám, tài liệu, RAG, break-glass): [14-sequences.md](detailed-design/14-sequences.md).
> - Event: [13-events.md](detailed-design/13-events.md) · Phân quyền: [15-authorization.md](detailed-design/15-authorization.md) · MLOps: [16-mlops.md](detailed-design/16-mlops.md).
> - Mọi sơ đồ dùng Mermaid, hiển thị trực tiếp trên GitHub. Hệ thống chỉ xử lý **dữ liệu bệnh nhân giả**, nhưng thiết kế bảo mật như với dữ liệu thật.

Mục lục:
[1. C4 — Context](#1-c4-mức-1--system-context) ·
[2. C4 — Container](#2-c4-mức-2--container) ·
[3. Triển khai trên Azure](#3-góc-nhìn-triển-khai-trên-azure) ·
[4. Vùng tin cậy mạng](#4-vùng-tin-cậy-và-luồng-mạng) ·
[5. Danh tính và phân quyền](#5-danh-tính-xác-thực-và-phân-quyền) ·
[6. Bảo vệ dữ liệu](#6-bảo-vệ-dữ-liệu-sức-khỏe) ·
[7. Độ tin cậy của event](#7-độ-tin-cậy-của-luồng-event) ·
[8. CI/CD và GitOps](#8-cicd-và-gitops) ·
[9. Môi trường](#9-môi-trường-và-vòng-đời) ·
[10. Quan sát](#10-quan-sát-observability) ·
[11. AI và ML](#11-ai-và-ml) ·
[12. Ánh xạ sơ đồ → quyết định](#12-ánh-xạ-sơ-đồ--quyết-định)

---

## 1. C4 mức 1 — System Context

Ai dùng hệ thống, và hệ thống phụ thuộc những gì bên ngoài.

```mermaid
flowchart TB
  PAT(["Bệnh nhân<br/>đặt lịch, xem hồ sơ của mình,<br/>hỏi chatbot đặt lịch"])
  STF(["Bác sĩ · Điều dưỡng<br/>lịch khám, hàng đợi, hồ sơ,<br/>tài liệu, trợ lý AI"])
  ADM(["Admin · DPO<br/>người dùng, kho tri thức,<br/>audit, break-glass"])

  SHP["Smart Hospital Platform<br/>web PWA + 8 microservice trên Kubernetes<br/>FHIR-native, event-driven"]

  IDP["Nhà cung cấp danh tính OIDC<br/>Keycloak (local) · Entra ID (cloud)"]
  LLM["LLM bên ngoài<br/>DeepSeek API → Gemini free tier"]
  MSG["Kênh tin nhắn<br/>Push FCM/APNs · SMS · Email"]
  AZ["Azure managed services<br/>DB, Blob, Service Bus, AI Search,<br/>Key Vault, Monitor"]

  PAT & STF & ADM -- "HTTPS (trình duyệt)" --> SHP
  PAT & STF & ADM -. "đăng nhập OIDC + PKCE" .-> IDP
  SHP -- "kiểm JWT bằng JWKS" --> IDP
  SHP -- "chỉ văn bản đã che PII" --> LLM
  SHP -- "nhắc lịch, cảnh báo DPO" --> MSG
  SHP -- "private endpoint" --> AZ
```

| Bên ngoài | Dữ liệu trao đổi | Kiểm soát |
|---|---|---|
| IdP | Token OIDC; không có dữ liệu lâm sàng | PKCE, token ≤ 15 phút, nhân viên bắt buộc MFA (`amr` có `mfa`) |
| LLM | Câu hỏi/ngữ cảnh **đã redact PII** | Chỉ service `ai` được egress; mọi lời gọi qua bộ redact (ADR-0013) |
| Kênh tin nhắn | Nội dung nhắc lịch tối thiểu, SĐT/email | Chỉ service `notification` được egress; nội dung không chứa chẩn đoán |
| Azure managed | Toàn bộ dữ liệu lưu trữ | Private endpoint, mã hóa at-rest, Workload Identity (không key) |

## 2. C4 mức 2 — Container

Các đơn vị triển khai độc lập (container) và kho dữ liệu của từng cái. Mỗi service **sở hữu** kho riêng; không service nào đọc DB của service khác.

```mermaid
flowchart TB
  USR(["Người dùng<br/>(trình duyệt)"])

  subgraph SHP["Smart Hospital Platform"]
    direction TB
    WEB["web<br/>React + TS + Mantine, nginx<br/>SPA tĩnh, CSP chặt"]
    GW["api-gateway<br/>Spring Cloud Gateway<br/>JWT lớp 1, rate limit, chặn /internal"]

    subgraph BIZ["Java 21 · Spring Boot"]
      direction LR
      SCH["scheduling<br/>slot, đặt lịch, check-in"]
      CLI["clinical<br/>FHIR record, identity link,<br/>care relationship, break-glass"]
      DOC["document<br/>metadata, SAS, quét mã độc"]
      NTF["notification<br/>worker nhắc lịch, retry, DLQ"]
      AUD["audit<br/>append-only, hash chain"]
    end

    subgraph PYS["Python 3.12 · FastAPI"]
      direction LR
      AI["ai<br/>RAG, tóm tắt, trích xuất,<br/>chatbot, LLM router"]
      ML["ml<br/>chấm điểm no-show,<br/>drift, retrain"]
    end

    BUS[["Message bus<br/>topic shp-events · CloudEvents"]]

    PG[("PostgreSQL<br/>scheduling")]
    RD[("Redis<br/>cache slot")]
    MGC[("MongoDB<br/>clinical")]
    MGD[("MongoDB<br/>document")]
    MGN[("MongoDB<br/>notification")]
    MGA[("MongoDB<br/>audit inbox")]
    MGI[("MongoDB<br/>ai")]
    MGM[("MongoDB<br/>ml")]
    BLOB[("Blob<br/>documents · kb · audit WORM · Parquet")]
    SRCH[("AI Search<br/>kb-chunks-v1")]
    LAW[("Log Analytics<br/>SHPAudit_CL")]
    MLF[("MLflow<br/>model registry")]
    OLL["Ollama trong cụm<br/>bge-m3 embedding · qwen2.5"]
  end

  USR -- "/" --> WEB
  USR -- "/fhir, /api (cùng origin)" --> GW
  USR -. "PUT/GET trực tiếp bằng SAS" .-> BLOB

  GW --> SCH & CLI & DOC & NTF & AUD & AI & ML

  SCH -- "/internal: identity, role" --> CLI
  NTF -- "/internal: contact" --> CLI
  DOC -- "/internal: care check" --> CLI
  AI -- "token của người dùng (OBO)" --> CLI & SCH

  SCH --> PG & RD
  CLI --> MGC
  DOC --> MGD & BLOB
  NTF --> MGN
  AUD --> MGA & BLOB & LAW
  AI --> MGI & SRCH & OLL
  ML --> MGM & BLOB & MLF

  SCH & CLI & DOC & ML -- "outbox → publish" --> BUS
  BUS -- "inbox → consume" --> NTF & AUD & AI & ML & CLI & SCH
```

| Container | Công nghệ | Giao tiếp vào | Giao tiếp ra | Dữ liệu sở hữu |
|---|---|---|---|---|
| web | React, Vite, nginx | HTTPS `/` | — (trình duyệt gọi gateway) | — |
| api-gateway | Spring Cloud Gateway (WebFlux) | HTTPS `/fhir`, `/api` | REST tới service | — |
| scheduling | Java | REST, event E6, E7 | REST clinical, event E1–E5 | Schedule, Slot, Appointment |
| clinical | Java | REST, `/internal`, event E1/E4/E5 | event E6, E7 | Patient, Practitioner, Encounter, Observation, Condition… |
| document | Java | REST, `/internal` | Blob, event E8, E9 | DocumentReference + file |
| notification | Java worker | REST (tùy chọn nhận tin), event | Push/SMS/Email | reminder, delivery attempt |
| audit | Java worker | event E11 | Blob WORM, Log Analytics | AuditEvent |
| ai | Python | REST, event E8 | LLM ngoài, Ollama, AI Search, clinical (OBO) | kb, hội thoại, extraction job |
| ml | Python | REST, event E1/E5 | event E10, MLflow | feature state, prediction log, model |

## 3. Góc nhìn triển khai trên Azure

Một môi trường cloud (`uat` hoặc `prod`) = **một stack Terraform** (`shp-infra/envs/<env>`), một resource group, một cluster AKS. Hai môi trường
dùng subscription/identity riêng (NFR-D4). Tài nguyên thường trực chỉ có stack `bootstrap`.

```mermaid
flowchart TB
  NET(["Internet"])

  subgraph BOOT["Stack bootstrap — thường trực, không chứa dữ liệu bệnh nhân"]
    direction LR
    TFS[("Terraform state<br/>Storage")]
    ACR[("Azure Container Registry<br/>image đã ký + chart OCI")]
    GHID["Managed identity<br/>GitHub OIDC federated"]
  end

  subgraph ENV["Stack envs/&lt;uat|prod&gt; — ephemeral, dựng bằng env-provision"]
    direction TB
    FD["Front Door + WAF<br/>(OWASP rule set)"]

    subgraph VNET["VNet vnet-&lt;env&gt;"]
      direction TB

      subgraph SAKS["Subnet snet-aks"]
        direction TB
        subgraph AKS["AKS — 3 availability zone"]
          direction LR
          NPS["Node pool system<br/>CoreDNS, Argo CD,<br/>add-on nền tảng"]
          NPA["Node pool apps<br/>gateway, web,<br/>5 service Java, ml"]
          NPI["Node pool ai<br/>taint workload=ai<br/>ai, Ollama"]
        end
      end

      subgraph SPE["Subnet snet-private-endpoints"]
        direction LR
        PE1["PE MongoDB vCore"]
        PE2["PE Redis"]
        PE3["PE Service Bus"]
        PE4["PE Blob"]
        PE5["PE AI Search"]
        PE6["PE Key Vault"]
      end

      subgraph SPG["Subnet snet-postgres (delegated)"]
        PGF[("PostgreSQL Flexible<br/>VNet integration, PITR")]
      end
    end

    MG[("Cosmos DB for MongoDB vCore")]
    RDS[("Azure Cache for Redis")]
    SB[["Azure Service Bus"]]
    ST[("Storage: documents, kb,<br/>audit WORM, ml Parquet")]
    AIS[("Azure AI Search")]
    KV[("Key Vault")]
    MON["Azure Monitor<br/>managed Prometheus + Grafana,<br/>Log Analytics"]
    DNS["Private DNS zones"]
  end

  NET --> FD --> AKS
  AKS -- "pull image (AcrPull)" --> ACR
  NPA & NPI --> PE1 & PE2 & PE3 & PE4 & PE5 & PE6
  NPA --> PGF
  PE1 --> MG
  PE2 --> RDS
  PE3 --> SB
  PE4 --> ST
  PE5 --> AIS
  PE6 --> KV
  VNET -. "phân giải tên" .-> DNS
  AKS -- "OTLP / metric / log" --> MON
  GHID -. "terraform apply<br/>(sau phê duyệt)" .-> ENV
```

**Bên trong cluster** (repo `shp-gitops`): Argo CD tạo add-on trước (sync wave `-3`), rồi baseline, rồi service.

```mermaid
flowchart LR
  subgraph K8S["AKS cluster"]
    direction TB
    subgraph PLAT["Add-on nền tảng (platform/addons)"]
      direction LR
      ARGO["argocd<br/>Argo CD"]
      CM["cert-manager<br/>ClusterIssuer"]
      KY["kyverno<br/>policy admission"]
      RO["argo-rollouts<br/>canary"]
      KE["keda<br/>scale theo queue/cron"]
      EG["envoy-gateway-system<br/>Gateway API"]
      OT["observability<br/>OTel Collector"]
    end
    subgraph APPS["namespace shp-apps (shp-baseline)"]
      direction LR
      GWY["Gateway shp-gateway<br/>listener HTTPS"]
      NP["NetworkPolicy<br/>default-deny-ingress"]
      SVCS["9 Deployment/Rollout<br/>chart microservice<br/>HPA · PDB · probe"]
      CSI["Key Vault CSI<br/>+ Workload Identity"]
    end
  end
  ARGO -- "sync từ shp-gitops" --> PLAT & APPS
  EG --> GWY --> SVCS
  KY -. "chặn pod vi phạm" .-> SVCS
  KE -. "scale" .-> SVCS
  RO -. "điều khiển canary" .-> SVCS
  CSI -. "mount secret" .-> SVCS
```

| Policy Kyverno | Mục đích |
|---|---|
| `shp-restrict-registry` | Chỉ cho image từ ACR của dự án |
| `shp-verify-signature` | Image phải có chữ ký cosign keyless từ pipeline CI (NFR-SEC6) |
| `shp-disallow-latest` | Cấm tag `latest`, buộc tag/digest cố định |
| `shp-require-resources-probes` | Bắt buộc requests/limits và readiness/liveness probe |

## 4. Vùng tin cậy và luồng mạng

Mỗi mũi tên qua ranh giới vùng đều có một điểm kiểm soát. Mặc định **chặn**; chỉ mở đúng luồng cần.

```mermaid
flowchart LR
  subgraph Z0["Z0 — Không tin cậy"]
    BR(["Trình duyệt"])
  end

  subgraph Z1["Z1 — Rìa"]
    direction TB
    WAF["Front Door + WAF<br/>TLS 1.2+, OWASP, rate limit"]
    GWY["Envoy Gateway<br/>TLS (cert-manager)"]
  end

  subgraph Z2["Z2 — Ứng dụng (shp-apps, default-deny)"]
    direction TB
    WEB["web"]
    GW["api-gateway<br/>kiểm JWT lớp 1"]
    SVC["service nghiệp vụ<br/>kiểm JWT lại + ABAC"]
    INT["/internal/v1/**<br/>chỉ service được liệt kê"]
  end

  subgraph Z3["Z3 — Dữ liệu (private endpoint, không public)"]
    DATA[("DB · Blob · Bus · Search · Key Vault")]
  end

  subgraph Z4["Z4 — Bên ngoài có kiểm soát"]
    direction TB
    LLM["LLM API"]
    MSGP["Push/SMS/Email"]
    IDP["IdP"]
  end

  BR -- "HTTPS" --> WAF --> GWY
  GWY -- "/" --> WEB
  GWY -- "/fhir, /api" --> GW --> SVC
  SVC -- "NetworkPolicy + app role internal.*" --> INT
  SVC -- "Workload Identity, TLS" --> DATA
  BR -. "SAS 1 blob, ≤ 5–10 phút" .-> DATA
  SVC -- "egress chỉ ai → LLM<br/>(sau redact PII)" --> LLM
  SVC -- "egress chỉ notification" --> MSGP
  GW & SVC -. "JWKS" .-> IDP
```

| Ranh giới | Kiểm soát | Nếu bị vượt qua |
|---|---|---|
| Z0 → Z1 | WAF, TLS, giới hạn tốc độ | Request bị chặn ở rìa, không vào cụm |
| Z1 → Z2 | Chỉ route `/`, `/fhir`, `/api`; **không có route `/internal`** | 404 ở gateway |
| Gateway → service | Service **không tin** header do gateway thêm; tự kiểm token | Token giả/hết hạn → 401 ngay tại service |
| Service → service | NetworkPolicy theo nhãn + token workload với `aud` = service đích | Bị chặn ở tầng mạng **và** ứng dụng |
| Z2 → Z3 | Private endpoint, tắt public network access, tắt shared key Blob | DB/Blob không truy cập được từ Internet |
| Z2 → Z4 | NetworkPolicy egress chỉ mở cho `ai` và `notification` (uat/prod) | Service khác không gọi ra Internet được |
| Trình duyệt → Blob | User delegation SAS: 1 blob, 1 quyền, hết hạn 5 phút (đọc) / 10 phút (ghi) | Link lộ chỉ dùng được trong thời gian ngắn, mỗi lần cấp có audit |

## 5. Danh tính, xác thực và phân quyền

### 5.1 Đăng nhập và gọi API

```mermaid
sequenceDiagram
    autonumber
    actor U as Người dùng
    participant W as web (SPA)
    participant IDP as IdP (Keycloak / Entra)
    participant GW as api-gateway
    participant S as Service (vd. clinical)
    participant C as clinical /internal identity

    U->>W: mở trang
    W->>IDP: Authorization Code + PKCE (code_challenge)
    IDP->>U: đăng nhập (nhân viên: bắt buộc MFA)
    IDP-->>W: code
    W->>IDP: đổi code + code_verifier
    IDP-->>W: access token (≤ 15 phút, chỉ giữ trong bộ nhớ)
    W->>GW: GET /fhir/Patient/123 (Bearer)
    GW->>GW: Lớp 1: chữ ký, iss, aud, exp (JWKS cache)
    GW->>S: chuyển tiếp nguyên token
    S->>S: Lớp 1 lại + Lớp 2 role (patient/doctor/nurse/admin/dpo)
    S->>C: Lớp 3: sub → fhirUser (cache 60 s, kiểm disabled)
    S->>S: Lớp 4: ma trận + quan hệ (own / care / break-glass)
    S->>S: Lớp 5: ép bộ lọc vào truy vấn (vd. patient = mình)
    S--)S: Lớp 6: phát audit-recorded (cho phép và từ chối)
    S-->>U: 200, hoặc 403 / 404 (không lộ sự tồn tại)
```

### 5.2 Các lớp kiểm soát và nơi thực thi

```mermaid
flowchart LR
  T(["Token"]) --> L1["1. Xác thực<br/>chữ ký · iss · aud · exp · mfa"]
  L1 --> L2["2. Role<br/>từ claim roles"]
  L2 --> L3["3. Danh tính FHIR<br/>sub → Patient/… | Practitioner/…"]
  L3 --> L4["4. Chính sách tài nguyên<br/>contracts/authz/matrix.yaml<br/>mặc định từ chối"]
  L4 --> L5["5. Lọc dữ liệu<br/>ép điều kiện vào query"]
  L5 --> OK(["Dữ liệu trả về"])
  L1 & L2 & L3 & L4 -. "từ chối" .-> DENY(["401 / 403 / 404"])
  L4 & DENY -.-> L6["6. Audit mọi quyết định"]

  classDef gw fill:#e8f1ff,stroke:#3b6db3;
  class L1 gw;
```

Lớp 1 chạy ở **cả** gateway và service; lớp 2–6 chỉ ở service sở hữu dữ liệu (thư viện `shp-web` + `shp-authz`). Ma trận được nhúng vào jar lúc
build và sinh test tự động; endpoint thiếu `@RequireShpAuthz` làm test đỏ.

### 5.3 Gọi thay mặt người dùng và gọi nội bộ

```mermaid
flowchart TB
  subgraph OBO["AI đọc/ghi dữ liệu bệnh nhân"]
    direction LR
    U1(["Token bác sĩ<br/>aud = ai"]) --> AI1["ai"]
    AI1 -- "token exchange RFC 8693 /<br/>on-behalf-of" --> IDP1["IdP"]
    IDP1 -- "token mới: vẫn là bác sĩ,<br/>aud = clinical" --> AI1
    AI1 --> CLI1["clinical<br/>áp ma trận cho chính bác sĩ đó"]
  end
  subgraph S2S["Service gọi /internal"]
    direction LR
    SCH2["scheduling"] -- "token workload<br/>aud = clinical, role internal.clinical" --> CLI2["clinical /internal/v1/…"]
    NP2["NetworkPolicy"] -. "chỉ cho nhãn scheduling,<br/>notification, document" .-> CLI2
  end
  subgraph JOB["Job nền"]
    direction LR
    J["sinh slot · chấm điểm · dọn dẹp"] -- "service identity<br/>audit: actor=system, HOPERAT" --> D["DB của chính service"]
  end
```

AI **không có quyền riêng** để đọc hồ sơ: nếu bác sĩ không được xem bệnh nhân thì AI gọi thay bác sĩ cũng bị từ chối. Mọi thao tác ghi
từ AI (tạo `Observation` từ trích xuất, đặt lịch qua chatbot) chỉ chạy **sau khi người dùng xác nhận**.

> ADR-0017 (đang review) đề xuất dùng Keycloak cho mọi môi trường thay cho Entra ID ở cloud. Nếu được chấp nhận, chỉ ô IdP trong các sơ
> đồ trên thay đổi; luồng PKCE, token exchange và các lớp kiểm soát giữ nguyên.

## 6. Bảo vệ dữ liệu sức khỏe

### 6.1 Phân loại dữ liệu và nơi lưu

```mermaid
flowchart LR
  subgraph C1["Rất nhạy cảm — PHI"]
    direction TB
    R1["Hồ sơ FHIR: chẩn đoán,<br/>sinh hiệu, dị ứng"]
    R2["Tài liệu: PDF/ảnh kết quả"]
  end
  subgraph C2["Định danh cá nhân — PII"]
    direction TB
    P1["Họ tên, ngày sinh,<br/>SĐT, email, CCCD"]
  end
  subgraph C3["Vận hành"]
    direction TB
    O1["Audit: ai, lúc nào, làm gì<br/>(không chứa nội dung lâm sàng)"]
    O2["Metric, trace, log<br/>(không chứa PII)"]
  end

  R1 --> MGC[("MongoDB clinical<br/>mã hóa at-rest, private")]
  P1 --> MGC
  R2 --> BL[("Blob documents<br/>private, versioning,<br/>quét mã độc")]
  O1 --> WORM[("Blob audit WORM<br/>+ Log Analytics")]
  O2 --> MON[("Azure Monitor")]
```

| Quy tắc | Thực thi ở đâu |
|---|---|
| Chỉ `clinical` lưu SĐT/email; chỉ một endpoint nội bộ trả thông tin liên hệ, mỗi lần gọi đều audit | `clinical` `/internal/v1/patients/{id}/contact` |
| Event không chứa PII/PHI thô (chỉ ID, nhóm tuổi `ageBand`, trạng thái) | JSON Schema trong `contracts/events`, kiểm lúc publish |
| Log/trace không chứa PII; IP trong audit được che | `shp-service-defaults`, `AuditRecordBuilder` |
| Mọi văn bản gửi LLM ngoài cụm phải qua redact PII | LLM router của `ai` (PY-06); không có cờ tắt cho DeepSeek/Gemini |
| Bệnh nhân chỉ thấy lần khám đã kết thúc và tài liệu đã phát hành | Lớp 4–5 phân quyền |
| Mã hóa: TLS khi truyền, at-rest cho mọi kho; secret chỉ ở Key Vault | Terraform + Key Vault CSI |

### 6.2 Audit chống sửa/xóa

```mermaid
flowchart LR
  ANY["Mọi service<br/>(cho phép + từ chối)"] -- "E11 audit.recorded<br/>qua outbox" --> BUS[["Bus"]]
  BUS --> AUD["audit service<br/>inbox (đúng một lần)"]
  AUD -- "batch 60 s / 5.000 bản ghi<br/>NDJSON append blob" --> WORM[("Blob audit<br/>immutability policy")]
  AUD -- "Logs Ingestion API" --> LAW[("Log Analytics<br/>SHPAudit_CL — truy vấn KQL")]
  WORM -. "header mỗi batch:<br/>prevHash → SHA-256 chuỗi" .-> VER["audit-verify<br/>phát hiện đứt chuỗi"]
  LAW --> DPO(["DPO: tra cứu, xem<br/>break-glass, cảnh báo"])
```

Không ai (kể cả Owner) xóa được blob audit trong thời gian khóa ở prod. Log Analytics phục vụ truy vấn nhanh; Blob WORM là bản gốc để đối chiếu.

## 7. Độ tin cậy của luồng event

Ghi dữ liệu và phát event **không bao giờ lệch nhau**: event nằm trong cùng transaction với dữ liệu, và mỗi consumer xử lý đúng một lần.

```mermaid
sequenceDiagram
    participant API as Use case (app/)
    participant DB as DB của producer
    participant RL as Outbox relay
    participant BUS as Bus (at-least-once)
    participant IN as Inbox consumer
    participant CDB as DB của consumer
    participant DLQ as Dead-letter

    API->>DB: BEGIN · ghi dữ liệu · outbox.add(event) · COMMIT
    loop mỗi ~1 giây
        RL->>DB: lấy event chưa gửi (SKIP LOCKED / lockedUntil)
        RL->>BUS: publish CloudEvent (id UUIDv7, traceparent)
        RL->>DB: đánh dấu đã gửi
    end
    Note over RL,BUS: Relay chết sau publish, trước khi đánh dấu → gửi lại → trùng
    BUS->>IN: giao event (có thể lặp)
    IN->>CDB: BEGIN · INSERT inbox(event.id) · xử lý · COMMIT
    alt event.id đã có trong inbox
        IN-->>BUS: ack, bỏ qua (đúng một lần)
    else xử lý lỗi tạm thời
        IN-->>BUS: nack → retry có backoff
    else lỗi lặp lại quá ngưỡng
        BUS->>DLQ: chuyển dead-letter + cảnh báo
    end
```

## 8. CI/CD và GitOps

### 8.1 Bốn repository

```mermaid
flowchart LR
  APP["smart-hospital-platform<br/>services, libs, web, contracts, docs<br/>caller svc-*.yml"]
  TPL["shp-platform-templates<br/>reusable workflows<br/>chart microservice (OCI)"]
  INF["shp-infra<br/>Terraform bootstrap/modules/envs<br/>env-provision · env-destroy · cost-guard"]
  OPS["shp-gitops<br/>Argo CD root, add-on, policy,<br/>values từng service theo env"]

  APP -- "uses …@vX.Y.Z (ghim version)" --> TPL
  INF -- "uses terraform-checks" --> TPL
  APP -- "bot: PR đổi tag/digest image" --> OPS
  OPS -- "chart microservice từ ACR" --> TPL
  INF -- "tạo cluster + cài Argo CD trỏ về" --> OPS
```

### 8.2 Pipeline của một service

```mermaid
flowchart LR
  PR(["PR vào main"]) --> CHK
  subgraph CHK["Kiểm tra bắt buộc (fail là dừng)"]
    direction TB
    C1["format · lint"] --> C2["build, warning = error"]
    C2 --> C3["unit + integration test<br/>Testcontainers, coverage ≥ 70%"]
    C3 --> C4["quét dependency · CodeQL"]
    C4 --> C5["hadolint · gitleaks"]
    C5 --> C6["contracts, authz matrix,<br/>traceability (qa.yml)"]
  end
  CHK --> REV{"CODEOWNERS review<br/>+ pr-gate xanh"}
  REV -- "merge (squash, linear)" --> MAIN(["main"])
  MAIN --> IMG
  subgraph IMG["container.yml"]
    direction TB
    I1["build image"] --> I2["Trivy: chặn High/Critical có bản vá"]
    I2 --> I3["SBOM"] --> I4["cosign keyless (OIDC)"] --> I5["push ACR (digest)"]
  end
  IMG --> G1{"Environment<br/>deploy-uat"}
  G1 -- "duyệt" --> P1["gitops-promote:<br/>PR envs/uat"]
  P1 --> A1["Argo CD sync UAT"]
  A1 --> G2{"Environment deploy-prod<br/>+ CODEOWNERS envs/prod"}
  G2 -- "duyệt" --> P2["promote-prod:<br/>PR envs/prod"]
  P2 --> A2["Argo CD sync prod<br/>Argo Rollouts canary"]
  A2 --> G3{"promote canary<br/>thủ công"}
```

Không có `kubectl apply` hay `helm install` tay: Argo CD là đường duy nhất thay đổi cluster. **Rollback = revert commit** trong `shp-gitops`.
GitHub đăng nhập Azure bằng OIDC federated credential, mỗi môi trường một identity, không lưu client secret.

### 8.3 Cổng phê duyệt

| Hành động | Cơ chế | Người duyệt |
|---|---|---|
| Merge code | PR + CI xanh + CODEOWNERS | Service owner hoặc PM |
| Deploy UAT | Environment `deploy-uat` + PR GitOps | DevOps |
| Promote prod | Environment `deploy-prod` + PR GitOps + promote canary | DevOps + Architect |
| Dựng / hủy môi trường | `env-provision` / `env-destroy` + Environment `infra-<env>` | DevOps |

## 9. Môi trường và vòng đời

```mermaid
stateDiagram-v2
    [*] --> Khongton: mặc định (không trả tiền)
    Khongton --> Dang_dung: env-provision (plan → duyệt infra-<env> → apply)
    Dang_dung --> Bootstrap: AKS + dữ liệu managed (~20–30 phút)
    Bootstrap --> Hoat_dong: cài Argo CD → sync add-on → sync service → seed dữ liệu giả
    Hoat_dong --> Hoat_dong: deploy qua PR GitOps
    Hoat_dong --> Qua_TTL: sống quá ttl-hours
    Qua_TTL --> Hoat_dong: cost-guard chỉ cảnh báo
    Hoat_dong --> Dang_huy: env-destroy (gõ tên env + duyệt infra-<env>-destroy)
    Qua_TTL --> Dang_huy: env-destroy
    Dang_huy --> Khongton: xóa toàn bộ resource group
    Khongton: Không tồn tại
    Dang_dung: Đang dựng
    Hoat_dong: Hoạt động
    Qua_TTL: Quá TTL
    Dang_huy: Đang hủy
```

| | local | uat | prod |
|---|---|---|---|
| Chạy ở | kind trên máy dev | AKS ephemeral | AKS ephemeral |
| Mục đích | viết code, test với đủ phụ thuộc | tích hợp, nghiệm thu, load/failure test | chỉ demo/bảo vệ |
| IdP | Keycloak | Entra ID | Entra ID |
| Bus | RabbitMQ | Service Bus | Service Bus |
| Blob | Azurite | Azure Storage | Azure Storage (GZRS, WORM khóa) |
| Search | OpenSearch (profile `full`) | AI Search | AI Search |
| Policy | NetworkPolicy tắt | bật | bật |
| Ai dựng | dev (`make -C dev …`) | DevOps, sau duyệt | DevOps, sau duyệt |

**Cụm local** (`dev/`) dùng **cùng chart `microservice`** với cloud:

```mermaid
flowchart LR
  subgraph KIND["kind cluster trên máy dev"]
    direction TB
    subgraph CORE["profile core (~6 GB)"]
      direction LR
      kPG[("PostgreSQL")]
      kMG[("MongoDB replica set")]
      kRD[("Redis")]
      kRMQ[["RabbitMQ"]]
      kAZ[("Azurite")]
      kKC["Keycloak<br/>+ seed users"]
    end
    subgraph FULL["profile full (thêm)"]
      direction LR
      kOS[("OpenSearch")]
      kOL["Ollama"]
      kAV["ClamAV"]
      kMF[("MLflow")]
      kOB["Grafana · Tempo ·<br/>Prometheus · Loki"]
    end
    kSVC["8 service + web<br/>chart microservice"]
  end
  kSVC --> CORE
  kSVC -.-> FULL
```

## 10. Quan sát (observability)

```mermaid
flowchart LR
  subgraph PODS["Mọi service"]
    direction TB
    OT1["OpenTelemetry SDK / agent<br/>trace · metric · log"]
    ACT["/actuator/prometheus<br/>/health/liveness · readiness"]
  end
  OT1 -- "OTLP" --> COL["OTel Collector<br/>namespace observability<br/>lọc PII, gắn nhãn env/service"]
  ACT -- "scrape" --> PROM
  COL --> AM["App Insights<br/>(trace phân tán)"]
  COL --> LAW[("Log Analytics")]
  COL --> PROM[("Managed Prometheus")]
  PROM --> GRAF["Managed Grafana<br/>dashboard SLO, DLQ,<br/>LLM cost, model metrics"]
  PROM --> ALR["Alert rules<br/>error rate, p95, DLQ,<br/>drift, break-glass"]
  PROM --> RO["Argo Rollouts<br/>AnalysisTemplate"]
  ALR --> ONC(["DevOps / DPO"])
```

`traceparent` được đưa vào mọi CloudEvent, nên một trace đi xuyên REST và event (vd. đặt lịch → nhắc lịch → chấm điểm no-show). Local thay
Azure Monitor bằng Grafana + Tempo + Prometheus + Loki (profile `full`).

## 11. AI và ML

### 11.1 LLM router với fallback

```mermaid
stateDiagram-v2
    [*] --> KiemDauVao
    KiemDauVao --> TuChoi: quá dài / mẫu prompt injection
    KiemDauVao --> TruyHoi: hợp lệ
    TruyHoi --> RetrievalOnly: không có đoạn đạt ngưỡng
    TruyHoi --> Redact: có ngữ cảnh (đã security trimming)
    Redact --> DeepSeek
    DeepSeek --> KiemKetQua: OK
    DeepSeek --> Gemini: timeout / 5xx / 429 / circuit mở
    Gemini --> KiemKetQua: OK
    Gemini --> Ollama: lỗi
    Ollama --> KiemKetQua: OK
    Ollama --> RetrievalOnly: lỗi
    KiemKetQua --> TraLoi: schema đúng, trích dẫn khớp chunk, không chẩn đoán
    KiemKetQua --> RetrievalOnly: không đạt
    TraLoi --> [*]
    RetrievalOnly --> [*]
    TuChoi --> [*]
    KiemDauVao: Kiểm đầu vào
    TruyHoi: Truy hồi hybrid BM25 + vector
    Redact: Che PII
    KiemKetQua: Kiểm kết quả
    TraLoi: Trả lời + trích dẫn
    RetrievalOnly: Chỉ trả danh sách tài liệu
    TuChoi: Từ chối
```

Mỗi lần gọi ghi audit `ai-query` (model, token, chi phí, mức fallback — **không** ghi nội dung câu hỏi). Ngân sách token theo ngày và circuit
breaker theo từng model nằm trong router dùng chung (PY-08, PY-09).

### 11.2 MLOps cho mô hình no-show

```mermaid
flowchart LR
  subgraph TRAIN["Huấn luyện (job trên cluster)"]
    direction TB
    D1[("Parquet dữ liệu giả<br/>hash phiên bản")] --> D2["kiểm tra dữ liệu<br/>(pandera)"]
    D2 --> D3["feature package dùng chung<br/>(train = serve)"]
    D3 --> D4["LR baseline · LightGBM"]
    D4 --> D5["MLflow run<br/>tham số, metric, git SHA, image digest"]
  end
  D5 --> GATE{"Cổng chất lượng<br/>AUC ≥ 0,75 · nhóm con · ECE ≤ 0,05<br/>p95 ≤ 100 ms · không thoái lui"}
  GATE -- "không đạt" --> KEEP["giữ model production<br/>+ cảnh báo lý do"]
  GATE -- "đạt" --> CAND["registry alias candidate<br/>+ model card"]
  CAND --> SH["shadow ≥ 1.000 dự đoán / 24 h"]
  SH --> C10["canary 10%"] --> C50["canary 50%"] --> PROD["alias production"]
  C10 & C50 -. "AnalysisTemplate vi phạm" .-> RB["tự rollback"]

  subgraph SERVE["Phục vụ và giám sát"]
    direction TB
    E1["E1 appointment-booked"] --> SC["ml chấm điểm"] --> E10["E10 no-show-scored → notification"]
    E5["E5 status-changed (nhãn thật)"] --> MON["job hằng ngày:<br/>PSI drift · AUC cuộn 14 ngày"]
  end
  PROD --> SC
  MON -- "drift / AUC giảm,<br/>≥ 500 nhãn mới, cooldown 7 ngày" --> TRAIN
```

## 12. Ánh xạ sơ đồ → quyết định

| Sơ đồ | Quyết định / tài liệu gốc |
|---|---|
| §1, §2 C4 | [ADR-0001](adr/0001-microservice-boundaries.md), [ADR-0008](adr/0008-polyglot-persistence.md), [ADR-0014](adr/0014-database-per-service-review.md), [ADR-0015](adr/0015-java-for-all-services.md), [ADR-0016](adr/0016-frontend-web-app.md) |
| §3 Triển khai | [ADR-0004](adr/0004-azure-first-portable.md), [ADR-0011](adr/0011-two-environments-local-kubernetes.md); repo `shp-infra`, `shp-gitops` |
| §4 Mạng | ADR-0004 (NetworkPolicy, Kyverno, private endpoint); [overview §1](overview.md#1-sơ-đồ-kiến-trúc-tổng-thể) |
| §5 Danh tính | [15-authorization.md](detailed-design/15-authorization.md), [07-api-conventions.md](detailed-design/07-api-conventions.md) |
| §6 Dữ liệu | [05-storage-audit-blob.md](detailed-design/05-storage-audit-blob.md), [ADR-0013](adr/0013-llm-deepseek-gemini-local-embedding.md) |
| §7 Event | [13-events.md](detailed-design/13-events.md), [ADR-0005](adr/0005-booking-consistency.md) |
| §8 CI/CD | [ADR-0009](adr/0009-cicd-ephemeral-environments.md), [ADR-0010](adr/0010-repository-strategy.md) |
| §9 Môi trường | ADR-0009, ADR-0011, [dev/README.md](../../dev/README.md) |
| §10 Quan sát | ADR-0004 (Observability) |
| §11 AI/ML | [ADR-0006](adr/0006-llm-router-fallback.md), ADR-0013, [ADR-0007](adr/0007-no-show-ml-mlops.md), [06-data-ai.md](detailed-design/06-data-ai.md), [16-mlops.md](detailed-design/16-mlops.md) |

Khi thay đổi kiến trúc: sửa ADR trước, rồi cập nhật sơ đồ tương ứng ở đây và trong [overview.md](overview.md) trong cùng PR.
