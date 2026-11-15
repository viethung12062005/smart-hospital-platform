# Detailed architecture diagrams — Smart Hospital Platform

> Task: ARC-04. Adds the **views** that [overview.md](overview.md) does not draw: C4 Context/Container, deployment on Azure,
> network trust zones, identity and authorization, data protection, CI/CD and GitOps, environments, observability, AI/ML.
>
> - Detailed business flows (booking, reminders, visit, documents, RAG, break-glass): [14-sequences.md](detailed-design/14-sequences.md).
> - Events: [13-events.md](detailed-design/13-events.md) · Authorization: [15-authorization.md](detailed-design/15-authorization.md) · MLOps: [16-mlops.md](detailed-design/16-mlops.md).
> - Every diagram uses Mermaid and renders directly on GitHub. The system only processes **synthetic patient data**, but the security design is as for real data.

Table of contents:
[1. C4 — Context](#1-c4-level-1--system-context) ·
[2. C4 — Container](#2-c4-level-2--container) ·
[3. Deployment on Azure](#3-deployment-view-on-azure) ·
[4. Network trust zones](#4-trust-zones-and-network-flow) ·
[5. Identity and authorization](#5-identity-authentication-and-authorization) ·
[6. Data protection](#6-protecting-health-data) ·
[7. Event reliability](#7-reliability-of-the-event-flow) ·
[8. CI/CD and GitOps](#8-cicd-and-gitops) ·
[9. Environments](#9-environments-and-lifecycle) ·
[10. Observability](#10-observability) ·
[11. AI and ML](#11-ai-and-ml) ·
[12. Diagram → decision mapping](#12-diagram--decision-mapping)

---

## 1. C4 level 1 — System Context

Who uses the system, and what the system depends on externally.

```mermaid
flowchart TB
  PAT(["Patient<br/>books appointments, views their own record,<br/>asks the booking chatbot"])
  STF(["Doctor · Nurse<br/>schedule, queue, records,<br/>documents, AI assistant"])
  ADM(["Admin · DPO<br/>users, knowledge base,<br/>audit, break-glass"])

  SHP["Smart Hospital Platform<br/>web PWA + 8 microservices on Kubernetes<br/>FHIR-native, event-driven"]

  IDP["OIDC identity provider<br/>Keycloak (local) · Entra ID (cloud)"]
  LLM["External LLM<br/>DeepSeek API → Gemini free tier"]
  MSG["Messaging channels<br/>Push FCM/APNs · SMS · Email"]
  AZ["Azure managed services<br/>DB, Blob, Service Bus, AI Search,<br/>Key Vault, Monitor"]

  PAT & STF & ADM -- "HTTPS (browser)" --> SHP
  PAT & STF & ADM -. "OIDC sign-in + PKCE" .-> IDP
  SHP -- "verify JWT with JWKS" --> IDP
  SHP -- "PII-redacted text only" --> LLM
  SHP -- "reminders, DPO alerts" --> MSG
  SHP -- "private endpoint" --> AZ
```

| External party | Data exchanged | Control |
|---|---|---|
| IdP | OIDC token; no clinical data | PKCE, token ≤ 15 minutes, staff must use MFA (`amr` contains `mfa`) |
| LLM | Question/context **with PII redacted** | Only the `ai` service has egress; every call goes through the redactor (ADR-0013) |
| Messaging channels | Minimal reminder content, phone number/email | Only the `notification` service has egress; content contains no diagnosis |
| Azure managed | All stored data | Private endpoint, encryption at rest, Workload Identity (no keys) |

## 2. C4 level 2 — Container

The independently deployable units (containers) and each one's data store. Each service **owns** its own store; no service reads another service's DB.

```mermaid
flowchart TB
  USR(["User<br/>(browser)"])

  subgraph SHP["Smart Hospital Platform"]
    direction TB
    WEB["web<br/>React + TS + Mantine, nginx<br/>static SPA, strict CSP"]
    GW["api-gateway<br/>Spring Cloud Gateway<br/>layer 1 JWT, rate limit, block /internal"]

    subgraph BIZ["Java 21 · Spring Boot"]
      direction LR
      SCH["scheduling<br/>slots, booking, check-in"]
      CLI["clinical<br/>FHIR record, identity link,<br/>care relationship, break-glass"]
      DOC["document<br/>metadata, SAS, malware scan"]
      NTF["notification<br/>reminder worker, retry, DLQ"]
      AUD["audit<br/>append-only, hash chain"]
    end

    subgraph PYS["Python 3.12 · FastAPI"]
      direction LR
      AI["ai<br/>RAG, summarization, extraction,<br/>chatbot, LLM router"]
      ML["ml<br/>no-show scoring,<br/>drift, retrain"]
    end

    BUS[["Message bus<br/>topic shp-events · CloudEvents"]]

    PG[("PostgreSQL<br/>scheduling")]
    RD[("Redis<br/>slot cache")]
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
    OLL["Ollama in the cluster<br/>bge-m3 embedding · qwen2.5"]
  end

  USR -- "/" --> WEB
  USR -- "/fhir, /api (same origin)" --> GW
  USR -. "direct PUT/GET with SAS" .-> BLOB

  GW --> SCH & CLI & DOC & NTF & AUD & AI & ML

  SCH -- "/internal: identity, role" --> CLI
  NTF -- "/internal: contact" --> CLI
  DOC -- "/internal: care check" --> CLI
  AI -- "user token (OBO)" --> CLI & SCH

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

| Container | Technology | Inbound communication | Outbound communication | Data owned |
|---|---|---|---|---|
| web | React, Vite, nginx | HTTPS `/` | — (the browser calls the gateway) | — |
| api-gateway | Spring Cloud Gateway (WebFlux) | HTTPS `/fhir`, `/api` | REST to services | — |
| scheduling | Java | REST, events E6, E7 | REST clinical, events E1–E5 | Schedule, Slot, Appointment |
| clinical | Java | REST, `/internal`, events E1/E4/E5 | events E6, E7 | Patient, Practitioner, Encounter, Observation, Condition… |
| document | Java | REST, `/internal` | Blob, events E8, E9 | DocumentReference + file |
| notification | Java worker | REST (optional message intake), events | Push/SMS/Email | reminder, delivery attempt |
| audit | Java worker | event E11 | Blob WORM, Log Analytics | AuditEvent |
| ai | Python | REST, event E8 | external LLM, Ollama, AI Search, clinical (OBO) | kb, conversations, extraction job |
| ml | Python | REST, events E1/E5 | event E10, MLflow | feature state, prediction log, model |

## 3. Deployment view on Azure

One cloud environment (`uat` or `prod`) = **one Terraform stack** (`shp-infra/envs/<env>`), one resource group, one AKS cluster. The two environments
use separate subscriptions/identities (NFR-D4). The only always-on resources are the `bootstrap` stack.

```mermaid
flowchart TB
  NET(["Internet"])

  subgraph BOOT["Stack bootstrap — always on, contains no patient data"]
    direction LR
    TFS[("Terraform state<br/>Storage")]
    ACR[("Azure Container Registry<br/>signed images + OCI chart")]
    GHID["Managed identity<br/>GitHub OIDC federated"]
  end

  subgraph ENV["Stack envs/&lt;uat|prod&gt; — ephemeral, created by env-provision"]
    direction TB
    FD["Front Door + WAF<br/>(OWASP rule set)"]

    subgraph VNET["VNet vnet-&lt;env&gt;"]
      direction TB

      subgraph SAKS["Subnet snet-aks"]
        direction TB
        subgraph AKS["AKS — 3 availability zones"]
          direction LR
          NPS["Node pool system<br/>CoreDNS, Argo CD,<br/>platform add-ons"]
          NPA["Node pool apps<br/>gateway, web,<br/>5 Java services, ml"]
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
  VNET -. "name resolution" .-> DNS
  AKS -- "OTLP / metric / log" --> MON
  GHID -. "terraform apply<br/>(after approval)" .-> ENV
```

**Inside the cluster** (repo `shp-gitops`): Argo CD creates the add-ons first (sync wave `-3`), then the baseline, then the services.

```mermaid
flowchart LR
  subgraph K8S["AKS cluster"]
    direction TB
    subgraph PLAT["Platform add-ons (platform/addons)"]
      direction LR
      ARGO["argocd<br/>Argo CD"]
      CM["cert-manager<br/>ClusterIssuer"]
      KY["kyverno<br/>admission policy"]
      RO["argo-rollouts<br/>canary"]
      KE["keda<br/>scale by queue/cron"]
      EG["envoy-gateway-system<br/>Gateway API"]
      OT["observability<br/>OTel Collector"]
    end
    subgraph APPS["namespace shp-apps (shp-baseline)"]
      direction LR
      GWY["Gateway shp-gateway<br/>HTTPS listener"]
      NP["NetworkPolicy<br/>default-deny-ingress"]
      SVCS["9 Deployment/Rollout<br/>microservice chart<br/>HPA · PDB · probe"]
      CSI["Key Vault CSI<br/>+ Workload Identity"]
    end
  end
  ARGO -- "sync from shp-gitops" --> PLAT & APPS
  EG --> GWY --> SVCS
  KY -. "block violating pods" .-> SVCS
  KE -. "scale" .-> SVCS
  RO -. "control canary" .-> SVCS
  CSI -. "mount secrets" .-> SVCS
```

| Kyverno policy | Purpose |
|---|---|
| `shp-restrict-registry` | Allow images only from the project's ACR |
| `shp-verify-signature` | Images must have a cosign keyless signature from the CI pipeline (NFR-SEC6) |
| `shp-disallow-latest` | Forbid the `latest` tag, require a fixed tag/digest |
| `shp-require-resources-probes` | Require requests/limits and readiness/liveness probes |

## 4. Trust zones and network flow

Every arrow that crosses a zone boundary has a control point. The default is **deny**; only the flows that are needed are opened.

```mermaid
flowchart LR
  subgraph Z0["Z0 — Untrusted"]
    BR(["Browser"])
  end

  subgraph Z1["Z1 — Edge"]
    direction TB
    WAF["Front Door + WAF<br/>TLS 1.2+, OWASP, rate limit"]
    GWY["Envoy Gateway<br/>TLS (cert-manager)"]
  end

  subgraph Z2["Z2 — Application (shp-apps, default-deny)"]
    direction TB
    WEB["web"]
    GW["api-gateway<br/>layer 1 JWT check"]
    SVC["business services<br/>re-check JWT + ABAC"]
    INT["/internal/v1/**<br/>only the listed services"]
  end

  subgraph Z3["Z3 — Data (private endpoint, not public)"]
    DATA[("DB · Blob · Bus · Search · Key Vault")]
  end

  subgraph Z4["Z4 — Controlled external"]
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
  BR -. "SAS for 1 blob, ≤ 5–10 minutes" .-> DATA
  SVC -- "egress only ai → LLM<br/>(after PII redaction)" --> LLM
  SVC -- "egress only notification" --> MSGP
  GW & SVC -. "JWKS" .-> IDP
```

| Boundary | Control | If it is crossed |
|---|---|---|
| Z0 → Z1 | WAF, TLS, rate limiting | Request is blocked at the edge, never enters the cluster |
| Z1 → Z2 | Only routes `/`, `/fhir`, `/api`; **there is no `/internal` route** | 404 at the gateway |
| Gateway → service | Services **do not trust** headers added by the gateway; they verify the token themselves | Forged/expired token → 401 right at the service |
| Service → service | Label-based NetworkPolicy + workload token with `aud` = target service | Blocked at the network layer **and** the application layer |
| Z2 → Z3 | Private endpoint, public network access disabled, Blob shared key disabled | DB/Blob cannot be reached from the Internet |
| Z2 → Z4 | Egress NetworkPolicy opened only for `ai` and `notification` (uat/prod) | Other services cannot call out to the Internet |
| Browser → Blob | User delegation SAS: 1 blob, 1 permission, expires in 5 minutes (read) / 10 minutes (write) | A leaked link works only for a short time, and every issuance is audited |

## 5. Identity, authentication and authorization

### 5.1 Sign-in and API calls

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant W as web (SPA)
    participant IDP as IdP (Keycloak / Entra)
    participant GW as api-gateway
    participant S as Service (e.g. clinical)
    participant C as clinical /internal identity

    U->>W: open the page
    W->>IDP: Authorization Code + PKCE (code_challenge)
    IDP->>U: sign in (staff: MFA required)
    IDP-->>W: code
    W->>IDP: exchange code + code_verifier
    IDP-->>W: access token (≤ 15 minutes, kept in memory only)
    W->>GW: GET /fhir/Patient/123 (Bearer)
    GW->>GW: Layer 1: signature, iss, aud, exp (JWKS cache)
    GW->>S: forward the token unchanged
    S->>S: Layer 1 again + Layer 2 role (patient/doctor/nurse/admin/dpo)
    S->>C: Layer 3: sub → fhirUser (60 s cache, check disabled)
    S->>S: Layer 4: matrix + relationship (own / care / break-glass)
    S->>S: Layer 5: enforce the filter in the query (e.g. patient = self)
    S--)S: Layer 6: emit audit-recorded (allow and deny)
    S-->>U: 200, or 403 / 404 (does not reveal existence)
```

### 5.2 Control layers and where they are enforced

```mermaid
flowchart LR
  T(["Token"]) --> L1["1. Authentication<br/>signature · iss · aud · exp · mfa"]
  L1 --> L2["2. Role<br/>from the roles claim"]
  L2 --> L3["3. FHIR identity<br/>sub → Patient/… | Practitioner/…"]
  L3 --> L4["4. Resource policy<br/>contracts/authz/matrix.yaml<br/>deny by default"]
  L4 --> L5["5. Data filtering<br/>enforce conditions in the query"]
  L5 --> OK(["Data returned"])
  L1 & L2 & L3 & L4 -. "deny" .-> DENY(["401 / 403 / 404"])
  L4 & DENY -.-> L6["6. Audit every decision"]

  classDef gw fill:#e8f1ff,stroke:#3b6db3;
  class L1 gw;
```

Layer 1 runs at **both** the gateway and the service; layers 2–6 run only in the service that owns the data (the `shp-web` + `shp-authz` libraries). The matrix is embedded in the jar at
build time and used to generate tests automatically; an endpoint missing `@RequireShpAuthz` makes the test fail.

### 5.3 On-behalf-of calls and internal calls

```mermaid
flowchart TB
  subgraph OBO["AI reading/writing patient data"]
    direction LR
    U1(["Doctor token<br/>aud = ai"]) --> AI1["ai"]
    AI1 -- "RFC 8693 token exchange /<br/>on-behalf-of" --> IDP1["IdP"]
    IDP1 -- "new token: still the doctor,<br/>aud = clinical" --> AI1
    AI1 --> CLI1["clinical<br/>applies the matrix for that same doctor"]
  end
  subgraph S2S["Service calling /internal"]
    direction LR
    SCH2["scheduling"] -- "workload token<br/>aud = clinical, role internal.clinical" --> CLI2["clinical /internal/v1/…"]
    NP2["NetworkPolicy"] -. "only for the scheduling,<br/>notification, document labels" .-> CLI2
  end
  subgraph JOB["Background jobs"]
    direction LR
    J["slot generation · scoring · cleanup"] -- "service identity<br/>audit: actor=system, HOPERAT" --> D["the service's own DB"]
  end
```

AI **has no permissions of its own** to read records: if a doctor is not allowed to view a patient, AI calling on the doctor's behalf is denied as well. Every write
from AI (creating an `Observation` from extraction, booking via the chatbot) runs **only after the user confirms**.

> ADR-0017 (under review) proposes using Keycloak for all environments instead of Entra ID in the cloud. If accepted, only the IdP box in the diagrams
> above changes; the PKCE flow, token exchange and control layers stay the same.

## 6. Protecting health data

### 6.1 Data classification and where it is stored

```mermaid
flowchart LR
  subgraph C1["Highly sensitive — PHI"]
    direction TB
    R1["FHIR records: diagnosis,<br/>vital signs, allergies"]
    R2["Documents: result PDFs/images"]
  end
  subgraph C2["Personal identifiers — PII"]
    direction TB
    P1["Full name, date of birth,<br/>phone number, email, national ID"]
  end
  subgraph C3["Operational"]
    direction TB
    O1["Audit: who, when, what<br/>(contains no clinical content)"]
    O2["Metrics, traces, logs<br/>(contain no PII)"]
  end

  R1 --> MGC[("MongoDB clinical<br/>encrypted at rest, private")]
  P1 --> MGC
  R2 --> BL[("Blob documents<br/>private, versioning,<br/>malware scan")]
  O1 --> WORM[("Blob audit WORM<br/>+ Log Analytics")]
  O2 --> MON[("Azure Monitor")]
```

| Rule | Where it is enforced |
|---|---|
| Only `clinical` stores phone numbers/email; a single internal endpoint returns contact details, and every call is audited | `clinical` `/internal/v1/patients/{id}/contact` |
| Events contain no raw PII/PHI (only IDs, `ageBand`, status) | JSON Schema in `contracts/events`, checked at publish time |
| Logs/traces contain no PII; IP addresses in the audit are masked | `shp-service-defaults`, `AuditRecordBuilder` |
| All text sent to an LLM outside the cluster must go through PII redaction | The `ai` LLM router (PY-06); there is no off switch for DeepSeek/Gemini |
| Patients only see finished visits and released documents | Authorization layers 4–5 |
| Encryption: TLS in transit, at rest for every store; secrets only in Key Vault | Terraform + Key Vault CSI |

### 6.2 Tamper-proof audit

```mermaid
flowchart LR
  ANY["Every service<br/>(allow + deny)"] -- "E11 audit.recorded<br/>via outbox" --> BUS[["Bus"]]
  BUS --> AUD["audit service<br/>inbox (exactly once)"]
  AUD -- "batch 60 s / 5,000 records<br/>NDJSON appended to blob" --> WORM[("Blob audit<br/>immutability policy")]
  AUD -- "Logs Ingestion API" --> LAW[("Log Analytics<br/>SHPAudit_CL — KQL queries")]
  WORM -. "header of each batch:<br/>prevHash → SHA-256 chain" .-> VER["audit-verify<br/>detects a broken chain"]
  LAW --> DPO(["DPO: look up, review<br/>break-glass, alerts"])
```

No one (not even the Owner) can delete audit blobs during the retention period in prod. Log Analytics serves fast queries; Blob WORM is the original for cross-checking.

## 7. Reliability of the event flow

Writing data and publishing the event **never diverge**: the event is in the same transaction as the data, and every consumer processes exactly once.

```mermaid
sequenceDiagram
    participant API as Use case (app/)
    participant DB as Producer's DB
    participant RL as Outbox relay
    participant BUS as Bus (at-least-once)
    participant IN as Inbox consumer
    participant CDB as Consumer's DB
    participant DLQ as Dead-letter

    API->>DB: BEGIN · write data · outbox.add(event) · COMMIT
    loop every ~1 second
        RL->>DB: fetch unsent events (SKIP LOCKED / lockedUntil)
        RL->>BUS: publish CloudEvent (id UUIDv7, traceparent)
        RL->>DB: mark as sent
    end
    Note over RL,BUS: Relay dies after publish, before marking → resend → duplicate
    BUS->>IN: deliver event (may repeat)
    IN->>CDB: BEGIN · INSERT inbox(event.id) · process · COMMIT
    alt event.id is already in the inbox
        IN-->>BUS: ack, skip (exactly once)
    else transient processing error
        IN-->>BUS: nack → retry with backoff
    else error repeated beyond the threshold
        BUS->>DLQ: move to dead-letter + alert
    end
```

## 8. CI/CD and GitOps

### 8.1 Four repositories

```mermaid
flowchart LR
  APP["smart-hospital-platform<br/>services, libs, web, contracts, docs<br/>caller svc-*.yml"]
  TPL["shp-platform-templates<br/>reusable workflows<br/>microservice chart (OCI)"]
  INF["shp-infra<br/>Terraform bootstrap/modules/envs<br/>env-provision · env-destroy · cost-guard"]
  OPS["shp-gitops<br/>Argo CD root, add-ons, policy,<br/>per-service values by env"]

  APP -- "uses …@vX.Y.Z (pinned version)" --> TPL
  INF -- "uses terraform-checks" --> TPL
  APP -- "bot: PR changing the image tag/digest" --> OPS
  OPS -- "microservice chart from ACR" --> TPL
  INF -- "create the cluster + install Argo CD pointed at it" --> OPS
```

### 8.2 Pipeline of a service

```mermaid
flowchart LR
  PR(["PR into main"]) --> CHK
  subgraph CHK["Mandatory checks (any failure stops)"]
    direction TB
    C1["format · lint"] --> C2["build, warnings = errors"]
    C2 --> C3["unit + integration tests<br/>Testcontainers, coverage ≥ 70%"]
    C3 --> C4["dependency scan · CodeQL"]
    C4 --> C5["hadolint · gitleaks"]
    C5 --> C6["contracts, authz matrix,<br/>traceability (qa.yml)"]
  end
  CHK --> REV{"CODEOWNERS review<br/>+ green pr-gate"}
  REV -- "merge (squash, linear)" --> MAIN(["main"])
  MAIN --> IMG
  subgraph IMG["container.yml"]
    direction TB
    I1["build image"] --> I2["Trivy: block High/Critical with a fix"]
    I2 --> I3["SBOM"] --> I4["cosign keyless (OIDC)"] --> I5["push ACR (digest)"]
  end
  IMG --> G1{"Environment<br/>deploy-uat"}
  G1 -- "approve" --> P1["gitops-promote:<br/>PR envs/uat"]
  P1 --> A1["Argo CD sync UAT"]
  A1 --> G2{"Environment deploy-prod<br/>+ CODEOWNERS envs/prod"}
  G2 -- "approve" --> P2["promote-prod:<br/>PR envs/prod"]
  P2 --> A2["Argo CD sync prod<br/>Argo Rollouts canary"]
  A2 --> G3{"promote canary<br/>manually"}
```

There is no manual `kubectl apply` or `helm install`: Argo CD is the only path that changes the cluster. **Rollback = revert the commit** in `shp-gitops`.
GitHub signs in to Azure with an OIDC federated credential, one identity per environment, with no client secret stored.

### 8.3 Approval gates

| Action | Mechanism | Approver |
|---|---|---|
| Merge code | PR + green CI + CODEOWNERS | Service owner or PM |
| Deploy UAT | Environment `deploy-uat` + GitOps PR | DevOps |
| Promote prod | Environment `deploy-prod` + GitOps PR + promote canary | DevOps + Architect |
| Create / destroy an environment | `env-provision` / `env-destroy` + Environment `infra-<env>` | DevOps |

## 9. Environments and lifecycle

```mermaid
stateDiagram-v2
    [*] --> Khongton: default (nothing provisioned)
    Khongton --> Dang_dung: env-provision (plan → approve infra-<env> → apply)
    Dang_dung --> Bootstrap: AKS + managed data (~20–30 minutes)
    Bootstrap --> Hoat_dong: install Argo CD → sync add-ons → sync services → seed synthetic data
    Hoat_dong --> Hoat_dong: deploy via GitOps PR
    Hoat_dong --> Qua_TTL: alive longer than ttl-hours
    Qua_TTL --> Hoat_dong: cost-guard only warns
    Hoat_dong --> Dang_huy: env-destroy (type the env name + approve infra-<env>-destroy)
    Qua_TTL --> Dang_huy: env-destroy
    Dang_huy --> Khongton: delete the whole resource group
    Khongton: Not created
    Dang_dung: Provisioning
    Hoat_dong: Running
    Qua_TTL: TTL exceeded
    Dang_huy: Destroying
```

| | local | uat | prod |
|---|---|---|---|
| Runs on | kind on the dev machine | Ephemeral AKS | Ephemeral AKS |
| Purpose | writing code, testing with all dependencies | integration, acceptance, load/failure testing | demo/protection only |
| IdP | Keycloak | Entra ID | Entra ID |
| Bus | RabbitMQ | Service Bus | Service Bus |
| Blob | Azurite | Azure Storage | Azure Storage (GZRS, locked WORM) |
| Search | OpenSearch (profile `full`) | AI Search | AI Search |
| Policy | NetworkPolicy disabled | enabled | enabled |
| Provisioned by | dev (`make -C dev …`) | DevOps, after approval | DevOps, after approval |

The **local cluster** (`dev/`) uses the **same `microservice` chart** as the cloud:

```mermaid
flowchart LR
  subgraph KIND["kind cluster on the dev machine"]
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
    subgraph FULL["profile full (extra)"]
      direction LR
      kOS[("OpenSearch")]
      kOL["Ollama"]
      kAV["ClamAV"]
      kMF[("MLflow")]
      kOB["Grafana · Tempo ·<br/>Prometheus · Loki"]
    end
    kSVC["8 services + web<br/>microservice chart"]
  end
  kSVC --> CORE
  kSVC -.-> FULL
```

## 10. Observability

```mermaid
flowchart LR
  subgraph PODS["Every service"]
    direction TB
    OT1["OpenTelemetry SDK / agent<br/>trace · metric · log"]
    ACT["/actuator/prometheus<br/>/health/liveness · readiness"]
  end
  OT1 -- "OTLP" --> COL["OTel Collector<br/>namespace observability<br/>PII filtering, env/service labels"]
  ACT -- "scrape" --> PROM
  COL --> AM["App Insights<br/>(distributed tracing)"]
  COL --> LAW[("Log Analytics")]
  COL --> PROM[("Managed Prometheus")]
  PROM --> GRAF["Managed Grafana<br/>SLO, DLQ,<br/>LLM cost, model metrics dashboards"]
  PROM --> ALR["Alert rules<br/>error rate, p95, DLQ,<br/>drift, break-glass"]
  PROM --> RO["Argo Rollouts<br/>AnalysisTemplate"]
  ALR --> ONC(["DevOps / DPO"])
```

`traceparent` is included in every CloudEvent, so a trace crosses REST and events (e.g. booking → reminder → no-show scoring). Locally, Azure Monitor is replaced by
Grafana + Tempo + Prometheus + Loki (profile `full`).

## 11. AI and ML

### 11.1 LLM router with fallback

```mermaid
stateDiagram-v2
    [*] --> KiemDauVao
    KiemDauVao --> TuChoi: too long / prompt injection pattern
    KiemDauVao --> TruyHoi: valid
    TruyHoi --> RetrievalOnly: no chunk above the threshold
    TruyHoi --> Redact: context available (after security trimming)
    Redact --> DeepSeek
    DeepSeek --> KiemKetQua: OK
    DeepSeek --> Gemini: timeout / 5xx / 429 / circuit open
    Gemini --> KiemKetQua: OK
    Gemini --> Ollama: error
    Ollama --> KiemKetQua: OK
    Ollama --> RetrievalOnly: error
    KiemKetQua --> TraLoi: valid schema, citations match chunks, no diagnosis
    KiemKetQua --> RetrievalOnly: does not pass
    TraLoi --> [*]
    RetrievalOnly --> [*]
    TuChoi --> [*]
    KiemDauVao: Input validation
    TruyHoi: Hybrid retrieval BM25 + vector
    Redact: PII redaction
    KiemKetQua: Output validation
    TraLoi: Answer + citations
    RetrievalOnly: Return only the list of documents
    TuChoi: Reject
```

Every call writes an `ai-query` audit record (model, tokens, cost, fallback level — **not** the question content). The daily token budget and the per-model circuit
breaker live in the shared router (PY-08, PY-09).

### 11.2 MLOps for the no-show model

```mermaid
flowchart LR
  subgraph TRAIN["Training (job on the cluster)"]
    direction TB
    D1[("Synthetic-data Parquet<br/>versioned hash")] --> D2["data validation<br/>(pandera)"]
    D2 --> D3["shared feature package<br/>(train = serve)"]
    D3 --> D4["LR baseline · LightGBM"]
    D4 --> D5["MLflow run<br/>parameters, metrics, git SHA, image digest"]
  end
  D5 --> GATE{"Quality gate<br/>AUC ≥ 0.75 · subgroups · ECE ≤ 0.05<br/>p95 ≤ 100 ms · no regression"}
  GATE -- "not passed" --> KEEP["keep the production model<br/>+ alert with the reason"]
  GATE -- "passed" --> CAND["registry alias candidate<br/>+ model card"]
  CAND --> SH["shadow ≥ 1,000 predictions / 24 h"]
  SH --> C10["canary 10%"] --> C50["canary 50%"] --> PROD["alias production"]
  C10 & C50 -. "AnalysisTemplate violation" .-> RB["automatic rollback"]

  subgraph SERVE["Serving and monitoring"]
    direction TB
    E1["E1 appointment-booked"] --> SC["ml scoring"] --> E10["E10 no-show-scored → notification"]
    E5["E5 status-changed (real label)"] --> MON["daily job:<br/>PSI drift · 14-day rolling AUC"]
  end
  PROD --> SC
  MON -- "drift / AUC drop,<br/>≥ 500 new labels, 7-day cooldown" --> TRAIN
```

## 12. Diagram → decision mapping

| Diagram | Decision / source document |
|---|---|
| §1, §2 C4 | [ADR-0001](adr/0001-microservice-boundaries.md), [ADR-0008](adr/0008-polyglot-persistence.md), [ADR-0014](adr/0014-database-per-service-review.md), [ADR-0015](adr/0015-java-for-all-services.md), [ADR-0016](adr/0016-frontend-web-app.md) |
| §3 Deployment | [ADR-0004](adr/0004-azure-first-portable.md), [ADR-0011](adr/0011-two-environments-local-kubernetes.md); repos `shp-infra`, `shp-gitops` |
| §4 Network | ADR-0004 (NetworkPolicy, Kyverno, private endpoint); [overview §1](overview.md#1-overall-architecture-diagram) |
| §5 Identity | [15-authorization.md](detailed-design/15-authorization.md), [07-api-conventions.md](detailed-design/07-api-conventions.md) |
| §6 Data | [05-storage-audit-blob.md](detailed-design/05-storage-audit-blob.md), [ADR-0013](adr/0013-llm-deepseek-gemini-local-embedding.md) |
| §7 Events | [13-events.md](detailed-design/13-events.md), [ADR-0005](adr/0005-booking-consistency.md) |
| §8 CI/CD | [ADR-0009](adr/0009-cicd-ephemeral-environments.md), [ADR-0010](adr/0010-repository-strategy.md) |
| §9 Environments | ADR-0009, ADR-0011, [dev/README.md](../../dev/README.md) |
| §10 Observability | ADR-0004 (Observability) |
| §11 AI/ML | [ADR-0006](adr/0006-llm-router-fallback.md), ADR-0013, [ADR-0007](adr/0007-no-show-ml-mlops.md), [06-data-ai.md](detailed-design/06-data-ai.md), [16-mlops.md](detailed-design/16-mlops.md) |

When the architecture changes: update the ADR first, then update the corresponding diagram here and in [overview.md](overview.md) in the same PR.
