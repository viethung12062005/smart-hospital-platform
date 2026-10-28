# Backlog chi tiết

> Sinh tự động bằng `python3 -I tools/plan/build.py` từ `tools/plan/tasks_sa.py`, `tasks_dev.py`, `stories.py`. Không sửa tay. Import vào GitHub Projects bằng [backlog.csv](backlog.csv). Việc theo từng người: [by-person](by-person/).

Tổng: **317 task**, **1070 giờ** · dev: mỗi task ≤ 4 giờ, SA: ≤ 8 giờ.

## Tải còn lại theo người và theo tuần (giờ; task đã xong không tính)

| Tuần | SA | D1 | D2 | D3 |
|---|---|---|---|---|
| W1 | 42 |  |  |  |
| W2 | 42 | 7 | 19 | 28 |
| W3 | 45 | 4 | 27 | 28 |
| W4 | **52** | 27 | 29 | 29 |
| W5 | 38 | 30 | 29 | 30 |
| W6 | 27 | 29 | 27 | 29 |
| W7 | 42 | 29 | 29 | 30 |
| W8 | 29 | 30 | 29 | 30 |
| W9 | 2 | 24 | 26 | 29 |
| W10 | 28 | 4 |  | 22 |
| W11 | 8 |  |  | 3 |
| W12 | 9 |  |  |  |
| **Tổng** | **364** | **184** | **215** | **258** |

In đậm = vượt ngưỡng cảnh báo (dev > 30h/tuần, SA > 45h/tuần).

## Lịch sprint

| Mã | Thời gian |
|---|---|
| S0 | W1 26/10-01/11: chốt gate G1-G3, tạo repo, onboarding |
| P1 | W2 02/11-08/11: platform 1; dev làm thư viện dùng chung + dữ liệu giả |
| P2 | W3 09/11-15/11: platform 2; dev làm thư viện dùng chung |
| P3 | W4 16/11-22/11: platform 3 (song song S1) |
| P4 | W5 23/11-29/11: identity, môi trường ephemeral, G4 (song song S2) |
| S1 | W4 16/11-22/11: dev bắt đầu service |
| S2 | W5 23/11-29/11 |
| S3 | W6 30/11-06/12 |
| S4 | W7 07/12-13/12 |
| S5 | W8 14/12-20/12 |
| S6 | W9 21/12-27/12 |
| H1 | W10 28/12-03/01: hardening, load/failure test |
| H2 | W11 04/01-10/01: eval, FinOps, bảo mật |
| DEF | W12 11/01-17/01: chuẩn bị bảo vệ |

## ARC — Kiến trúc & quản trị dự án

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| ARC-01 | ENABLER | Duyệt G1: phân tích nghiệp vụ | Phạm vi, KPI, quy tắc nghiệp vụ được nhóm chốt; trạng thái doc = Approved | SA | 2 |  | S0 | docs/01 |
| ARC-02 | ENABLER | Duyệt G2: chốt NFR và MoSCoW | Mọi NFR có số + cách đo; danh sách Must/Should/Could được chốt | SA | 3 | ARC-01 | S0 | docs/02 |
| ARC-03 | ENABLER | Duyệt G3: chuyển ADR 0001-0010 sang Accepted | Mỗi ADR truy về FR/NFR; trạng thái Accepted | SA | 3 | ARC-02 | S0 | ADR |
| ARC-04 | ENABLER | Sơ đồ C4 mức Context và Container | 2 sơ đồ (Mermaid/PlantUML) trong docs/03, khớp service catalog | SA | 4 | ARC-03 | S0 | P1 |
| ARC-05 | ENABLER | Mô hình dữ liệu từng service | Bảng resource FHIR + SearchParameter cần hỗ trợ cho từng service; owner dữ liệu rõ ràng | SA | 6 | ARC-03 | S0 | ADR-0003, ADR-0008 |
| ARC-09 | ENABLER | Chuẩn coding & conventions cho service | Tài liệu: cấu trúc thư mục, lỗi, logging không PII, tracing, cấu hình, versioning API | SA | 4 | ARC-03 | S0 | NFR-O4 |
| ARC-10 | ENABLER | Definition of Ready/Done, quy trình PR, nhánh | Tài liệu trong docs/00; PR template phản ánh DoD | SA | 2 | ARC-03 | S0 | NFR-D1 |
| ARC-11 | ENABLER | Đặc tả dữ liệu giả (synthetic) | Quy tắc sinh bệnh nhân, bác sĩ, lịch, lần khám tiếng Việt; không dùng dữ liệu thật | SA | 3 | ARC-05 | S0 | NFR-SEC9 |
| ARC-19 | ENABLER | Tạo contracts/authz/matrix.yaml đầy đủ từ ma trận phân quyền | Mọi ô của ma trận có dòng quy tắc; schema YAML được validate; dev dùng làm đầu vào cho SHR-22 | SA | 4 | ARC-03 | P1 | 15-authorization §3 |
| ARC-06 | ENABLER | Hợp đồng API (OpenAPI) khung cho 6 service | File openapi.yaml mỗi service với endpoint, mã lỗi OperationOutcome; lint không lỗi | SA | 6 | ARC-05 | P2 | P2 |
| ARC-07 | ENABLER | Hợp đồng sự kiện (JSON Schema) | Schema cho AppointmentBooked/Cancelled, DocumentUploaded, AuditRecorded, NoShowScored; có version | SA | 4 | ARC-05 | P2 | P2 |
| ARC-20 | ENABLER | Dữ liệu tham chiếu: ICD-10 (~300 mã), LOINC sinh hiệu/xét nghiệm, CodeSystem chuyên khoa và lý do hủy | File JSON trong contracts/terminology, có script nạp, được CLN-16/CLN-18 dùng | SA | 4 | ARC-03 | P2 | 01-domain-model §3 |
| ARC-13 | ENABLER | Sprint review + planning S1 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S1 | PM |
| ARC-14 | ENABLER | Sprint review + planning S2 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S2 | PM |
| ARC-15 | ENABLER | Sprint review + planning S3 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S3 | PM |
| ARC-08 | ENABLER | Threat model STRIDE | Bảng mối đe dọa theo luồng dữ liệu, biện pháp giảm thiểu truy về NFR-SEC | SA | 6 | ARC-04 | S4 | P3 |
| ARC-12 | ENABLER | Kế hoạch kiểm thử (test strategy) | Mức test, ngưỡng coverage, kịch bản load/failure test map với NFR | SA | 4 | ARC-02 | S4 | P4 |
| ARC-16 | ENABLER | Sprint review + planning S4 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S4 | PM |
| ARC-17 | ENABLER | Sprint review + planning S5 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S5 | PM |
| ARC-18 | ENABLER | Sprint review + planning S6 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S6 | PM |

## GOV — Repo, quy trình & GitHub governance

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| GOV-01 | US-070 | Tạo 4 repo theo ADR-0010 | 4 repo tồn tại, README mô tả trách nhiệm, visibility đúng | SA | 1 | ARC-03 | S0 | ADR-0010 |
| GOV-02 | US-070 | Ruleset bảo vệ nhánh main cho 4 repo | Bắt buộc PR, 1 review, CI xanh, cấm force-push; bot GitOps được bypass ở shp-gitops | SA | 2 | GOV-01 | S0 | ADR-0009 |
| GOV-03 | US-070 | CODEOWNERS + PR template + issue template | PR vào infra/gitops-prod cần SA duyệt; template có checklist DoD | SA | 2 | GOV-01 | S0 | NFR-D1 |
| GOV-07 | US-070 | GitHub Project + import backlog | Project board có cột Todo/In progress/Review/Done, field Sprint/Owner/Estimate; backlog.csv được import | SA | 2 | GOV-01 | S0 | PM |
| GOV-05 | US-070 | GitHub App bot cho GitOps | App chỉ có quyền contents:write trên shp-gitops; secret lưu ở org/repo secret | SA | 2 | GOV-01 | P2 | ADR-0010 |
| GOV-04 | US-070 | GitHub Environments và người duyệt | deploy-uat, deploy-prod, infra-<env>, infra-<env>-destroy (env = uat, prod) có reviewer và wait timer theo ADR-0009 | SA | 2 | GOV-01 | P3 | ADR-0009 |
| GOV-06 | US-070 | Dependabot cho actions, maven, pip, docker, terraform | dependabot.yml ở mỗi repo; action được ghim theo commit SHA | SA | 2 | GOV-01 | S4 | NFR-SEC6 |

## IAC — Hạ tầng Azure bằng Terraform

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| IAC-01 | ENABLER | Stack bootstrap: RG shared, state storage | Storage account có versioning, soft delete, tắt shared key; state truy cập bằng Entra ID | SA | 3 | GOV-01 | P1 | NFR-D3 |
| IAC-02 | ENABLER | Bootstrap: Azure Container Registry | ACR tắt admin user; retention policy; output login server | SA | 2 | IAC-01 | P1 | ADR-0009 |
| IAC-03 | ENABLER | Bootstrap: identity OIDC cho GitHub | Identity plan (read), apply-<env>, acr-push; federated credential theo repo + environment; RBAC tối thiểu | SA | 4 | IAC-01 | P1 | NFR-SEC4 |
| IAC-05 | ENABLER | Module network | VNet, subnet AKS/private endpoint/Postgres (delegated), private DNS zones; validate + tflint sạch | SA | 5 | IAC-01 | P2 | NFR-SEC7 |
| IAC-06 | ENABLER | Module monitoring | Log Analytics, Azure Monitor workspace (Prometheus), Managed Grafana, App Insights | SA | 4 | IAC-05 | P2 | NFR-O1, O2 |
| IAC-07 | ENABLER | Module aks | Azure CNI overlay + Cilium, Workload Identity, KV CSI, Azure RBAC, tắt local account, 3 node pool, 3 zone | SA | 8 | IAC-05, IAC-06 | P2 | ADR-0004 |
| IAC-04 | ENABLER | Bootstrap: budget alert + chính sách tag | Budget cảnh báo 50/80/100%; tag shp-env, created-at, ttl-hours bắt buộc | SA | 2 | IAC-01 | P3 | NFR-C1, C3 |
| IAC-08 | ENABLER | Module key vault | RBAC mode, private endpoint, purge protection chỉ ở prod, tên có hậu tố ngẫu nhiên | SA | 2 | IAC-05 | P3 | NFR-SEC4 |
| IAC-09 | US-067 | Module postgresql | Flexible Server 16, chỉ Entra auth (không mật khẩu), PITR 7 ngày, HA zone-redundant ở prod | SA | 4 | IAC-05 | P4 | ADR-0008, NFR-A4 |
| IAC-10 | US-067 | Module mongodb (Cosmos DB for MongoDB vCore) | Cluster private endpoint; mật khẩu sinh bằng random_password lưu Key Vault, không nằm trong code | SA | 4 | IAC-05, IAC-08 | P4 | ADR-0008 |
| IAC-11 | ENABLER | Module redis | Private endpoint, Entra auth, TLS only | SA | 2 | IAC-05 | P4 | ADR-0008 |
| IAC-12 | US-048 | Module storage | Container documents + audit (immutability WORM), lifecycle Hot->Cool 30 ngày, tắt shared key | SA | 4 | IAC-05 | P4 | NFR-A5, SEC5 |
| IAC-17 | ENABLER | Stack envs/uat, prod (lõi: network, AKS, Key Vault, ACR) + sizing | 2 stack gọi module; tfvars khác nhau về SKU/HA/spot; UAT có sizing=prod để đo hiệu năng; plan chạy được | SA | 4 | IAC-05, IAC-07, IAC-08 | P4 | NFR-D4 |
| IAC-13 | ENABLER | Module service bus | Namespace tắt local auth; RBAC cho service | SA | 2 | IAC-05 | S3 | ADR-0001 |
| IAC-14 | ENABLER | Module ai-search | Tắt API key (RBAC only), SKU theo biến môi trường | SA | 2 | IAC-05 | S3 | ADR-0008 |
| IAC-15 | ENABLER | Module workload identity cho service | Mỗi service một UAMI + federated credential (namespace/serviceaccount) + role tới đúng kho dữ liệu | SA | 4 | IAC-07 | S3 | NFR-SEC4 |
| IAC-16 | US-008, US-066 | Module identity ứng dụng (Entra ID) | App registration API + SPA, app roles patient/doctor/nurse/admin/dpo, user thử nghiệm giả | SA | 4 | IAC-01 | S3 | UC-08, NFR-SEC1 |
| IAC-20 | US-070 | Gắn các module dữ liệu và identity (IAC-09..IAC-16) vào 2 stack | plan chạy được cho cả 2 môi trường; mỗi service chỉ có quyền đúng kho dữ liệu của mình | SA | 4 | IAC-17, IAC-12, IAC-15 | S3 | NFR-D4 |
| IAC-18 | US-036 | Module Defender for Storage + Event Grid → queue cho kết quả quét | Blob mới được quét; kết quả vào queue để document service xử lý | SA | 3 | IAC-12 | S4 | 05-storage §2 |
| IAC-19 | US-055 | Node pool ai chạy Ollama (bge-m3, qwen2.5) + secret API key DeepSeek/Gemini trong Key Vault | Ollama có PVC cache model; key chỉ trong Key Vault, nạp bằng CSI, không trong repo/values | SA | 4 | IAC-05 | S4 | ADR-0006 |

## CIT — CI templates (GitHub Actions)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| CIT-01 | US-070 | Reusable workflow Java CI (Maven) | Spotless check, build -Werror, test + JaCoCo ngưỡng coverage, quét dependency lỗ hổng; dùng được từ repo khác | SA | 4 | GOV-01 | P1 | ADR-0009 |
| CIT-03 | US-070 | Reusable workflow container | hadolint, buildx build, Trivy chặn High/Critical, SBOM, ký cosign keyless, push ACR chỉ ở main, output digest | SA | 5 | IAC-02, IAC-03 | P1 | NFR-SEC6 |
| CIT-05 | US-070 | Reusable workflow Terraform checks + plan | fmt, validate, tflint, Trivy config, plan + tóm tắt vào job summary; plan qua OIDC read-only | SA | 4 | IAC-03 | P2 | NFR-D3 |
| CIT-06 | US-070 | Reusable workflow GitOps update/promote | Cập nhật digest vào shp-gitops; uat commit sau approval; prod mở PR sau approval | SA | 4 | GOV-05 | P2 | ADR-0009 |
| CIT-02 | US-070 | Reusable workflow Python CI | ruff lint+format, mypy, pytest + coverage, pip-audit | SA | 3 | GOV-01 | P3 | ADR-0009 |
| CIT-04 | US-070 | Reusable workflow Helm validate | helm lint, render từng môi trường, kubeconform strict, Trivy config | SA | 3 | GOP-01 | P3 | ADR-0009 |
| CIT-07 | US-070 | Workflow kiểm tra chung cho mọi PR | gitleaks, actionlint, dependency review, CodeQL (java-kotlin, python, actions) | SA | 3 | GOV-01 | P3 | NFR-SEC6 |
| CIT-08 | US-070 | Caller workflow mẫu cho service + script sinh | Script tạo svc-<name>.yml từ template với path filter đúng | SA | 2 | CIT-01, CIT-02, CIT-03, CIT-06 | P3 | ADR-0009 |
| CIT-09 | US-070 | Phát hành templates có version | Tag vX.Y.Z trên shp-platform-templates; chart push OCI lên ACR; CHANGELOG | SA | 2 | CIT-01, GOP-01 | S4 | ADR-0010 |
| CIT-10 | US-070 | Đóng gói Maven nội bộ cho thư viện dùng chung | Thư viện phát hành lên GitHub Packages (Maven) có version; service tham chiếu theo version | SA | 3 | SHR-01 | S4 | ADR-0010 |

## GOP — Helm & GitOps (Argo CD)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| GOP-01 | US-070 | Helm chart chung microservice | Deployment/Rollout, Service, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, ServiceAccount WI, SecretProviderClass; values.schema.json | SA | 8 | ARC-09 | P1 | ADR-0004 |
| GOP-03 | US-070 | Chart bootstrap Argo CD | AppProject, ApplicationSet platform + services theo môi trường; app-of-apps | SA | 6 | GOP-01 | P2 | ADR-0009 |
| GOP-04 | US-070 | Platform add-on: Argo Rollouts, KEDA, cert-manager | Application ghim version chart; values theo môi trường | SA | 3 | GOP-03 | P3 | NFR-A7, S1 |
| GOP-05 | US-070 | Platform add-on: Envoy Gateway (Gateway API) | GatewayClass/Gateway, TLS qua cert-manager | SA | 3 | GOP-04 | P3 | NFR-SEC3 |
| GOP-06 | US-070 | Platform add-on: OpenTelemetry Collector | Nhận OTLP, xuất sang Azure Monitor; local xuất sang Grafana stack | SA | 3 | GOP-03 | P4 | NFR-O1 |
| GOP-07 | US-070 | Kyverno + policy | Chỉ image đã ký từ ACR, cấm tag latest, bắt buộc resources/probes, PSS restricted; Enforce | SA | 4 | GOP-03 | P4 | NFR-SEC6 |
| GOP-08 | US-070 | NetworkPolicy default-deny + namespace | Namespace có nhãn PSA restricted; chỉ mở luồng đã thiết kế | SA | 2 | GOP-03 | P4 | NFR-SEC7 |
| GOP-10 | US-063 | Chart MLflow (tracking + registry) cho môi trường cluster | MLflow chạy với backend lưu trữ bền, truy cập qua Workload Identity | SA | 3 | GOP-03 | S3 | ADR-0007 |
| GOP-02 | US-070 | Unit test cho chart | helm-unittest cho các tổ hợp values chính; chạy trong CI | SA | 3 | GOP-01 | S4 | ADR-0009 |
| GOP-09 | US-070 | Canary prod với Argo Rollouts | Bước 10%-50%-100%, dừng chờ promote thủ công, rollback tự động khi lỗi | SA | 3 | GOP-04 | S4 | NFR-A7, D2 |

## ENV — Vòng đời môi trường ephemeral

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| ENV-01 | US-070 | Workflow env-provision | plan -> approval infra-<env> -> apply -> cài Argo CD + bootstrap -> chờ healthy -> seed dữ liệu giả -> smoke test | SA | 6 | IAC-17, GOP-03 | P4 | ADR-0009 |
| ENV-02 | US-070 | Workflow env-destroy | Gõ đúng tên môi trường + approval infra-<env>-destroy; destroy sạch, kiểm tra không còn resource | SA | 2 | IAC-17 | P4 | ADR-0009 |
| ENV-05 | US-070 | Diễn tập G4 | Service mẫu đi local -> uat -> prod (dựng tạm) chỉ qua pipeline; rollback bằng GitOps revert < 5 phút; image chưa ký bị chặn; destroy; có bằng chứng | SA | 4 | ENV-01, LOC-03, LOC-04, GOP-07 | S3 | Gate G4 |
| ENV-03 | US-070 | Workflow cost-guard | Chạy theo lịch; liệt kê môi trường sống quá TTL; mở/cập nhật issue cảnh báo (không tự hủy) | SA | 2 | IAC-04 | S5 | NFR-C1 |
| ENV-04 | US-070 | Runbook vòng đời môi trường | Hướng dẫn dựng/kiểm thử/hủy, thời gian dự kiến, xử lý sự cố thường gặp | SA | 2 | ENV-01, ENV-02 | S5 | NFR-D3 |
| ENV-06 | US-070 | Diễn tập dựng prod ephemeral lần 1: đo thời gian dựng/hủy, seed dữ liệu, kịch bản dự phòng nếu dựng trễ | Có số đo thời gian thực tế; có bản dự phòng (video/ảnh chụp/UAT); prod được hủy sạch sau diễn tập | SA | 4 | ENV-01, ENV-02, ENV-05 | H1 | ADR-0011 |

## OBS — Observability nền

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| OBS-04 | ENABLER | Thư viện observability chuẩn (OTel, log có trace-id, che PII) | OTel traces/metrics/logs, log có trace-id, enricher che PII; test log không chứa PII | SA | 4 | LOC-03 | S1 | NFR-O1, O4 |
| OBS-01 | ENABLER | Dashboard RED chuẩn cho mọi service | Grafana dashboard theo nhãn service: rate, errors, duration p50/p95/p99 | SA | 3 | GOP-06 | S4 | NFR-O2 |
| OBS-02 | ENABLER | SLO + cảnh báo burn rate | SLO cho NFR-A1, P1, P2; cảnh báo multi-window burn rate | SA | 3 | OBS-01 | S4 | NFR-O2, O3 |
| OBS-03 | ENABLER | Cảnh báo DLQ và lỗi pipeline | Cảnh báo khi dead-letter có message, cert sắp hết hạn, Argo CD app degraded | SA | 2 | OBS-01 | S5 | NFR-O3 |

## LOC — Môi trường local & walking skeleton

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| LOC-01 | ENABLER | Bộ công cụ dev local trên kind (profile core): cụm K8s nhỏ + Helm cho PostgreSQL, MongoDB replica set, Redis, RabbitMQ, Azurite, Keycloak; Makefile up/down/status | `make up` dựng cụm và mọi phụ thuộc cốt lõi trong < 10 phút trên máy 16 GB; `make down` dọn sạch; có README | SA | 8 | ARC-09 | S0 | ADR-0011, NFR-I3 |
| LOC-02 | US-008, US-066 | Keycloak realm local | Realm export với role patient/doctor/nurse/admin/dpo, claim fhir_user, user giả | SA | 3 | LOC-01 | P1 | NFR-SEC1 |
| LOC-03 | ENABLER | Walking skeleton Java (Spring Boot) | Service mẫu: actuator health, info, OTel, Dockerfile distroless non-root; chạy qua pipeline template | SA | 4 | ARC-09 | P1 | Gate G4 |
| LOC-04 | ENABLER | Walking skeleton Python | FastAPI: health, info, OTel, Dockerfile non-root; chạy qua pipeline template | SA | 3 | ARC-09 | P1 | Gate G4 |
| LOC-08 | ENABLER | Profile full cho dev local: ClamAV, OpenSearch, Ollama, MLflow, Loki/Grafana/OTel; seed dữ liệu giả vào cụm | `make up PROFILE=full` dựng đủ hạ tầng cho mọi service; healthcheck; tài liệu tài nguyên cần thiết | SA | 3 | LOC-01 | P1 | ADR-0011, NFR-I3 |
| LOC-05 | ENABLER | Chạy service trên cụm local bằng chart microservice chung (make deploy SVC=...) | Service mẫu build, nạp vào kind và chạy bằng đúng chart dùng cho UAT/prod; có hướng dẫn debug | SA | 3 | GOP-01, LOC-03, LOC-01 | P3 | ADR-0011, NFR-I3 |
| LOC-07 | ENABLER | Hướng dẫn onboarding dev | Từ clone repo đến chạy local và mở PR đầu tiên trong < 1 giờ | SA | 2 | LOC-01, LOC-03 | P3 | PM |

## SCF — Scaffold service (SA)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| SCF-01 | ENABLER | Scaffold scheduling service (khung code, Dockerfile, pipeline svc-scheduling, Helm values, kết nối DB/bus/OTel) | Service từ template; svc-scheduling.yml; values local/uat/prod | SA | 2 | LOC-03, CIT-08 | S1 | ADR-0001 |
| SCF-02 | ENABLER | Scaffold notification worker (khung code, pipeline, values, kết nối DB/bus) | Service từ template; svc-notification.yml | SA | 2 | LOC-03, CIT-08 | P3 | ADR-0001 |
| SCF-03 | ENABLER | Scaffold clinical service (khung code, pipeline, values, kết nối MongoDB/bus) | Service từ template; svc-clinical.yml | SA | 2 | LOC-03, CIT-08 | S1 | ADR-0001 |
| SCF-04 | ENABLER | Scaffold document service (khung code, pipeline, values, Blob/MongoDB) | Service từ template; svc-document.yml | SA | 2 | LOC-03, CIT-08 | P3 | ADR-0001 |
| SCF-05 | ENABLER | Scaffold audit worker (khung code, pipeline, values, Blob WORM/Log Analytics) | Service từ template; svc-audit.yml | SA | 2 | LOC-03, CIT-08 | P3 | ADR-0001 |
| SCF-06 | ENABLER | Scaffold ai-service FastAPI (khung code, pipeline, values, node pool ai) | FastAPI từ template; svc-ai.yml; node pool ai | SA | 2 | LOC-04, CIT-08 | S1 | ADR-0001 |
| SCF-07 | ENABLER | Scaffold ml-service (khung code Python, Dockerfile, pipeline, values, kết nối MongoDB/bus/MLflow) | Service mẫu chạy qua pipeline template | SA | 2 | LOC-04, CIT-08 | P3 | ADR-0001 |

## GW — API Gateway (SA)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| GW-01 | ENABLER | API Gateway Spring Cloud Gateway | Route tới các service; truyền trace context | SA | 3 | LOC-03 | S2 | ADR-0001 |
| GW-02 | US-068 | Xác thực + rate limit theo người dùng | Token sai -> 401; vượt hạn mức -> 429 | SA | 4 | GW-01, SHR-20 | S4 | NFR-S3 |

## MLO — MLOps pipeline (SA)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| MLO-01 | US-063 | Pipeline huấn luyện ML trong CI + canary deploy model | Chạy khi dữ liệu/code đổi; artifact model; canary deploy | SA | 4 | ML-05 | S5 | ADR-0007 |
| MLO-02 | US-071 | Image huấn luyện tái lập được: pin phiên bản thư viện, seed, image cố định, quét lỗ hổng | Cùng image + dataset hash + seed cho cùng model; image được ký | SA | 3 | MLO-01 | S5 | 16-mlops §2 |
| MLO-03 | US-073 | Argo Rollouts canary cho ml-service + AnalysisTemplate (Prometheus), tự tăng tỉ lệ hoặc rollback | Bước 10%, 50%, 100% có phân tích; model xấu tự rollback; autoPromote cấu hình theo môi trường | SA | 6 | GOP-04, SCF-07, ML-25 | S5 | 16-mlops §6 |
| MLO-05 | US-065 | Cảnh báo drift và AUC thực tế (Alertmanager) + panel dashboard MLOps | Cảnh báo tới đúng kênh; dashboard có PSI, AUC thực tế, phiên bản model, lịch sử huấn luyện lại | SA | 3 | ML-10, OBS-01 | S5 | NFR-O3 |
| MLO-04 | US-074 | Điều phối huấn luyện lại trên cluster: CronJob/KEDA nhận yêu cầu từ ML-22, chạy ML-23, theo dõi và audit | Cảnh báo drift giả lập khởi động đúng một lần huấn luyện lại; không chạy chồng | SA | 5 | ML-23, GOP-10 | S5 | 16-mlops §5 |

## PRD — Production engineering & bảo vệ

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| PRD-10 | US-068 | Script k6: tìm slot + đặt lịch (kịch bản 1x/5x/10x) | Kịch bản 1x/5x/10x theo workload-analysis; chạy được trên UAT (cỡ prod) | SA | 4 | SCH-14 | S5 | NFR-P1, P2 |
| PRD-11 | US-068 | KEDA cron scale trước 07:00 + HPA cho scheduling | Values prod/uat scale trước đợt mở slot | SA | 1 | SCF-01, GOP-04 | S5 | NFR-S1 |
| PRD-01 | US-068 | Load test 1x/5x/10x + báo cáo | Chạy trên UAT ephemeral (cỡ prod); so với NFR-P/S | SA | 6 | PRD-10, ENV-01 | H1 | NFR-P1, P2, S2, S3 |
| PRD-02 | US-069 | Failure test | Kill pod, DB failover, provider SMS lỗi, LLM lỗi, consumer crash; ghi kết quả | SA | 6 | ENV-01 | H1 | NFR-A3, A6 |
| PRD-03 | US-067 | Diễn tập backup/restore | PITR Postgres + restore Mongo; ghi thời gian thực tế so với RTO | SA | 4 | ENV-01 | H1 | UC-16, NFR-A4 |
| PRD-04 | ENABLER | Dashboard SLO cuối cùng | Error budget theo NFR-A1 | SA | 3 | OBS-02 | H1 | NFR-O2 |
| PRD-05 | ENABLER | Báo cáo FinOps | Chi phí theo giờ-môi trường, dự phóng tháng, khuyến nghị | SA | 4 | ENV-03 | H2 | NFR-C1..C3 |
| PRD-06 | ENABLER | Rà soát bảo mật | Checklist theo threat model; kết quả quét; ma trận phân quyền xanh | SA | 4 | ARC-08 | H2 | P3 |
| PRD-08 | ENABLER | Kịch bản demo + slide bảo vệ | Trả lời 12 câu hỏi review của đề; demo end-to-end | SA | 6 | PRD-01..PRD-07 | DEF | Đề bài |
| PRD-09 | ENABLER | Diễn tập demo trên prod ephemeral | Dựng prod, chạy kịch bản, hủy; có timing | SA | 3 | PRD-08 | DEF | Đề bài |

## SHR — Thư viện dùng chung (Java)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| SHR-01 | ENABLER | FHIR R4 JSON serialize/parse (HAPI FHIR) + test vòng khứ hồi cho resource trong phạm vi | Parse sai trả 400 validation-failed; test từng resource type | D2 | 3 | LOC-03 | P1 | ADR-0003 |
| SHR-02 | ENABLER | Mô hình lỗi: exception → OperationOutcome / Problem Details + danh mục mã lỗi | Mọi mã ở 07 §5 có test; thông điệp vi/en, không lộ PII/stack trace | SA | 3 | SHR-01 | P1 | 07 §5 |
| SHR-03 | ENABLER | Search parameter registry + parser (token, reference, date theo độ chính xác, _count) | ge/gt/le/lt/eq, '2026-11' = cả tháng; tham số lạ → 400 | D2 | 4 | SHR-01 | P1 | ADR-0003, 07 §4 |
| SHR-04 | ENABLER | Keyset page token (mờ, ký) + dựng Bundle searchset | Token bị sửa → 400; link next/self đúng | D2 | 3 | SHR-03 | P2 | 07 §4 |
| SHR-05 | ENABLER | Filter ETag/If-Match (412/428) và Idempotency-Key bắt buộc (428) | Thiếu header → 428; ETag W/"version" trên mọi response | D2 | 2 | SHR-02 | P2 | 07 §3 |
| SHR-10 | ENABLER | Mongo resource store: create/update với version CAS + history + outbox trong một transaction | Update version cũ khớp 0; transaction rollback toàn phần khi lỗi | D2 | 4 | SHR-03, LOC-08 | P2 | 03 §2.5 |
| SHR-11 | ENABLER | Mongo search executor: biên dịch tiêu chí search thành truy vấn + dùng index | Kế hoạch truy vấn dùng IXSCAN cho các truy vấn chính | D2 | 3 | SHR-10 | S1 | 03 §2.6 |
| SHR-12 | US-035 | Idempotency-Key cho MongoDB (document service) | Cùng key → cùng kết quả; khác body 422 | D2 | 2 | SHR-05, SHR-10 | S1 | 07 §3 |
| SHR-20 | US-008, US-066 | JWT bearer cho Entra ID và Keycloak: map claim roles, kiểm amr=mfa với nhân viên | Test token hợp lệ/sai chữ ký/hết hạn/sai aud/nhân viên thiếu MFA; cấu hình qua biến môi trường | SA | 3 | LOC-02 | P1 | 07 §2, 15 §1 |
| SHR-21 | US-007 | Identity resolver: sub → fhirUser + roles + disabled, cache 60 giây | Tài khoản bị khóa bị từ chối ≤ 60 giây; cache hết hạn đúng; clinical lỗi thì dùng cache, không có cache thì 503 | SA | 3 | SHR-20 | P1 | 15 §8, C2 |
| SHR-22 | ENABLER | Authz: schema + loader + validator cho contracts/authz/matrix.yaml | Matrix sai schema (thiếu role, predicate lạ) làm build thất bại; unit test | SA | 3 | ARC-19 | P1 | 15 §3 |
| SHR-23 | ENABLER | Authz: các predicate own, self-practitioner, care, breakglass, released, before-2h, encounter-finished, not-care | Mỗi predicate có test đúng/sai; predicate care/breakglass gọi qua interface có thể giả lập | SA | 4 | SHR-22 | P1 | 15 §2 |
| SHR-06 | ENABLER | CloudEvents envelope + validate JSON Schema + helper test hợp đồng | Event sai schema bị từ chối; helper dùng cho mọi service | SA | 3 | ARC-07 | P2 | 13 §1 |
| SHR-07 | ENABLER | Outbox relay dùng chung (store PostgreSQL và MongoDB), poll 1 giây, SKIP LOCKED | Bus chết rồi sống lại vẫn phát đủ; nhiều replica không trùng | SA | 4 | SHR-06 | P2 | 13 §1 |
| SHR-08 | ENABLER | Consumer nền tảng: inbox idempotent + Spring Cloud Stream (RabbitMQ local, Service Bus cloud), retry/DLQ | Event trùng chỉ xử lý một lần; 5 lần giao rồi DLQ | SA | 4 | SHR-06 | P2 | 13 §1 |
| SHR-24 | ENABLER | Authz: decision engine default-deny, ràng buộc trường (fields), purposeOfUse | Không có quy tắc khớp → Deny; nhiều quy tắc khớp lấy Allow đầu tiên có điều kiện thỏa; test bảng | SA | 3 | SHR-23 | P2 | 15 §2 |
| SHR-25 | ENABLER | Authz: filter endpoint Spring + test 'mọi endpoint phải có quy tắc' | Thêm endpoint không có quy tắc làm test CI thất bại; 403/404 đúng quy ước | SA | 4 | SHR-24 | P2 | 15 §3 |
| SHR-26 | ENABLER | Authz: bộ sinh test từ matrix.yaml (role × điều kiện) | Từ matrix sinh test Allow/Deny cho mọi ô; dùng được trong test của từng service | D1 | 4 | SHR-24 | P2 | 15 §3, NFR-SEC2 |
| SHR-28 | US-015 | Idempotency-Key middleware cho PostgreSQL (claim, request hash, replay) | Cùng key+body → trả kết quả cũ; khác body → 422; thiếu key → 428; test song song | SA | 4 | SHR-05 | P2 | 02 §3.1, 07 §3 |
| SHR-09 | US-047 | Thư viện audit emitter: dựng audit.recorded (schema 05), che IP, chặn PII, middleware FHIR | Audit không chứa nội dung lâm sàng; search chỉ ghi tên tham số | SA | 4 | SHR-06 | S1 | E11(pub), 05 §1 |
| SHR-27 | US-047 | Authz: phát audit cho mọi quyết định (cho phép và từ chối) | Mỗi Deny sinh audit outcome=denied kèm mã lý do; test | SA | 2 | SHR-24, SHR-09 | S1 | 05 §1, E11(pub) |

## PY — Thư viện dùng chung (Python, cho ai/ml)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| PY-01 | US-066 | Thư viện Python: xác thực JWT (Entra + Keycloak), dependency role cho FastAPI | Test token hợp lệ/sai/hết hạn | D3 | 3 | LOC-02 | P1 | 07 §2 |
| PY-02 | US-053 | Client on-behalf-of (Entra OBO, Keycloak RFC 8693 token exchange) | Token trao đổi mang danh tính người dùng, aud đúng; test với IdP giả | D3 | 3 | PY-01 | P2 | 15 §7 |
| PY-05 | ENABLER | Problem Details + danh mục mã lỗi cho FastAPI | Khớp 07 §5; test | D3 | 2 | LOC-04 | P1 | 07 §5 |
| PY-06 | US-055 | Bộ che PII (tên, SĐT, email, địa chỉ, mã định danh → token giả có bản đồ ngược) | Bộ test dữ liệu giả: không còn PII trong prompt gửi ra ngoài Azure | D3 | 4 |  | P1 | NFR-SEC8 |
| PY-07 | US-055 | Giao diện LLM provider + adapter DeepSeek, Gemini, Ollama (OpenAI-compatible) + redact PII bắt buộc + provider giả cho test | Cùng một interface; mô phỏng timeout/429/5xx | D3 | 4 | LOC-04 | P2 | ADR-0006 |
| PY-08 | US-055 | Router: chuỗi model theo tác vụ, timeout, chuyển model kế, kết quả cuối cùng khi hết chuỗi | Ghi fallbackLevel; test mọi nhánh lỗi | D3 | 4 | PY-07 | P2 | 06 §4 |
| PY-09 | US-055 | Circuit breaker + hạn mức token/ngày + metric theo model | Mạch mở sau 5 lỗi/60s; vượt ngân sách → ai-budget-exceeded | D3 | 3 | PY-08 | S1 | 06 §4, NFR-C2 |
| PY-03 | US-059, US-064 | Consumer CloudEvents Python: validate schema, inbox (Mongo/Postgres), retry/DLQ | Event trùng xử lý một lần | D3 | 4 | ARC-07 | P2 | 13 §1 |
| PY-04 | US-047 | Audit emitter Python (E11) cho AI/ML | Không ghi nội dung câu hỏi hay PII; test | D3 | 2 | PY-03 | S1 | E11(pub), 12 §0 |

## DAT — Dữ liệu giả

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| DAT-01 | ENABLER | Bộ sinh dữ liệu giả tiếng Việt: bệnh nhân, bác sĩ, điều dưỡng (không dữ liệu thật) | Tên/SĐT/ngày sinh hợp lệ nhưng giả; seed cố định để tái lập | D3 | 4 | ARC-11 | P1 | NFR-SEC9 |
| DAT-02 | ENABLER | Bộ sinh lịch làm việc, lịch hẹn, lần khám, sinh hiệu, chẩn đoán, tài liệu mẫu | Dữ liệu nhất quán giữa các service (ID khớp) | D3 | 4 | DAT-01 | P2 | NFR-SEC9 |
| DAT-03 | ENABLER | CLI seed gọi API công khai: local và môi trường cloud | Một lệnh nạp xong dữ liệu mẫu; chạy lại không trùng | D3 | 3 | DAT-02, SCH-14, CLN-05 | S3 | NFR-SEC9 |

## SCH — Scheduling service

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| SCH-01 | US-009, US-011 | Migration V001: schedule, schedule_template, slot (+ extension, exclusion constraint) | Chạy sạch trên Postgres 16 bằng Testcontainers; có ràng buộc chống slot chồng lấn | D1 | 2 | SCF-01 | S1 | 02 §2 |
| SCH-02 | US-015 | Migration V002: appointment, resource_history, outbox, idempotency_key, inbox | Chạy sạch; index unique một lịch hiệu lực mỗi slot tồn tại | D1 | 2 | SCH-01 | S1 | 02 §2 |
| SCH-03 | US-009 | Schedule repository: tạo/đọc/tìm/sửa với version CAS và history | Sửa với version cũ thất bại; mỗi lần ghi có history; test integration | D1 | 4 | SCH-02, SHR-02 | S1 | 02, ADR-0003 |
| SCH-04 | US-009, US-013 | API Schedule (S1-S4) + kiểm tra Practitioner/chuyên khoa qua interface (bản giả) + quy tắc authz | 201/400/409/403 đúng; bác sĩ chỉ thấy lịch của mình; thêm quy tắc vào matrix | D1 | 4 | SCH-03, SHR-25 | S1 | S1, S2, S3, S4 |
| SCH-11 | US-012 | API block/unblock slot (S9, S10) | 409 slot-has-appointment khi slot busy; unblock sai trạng thái 409; If-Match | D1 | 3 | SCH-03, SHR-25 | S3 | S9, S10, AC-02.2 |
| SCH-05 | US-010 | Template repository + API S5, S6 với validate (chia hết, không chồng giờ) | Thay toàn bộ mẫu trong một transaction; các trường hợp 400 có test | D1 | 4 | SCH-04 | S2 | S5, S6 |
| SCH-06 | US-011 | Bộ sinh slot: múi giờ Asia/Ho_Chi_Minh, ON CONFLICT DO NOTHING | Mẫu 08:00-11:30 / 15 phút → đúng 14 slot free; chạy lại không trùng | D1 | 4 | SCH-05 | S2 | AC-02.1 |
| SCH-07 | US-011 | API slots:generate (S7) + giới hạn ngày | to ≤ hôm nay + 30, ≤ 31 ngày, from ≥ hôm nay; kết quả created/skipped | D1 | 2 | SCH-06 | S2 | S7 |
| SCH-09 | US-014 | Repository tìm slot với keyset pagination (specialty, practitioner, schedule, khoảng ngày) | Dùng ix_slot_free_by_specialty (EXPLAIN); phân trang ổn định | D1 | 4 | SCH-02, SHR-04 | S2 | 02 §4 |
| SCH-12 | US-015 | $book bước 1: claim Idempotency-Key, advisory lock theo bệnh nhân, kiểm BR-03 | Bệnh nhân có 3 lịch tương lai → 422; hai request cùng bệnh nhân tuần tự | D1 | 4 | SCH-02, SHR-28 | S3 | 02 §3.1, AC-03.4 |
| SCH-17 | US-013, US-018, US-019 | Đọc/tìm lịch hẹn (S12, S13): ép bộ lọc theo vai trò, tối đa 92 ngày | Patient chỉ thấy của mình; bác sĩ chỉ của mình; Nurse tự do; test mọi vai trò | D1 | 4 | SCH-03, SHR-25 | S3 | S12, S13 |
| SCH-21 | US-019 | API _history cho Appointment (S17) | Trả mọi phiên bản; chỉ Nurse/Admin | D1 | 2 | SCH-03 | S4 | S17 |
| SCH-33 | US-009 | Nối client thật tới clinical (roles của Practitioner) thay cho bản giả trong SCH-04 | Test hợp đồng với clinical; Practitioner không có role chuyên khoa → 400 | D1 | 2 | SCH-04, CLN-12 | S2 | 09 §4 |
| SCH-08 | US-011 | Job sinh slot hằng ngày 01:00 (một replica chạy nhờ advisory lock) | Hai replica chạy cùng lúc chỉ một thực hiện; test | D1 | 2 | SCH-06 | S2 | 02 §3.3 |
| SCH-10 | US-014 | API tìm slot (S8): ép status/start cho bệnh nhân, 400 khi thiếu bộ lọc hoặc > 31 ngày | Patient không xem được slot không free; test bộ lọc | D1 | 3 | SCH-09, SHR-25 | S2 | S8 |
| SCH-13 | US-015 | $book bước 2: compare-and-set slot, BR-04, tạo appointment, history, outbox appointment-booked | Slot busy/ngoài cửa sổ → 409/422; commit gồm history và outbox | D1 | 4 | SCH-12 | S3 | E1(pub), 02 §3.1 |
| SCH-14 | US-015 | API $book (S11): parse Parameters, kiểm patient=own, gợi ý 3 slot khi 409 | 201/200(replay)/400/403/404/409/422/428 đúng; Nurse ép channel=staff | D1 | 4 | SCH-13, SHR-25 | S3 | S11, AC-03.6 |
| SCH-15 | US-015 | Test idempotency end-to-end: cùng key+body, cùng key khác body | AC-03.2 và AC-03.3 xanh trong CI | D1 | 3 | SCH-14 | S3 | AC-03.2, AC-03.3 |
| SCH-16 | US-015 | Test đồng thời: 50 yêu cầu đặt một slot (Testcontainers) | Đúng 1 thành công, 49 nhận 409; không lịch hẹn trùng (AC-03.1) | D1 | 3 | SCH-14 | S3 | AC-03.1, NFR-P2 |
| SCH-18 | US-016, US-019 | API $cancel (S14): BR-02, trả slot về free, outbox appointment-cancelled | <2h Patient 422, Nurse được; không còn booked → 409; slot trả đúng | D1 | 4 | SCH-13 | S4 | S14, E2(pub), AC-03.5 |
| SCH-22 | US-022 | API nội bộ đọc lịch hẹn (S18) với service token | Chỉ clinical/ml gọi được; role khác 403; audit actor=system | D1 | 2 | SCH-17 | S4 | S18 |
| SCH-19 | US-017, US-019 | API $reschedule (S15) nguyên tử: đặt mới + hủy cũ trong một transaction | Lỗi giữa chừng giữ nguyên lịch cũ; phát appointment-rescheduled | D1 | 4 | SCH-18 | S4 | S15, E3(pub) |
| SCH-20 | US-021 | API $check-in (S16): cửa sổ -60/+30 phút, chỉ Nurse, outbox appointment-checked-in | Ngoài cửa sổ 422; không phải Nurse 403 | D1 | 3 | SCH-13, SHR-25 | S4 | S16, E4(pub) |
| SCH-23 | US-015 | Relay outbox: bổ sung ageBand từ clinical (cache 24 giờ, lỗi → unknown) | Clinical lỗi không chặn phát event; ageBand=unknown | D1 | 3 | SCH-13, SHR-07, CLN-12 | S4 | 13 §3 E1 |
| SCH-24 | US-020 | Job no-show mỗi 5 phút (SKIP LOCKED) + event status-changed | booked quá end+30' → noshow; hai replica không xử lý trùng | D1 | 3 | SCH-13 | S4 | E5(pub), 02 §3.3 |
| SCH-25 | US-022 | Consumer encounter-finished → Appointment fulfilled (inbox, bỏ qua event cũ) | Event trùng/đến muộn không đổi trạng thái; phát status-changed | D1 | 3 | SCH-13, SHR-08 | S4 | E6(sub), E5(pub) |
| SCH-27 | US-014 | Cache Redis cho tìm slot (TTL 5 giây) + invalidate theo event; Redis lỗi → đọc thẳng DB | Hit ratio đo được; Redis chết không làm lỗi API | D1 | 4 | SCH-10, SHR-08 | S5 | NFR-S2 |
| SCH-28 | US-015 | Job dọn dẹp: outbox đã phát >7 ngày, idempotency >24h, slot free quá khứ >90 ngày | Không xóa dữ liệu còn cần; test | D1 | 2 | SCH-13 | S5 | 02 §3.3 |
| SCH-29 | US-009, US-012, US-016, US-017 | Chạy bộ test ma trận phân quyền cho mọi endpoint scheduling | Mọi ô liên quan scheduling xanh; mọi Deny có audit | D1 | 4 | SCH-22, SHR-26 | S5 | NFR-SEC2 |
| SCH-26 | US-004 | Consumer patient-merged: thay patientId trong dữ liệu scheduling (idempotent) | Chạy lại không đổi kết quả; test | D1 | 2 | SCH-13, SHR-08 | S5 | E7(sub) |
| SCH-30 | US-015 | Test hợp đồng cho event E1-E5 (producer sinh đúng schema) | Schema JSON validate trong CI | D1 | 3 | SCH-25, SHR-06 | S5 | E1(pub), E2(pub), E3(pub), E4(pub), E5(pub) |
| SCH-31 | US-014 | EXPLAIN ANALYZE 4 truy vấn nóng trên dữ liệu cỡ năm 1 + chỉnh index nếu cần | Kết quả ghi vào docs; truy vấn tìm slot dùng index | D1 | 3 | SCH-27, DAT-03 | S5 | 02 §4, NFR-P1 |
| SCH-32 | US-068 | Tinh chỉnh hiệu năng theo kết quả load test (pool, index, truy vấn) | Đạt NFR-P1, P2 hoặc có giải thích | D1 | 4 | PRD-01 | H1 | NFR-P1, P2 |

## NTF — Notification service

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| NTF-01 | US-041 | Khởi tạo MongoDB notification (ADR-0014): preferences, device_tokens, reminders (lần gửi nhúng), inbox, outbox | Script idempotent; unique (appointmentId, kind); TTL dọn nhắc cũ; validator kênh | D1 | 2 | SCF-02 | S2 | 04 §1.2 |
| NTF-02 | US-041 | Consumer appointment-booked → tạo nhắc xác nhận, T-24h, T-2h (bỏ mốc đã qua) | Event trùng không tạo nhắc trùng; test các mốc thời gian | D1 | 3 | NTF-01, SHR-08 | S3 | E1(sub) |
| NTF-03 | US-042 | Consumer appointment-cancelled/rescheduled: hủy nhắc chưa gửi, tạo nhắc hủy | AC-07.1; reschedule chỉ một thông báo gộp | D1 | 3 | NTF-02 | S3 | E2(sub), E3(sub), AC-07.1 |
| NTF-04 | US-005, US-041 | Logic tùy chọn: thứ tự kênh, ngôn ngữ, khung giờ yên tĩnh (t-2h bỏ qua) | Test lùi giờ gửi qua khung yên tĩnh | D1 | 3 | NTF-01 | S3 | 04 §1.2 |
| NTF-05 | US-043 | Vòng lấy việc: findOneAndUpdate nguyên tử + lockedUntil, thu hồi nhắc sending bị treo | Worker chết giữa chừng → nhắc được gửi lại; hai worker không trùng (test Testcontainers MongoDB) | D1 | 4 | NTF-01 | S3 | 04 §1.3 |
| NTF-06 | US-041, US-043 | Abstraction kênh + provider giả + template vi/en (không nhúng PII/chẩn đoán) | Template có biến doctorName/startTime/place; test render hai ngôn ngữ | D1 | 4 | NTF-01 | S3 | 11 §1 |
| NTF-07 | US-043 | Bộ gửi: kênh dự phòng, backoff 1/5/15/60 phút, dead-letter lần 5, metric | Mô phỏng lỗi từng kênh; lần 5 → dead-lettered; attempt unique | D1 | 4 | NTF-05, NTF-06 | S4 | AC-07.2 |
| NTF-08 | US-043 | Client lấy thông tin liên hệ từ clinical (nội bộ) + audit; không lưu PII | Không có SĐT/email trong DB/log; test | D1 | 2 | NTF-06, CLN-12 | S4 | 09 §4 |
| NTF-12 | US-043 | Adapter thật: push (FCM), email, SMS qua interface kênh | Cấu hình bằng identity/secret store; có chế độ giả cho local | D1 | 4 | NTF-07 | S5 | ADR-0004 |
| NTF-09 | US-005 | API thiết bị và tùy chọn (N1-N4) | Chủ sở hữu lấy từ danh tính; 400 kênh sai; upsert theo token | D1 | 4 | NTF-04, SHR-25 | S4 | N1, N2, N3, N4 |
| NTF-10 | US-044 | API xem nhắc theo lịch hẹn và dead-letter, retry (N5-N7) | Không trả thông tin liên hệ; retry sau giờ khám 409 | D1 | 3 | NTF-07, SHR-25 | S4 | N5, N6, N7 |
| NTF-11 | US-045, US-046, US-004 | Consumer document-released, no-show-scored, patient-merged | t-6h-risk chỉ khi highRisk và còn thời gian; result-available nội dung chung chung | D1 | 3 | NTF-02 | S4 | E9(sub), E10(sub), E7(sub) |
| NTF-13 | US-041 | Metric độ trễ gửi để đo NFR-P8 | Histogram từ due_at đến sent; có trong dashboard | D1 | 2 | NTF-07 | S5 | NFR-P8 |
| NTF-15 | US-043, US-069 | Test lỗi: nhà cung cấp ngừng, worker bị kill giữa chừng | Không mất và không gửi trùng; ghi kết quả | D1 | 3 | NTF-12 | S5 | NFR-A6 |
| NTF-14 | US-044, US-005 | Test ma trận phân quyền + hợp đồng event cho notification | Xanh trong CI | D1 | 3 | NTF-10, SHR-26 | S5 | NFR-SEC2 |

## CLN — Clinical service

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| CLN-02 | US-001, US-006 | Validator profile cho Patient, Practitioner, PractitionerRole (cardinality, value set) | Trường ngoài profile bị từ chối; test từng ràng buộc | D2 | 4 | SHR-01, ARC-20 | S1 | 01 §4 |
| CLN-10 | US-006 | API Practitioner/PractitionerRole (C9, C10): danh bạ cho mọi người dùng, ghi chỉ Admin | Danh bạ không lộ liên hệ cá nhân; mã NV-NNNN | D2 | 4 | CLN-02, SHR-25 | S2 | C9, C10 |
| CLN-11 | US-007 | API /me (C2), quản lý liên kết/khóa tài khoản (C21), API nội bộ identity | Khóa có hiệu lực ≤ 60 giây; role không khớp PractitionerRole → 422 | D2 | 4 | CLN-10, SHR-21 | S2 | C2, C21, 15 §8 |
| CLN-12 | US-033, US-043 | API nội bộ: kiểm tra Care, roles của Practitioner, thông tin liên hệ, age-band | Chỉ service được phép gọi; contact có audit | D2 | 4 | CLN-11 | S2 | 09 §4 |
| CLN-13 | US-033 | Consumer appointment-booked/cancelled/rescheduled/status-changed → care_relationships | Care đến start+365 ngày; hủy hết nguồn thì mất Care; event trùng bỏ qua | D2 | 4 | CLN-12, SHR-08 | S3 | E1(sub), E2(sub), E3(sub), E5(sub), 15 §4 |
| CLN-14 | US-023 | Consumer appointment-checked-in → tạo Encounter (unique appointmentId) | Nhận hai lần chỉ một Encounter | D2 | 3 | CLN-13 | S3 | E4(sub) |
| CLN-16 | US-025 | Validator Observation: LOINC, đơn vị UCUM, khoảng hợp lý, tính interpretation | Nhiệt độ 50 °C → value-out-of-range; huyết áp có 2 component | D2 | 4 | ARC-20, CLN-02 | S3 | 01 §4.7 |
| CLN-01 | US-002 | Bootstrap MongoDB clinical: collections, $jsonSchema validator, mọi index theo thiết kế | Script idempotent; test validator từ chối document sai | D2 | 4 | SCF-03, SHR-10 | S1 | 03 §2.2, §2.4 |
| CLN-03 | US-001 | Patient repository: sinh MRN BN-YYYY-NNNNNN, nameTokens bỏ dấu, chuẩn hóa SĐT E.164 | MRN không trùng khi tạo song song; 'Nguyễn' → 'nguyen' | D2 | 4 | CLN-01, CLN-02 | S1 | 03 §2.3 |
| CLN-15 | US-024 | API Encounter: $start, đọc, tìm, sửa ghi chú (C11-C13), chặn sửa khi finished | Không phải bác sĩ của lịch hẹn 403; finished sửa 409 | D2 | 4 | CLN-14, SHR-25 | S3 | C11, C12, C13 |
| CLN-17 | US-025 | API Observation (C16): ghi/đọc/tìm, ràng buộc Encounter và quyền | Sinh hiệu không có Encounter → 400; Patient chỉ thấy của Encounter finished | D2 | 3 | CLN-16, CLN-15 | S3 | C16 |
| CLN-18 | US-026 | API Condition (C17) + bảng ICD-10 + quy tắc recorder phải có Care | Không Care (kể cả break-glass) → 403; mã sai 422; entered-in-error giữ bản ghi | D2 | 4 | CLN-15, ARC-20 | S3 | C17, AC-04.1 |
| CLN-20 | US-028 | API $finish (C14) + phát encounter-finished | Chưa có chẩn đoán → 422 diagnosis-required | D2 | 3 | CLN-18 | S4 | C14, E6(pub) |
| CLN-21 | US-029 | API $addendum (C15) | Chỉ khi finished; bản gốc không đổi | D2 | 2 | CLN-20 | S4 | C15, AC-04.2 |
| CLN-25 | US-029 | Dựng dữ liệu ICD-10 và LOINC vào bảng tra cứu khi khởi động | Nạp idempotent; tra mã < 1 ms | D2 | 2 | CLN-18 | S4 | ARC-20 |
| CLN-27 | US-023, US-033 | Test hợp đồng event E1-E7 cho clinical (consumer đọc được ví dụ, producer sinh đúng schema) | Xanh trong CI | D2 | 3 | CLN-21, SHR-06 | S5 | E6(pub), E7(pub) |
| CLN-04 | US-001, US-003 | Phát hiện trùng hồ sơ theo SĐT + ngày sinh | Trùng → duplicate-patient-suspected, không lộ dữ liệu | D2 | 2 | CLN-03 | S1 | AC-01.2 |
| CLN-05 | US-001 | API đăng ký bệnh nhân (C1) + tạo identity_links | 201/400/409/422 đúng; MRN trả về; có audit | D2 | 4 | CLN-04, SHR-25, SHR-21 | S1 | C1 |
| CLN-19 | US-027 | API AllergyIntolerance (C18) | Bác sĩ Care và Nurse ghi; bệnh nhân đọc của mình | D2 | 2 | CLN-15 | S3 | C18 |
| CLN-22 | US-030 | Quy tắc bệnh nhân đọc dữ liệu lâm sàng của mình (chỉ Encounter finished) | Encounter chưa finished 404; dữ liệu người khác 404 | D2 | 3 | CLN-17, CLN-18 | S4 | 15 §6 |
| CLN-24 | US-034 | API break-glass (C20): phiên 60 phút TTL, lý do 20-500 ký tự, audit ETREAT, chỉ đọc | Ghi chẩn đoán trong phiên 403; phiên hết hạn tự mất | D2 | 4 | CLN-13, SHR-09 | S4 | C20, AC-10.1 |
| CLN-06 | US-002 | API đọc/sửa/lịch sử Patient (C4, C5, C7) với quy tắc trường cho bệnh nhân | Patient sửa name → 403; mỗi sửa có version; hồ sơ người khác 404 | D2 | 4 | CLN-05 | S2 | C4, C5, C7, AC-01.1, AC-01.3 |
| CLN-07 | US-003 | API Nurse tạo hồ sơ (C3) + forceCreate có lý do và audit | Không forceCreate khi nghi trùng → 422 | D2 | 3 | CLN-05 | S2 | C3 |
| CLN-08 | US-031 | API tìm bệnh nhân POST _search (C6): tên không dấu, SĐT, MRN, ngày sinh, rút gọn kết quả | 'nguyen va' ra đúng người; bác sĩ không Care chỉ thấy năm sinh; không tham số → 400 | D2 | 4 | CLN-05, SHR-11 | S2 | C6, AC-06.1, AC-06.2 |
| CLN-26 | US-002, US-024, US-026, US-031, US-034 | Chạy bộ test ma trận phân quyền cho mọi endpoint clinical | Mọi ô clinical xanh; Admin 403 mọi dữ liệu lâm sàng | D2 | 4 | CLN-24, SHR-26 | S5 | NFR-SEC2 |
| CLN-09 | US-004 | API gộp hồ sơ (C8) + phát patient-merged | Hồ sơ nguồn active=false, link replaced-by; gộp lại 409 | D2 | 4 | CLN-06 | S2 | C8, E7(pub) |
| CLN-23 | US-032 | API timeline (C19): 4 truy vấn song song + document client có timeout 300 ms, partial | Document lỗi vẫn trả phần còn lại với partial; keyset | D2 | 4 | CLN-17, CLN-18, DOC-10 | S4 | C19 |
| CLN-28 | US-032 | Tối ưu timeline: đo p95, index, projection để đạt ≤ 500 ms | Báo cáo p95 trên dữ liệu cỡ năm 1 (NFR-P3) | D2 | 4 | CLN-23, DAT-03 | S5 | NFR-P3 |

## DOC — Document service

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| DOC-01 | US-035 | Bootstrap MongoDB document: collections, validator, index, TTL upload_sessions | Script idempotent | D2 | 2 | SCF-04, SHR-10 | S3 | 03 §3 |
| DOC-02 | US-035, US-037 | IBlobStore trên Azure Blob SDK (một code cho local Azurite và cloud); chọn cách ký SAS qua cấu hình (user delegation trên cloud, account key trên Azurite); SAS ghi 10 phút/đọc 5 phút | Test với Azurite (Testcontainers); SAS đúng quyền, đúng blob, đúng thời hạn | D2 | 4 | DOC-01 | S3 | 05 §2 |
| DOC-03 | US-035 | API xin upload (D1): kiểm loại, kích thước, Care, Idempotency-Key | 415/413/403/400 đúng; cùng key cùng documentId | D2 | 4 | DOC-02, SHR-12, SHR-25 | S3 | D1, AC-05.1 |
| DOC-04 | US-035 | API complete (D2): kiểm tồn tại, size, SHA-256, magic bytes | Không khớp → 422 upload-incomplete; 202 scanning | D2 | 4 | DOC-03 | S4 | D2 |
| DOC-05 | US-036 | Xử lý kết quả quét: sạch → available + phát document-uploaded; nhiễm → quarantine | File nhiễm không phát event; quét lỗi retry 3 lần | D2 | 4 | DOC-04, IAC-18 | S4 | E8(pub) |
| DOC-06 | US-037, US-039 | API đọc/tìm metadata (D3, D4) với quy tắc bệnh nhân (đã phát hành) | Patient chưa phát hành → 404; không trả URL | D2 | 3 | DOC-04, SHR-25 | S4 | D3, D4, AC-05.3 |
| DOC-07 | US-037 | API cấp link tải 5 phút (D5) + audit mỗi lần | Sau 5 phút Blob 403; chưa available 409; Deny cũng audit | D2 | 3 | DOC-06, SHR-09 | S4 | D5, AC-05.2 |
| DOC-08 | US-038 | API phát hành (D6) + phát document-released | Phát hành lại 409; chỉ bác sĩ Care | D2 | 2 | DOC-06 | S4 | D6, E9(pub) |
| DOC-10 | US-032, US-059, US-004 | API nội bộ (D8, D9) + consumer patient-merged | D9 SAS đọc 60 giây chỉ cho service ai; merge idempotent | D2 | 3 | DOC-06, SHR-08 | S4 | D8, D9, E7(sub) |
| DOC-11 | US-035 | Job dọn blob mồ côi và pending-upload > 24 giờ | Không xóa file đã available | D2 | 2 | DOC-04 | S5 | 10 §5 |
| DOC-09 | US-040 | API entered-in-error (D7) | Ẩn khỏi timeline/bệnh nhân, giữ bản ghi | D2 | 2 | DOC-06 | S5 | D7 |
| DOC-12 | US-035, US-037, US-038 | Test ma trận phân quyền và hợp đồng event cho document | Xanh trong CI | D2 | 4 | DOC-09, SHR-26 | S5 | NFR-SEC2 |

## AUD — Audit service

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| AUD-01 | US-047 | Consumer audit.recorded: validate schema, inbox theo eventId, gom batch 60s/5.000 bản ghi | Event trùng không ghi trùng; bus lỗi không mất | D2 | 4 | SCF-05, SHR-08 | S2 | E11(sub) |
| AUD-03 | US-049 | Đẩy sang Log Analytics (Logs Ingestion) / Loki local | Xuất hiện trong ≤ 1 phút; không mất khi sink lỗi (retry) | D2 | 4 | AUD-01 | S3 | 05 §1.3, NFR-SEC5 |
| AUD-04 | US-049 | API tra cứu audit (A1): bộ lọc, khoảng ≤ 90 ngày, ẩn reason với Admin, audit việc tra cứu | Thiếu from/to → 400; DPO thấy đủ trường | D2 | 4 | AUD-03, SHR-25 | S4 | A1 |
| AUD-02 | US-048 | Ghi append blob NDJSON vào container WORM kèm chuỗi hash (prevHash, batchHash) | Batch sau chứa hash batch trước; metadata batchHash đúng | D2 | 4 | AUD-01, IAC-12 | S3 | 05 §1.2 |
| AUD-05 | US-050 | API export audit (A2, A3): job nền xuất CSV, link SAS 5 phút | Khoảng ≤ 366 ngày; tối đa 3 export đồng thời | D2 | 4 | AUD-04 | S4 | A2, A3 |
| AUD-06 | US-050 | Công cụ + API kiểm tra toàn vẹn chuỗi hash (A4) | Phát hiện batch bị thay đổi/thiếu và báo vị trí | D2 | 4 | AUD-02 | S4 | A4 |
| AUD-07 | US-051 | Break-glass review (A5) + phát cảnh báo DPO ≤ 5 phút qua notification | Trạng thái new → acknowledged; cảnh báo gửi email + push | D2 | 4 | AUD-03, CLN-24 | S4 | A5, AC-10.1 |
| AUD-08 | US-048 | Test bất biến: sửa/xóa blob audit bị từ chối, chuỗi hash đứt bị phát hiện | Chuỗi hash đứt bị phát hiện: xanh trong CI (Azurite); sửa/xóa blob bị từ chối (WORM): smoke test trên UAT | D2 | 3 | AUD-06 | S5 | AC-09.2 |

## AI — AI service

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| AI-13 | US-055 | Theo dõi token/chi phí/fallback + API usage (AI10) + cảnh báo 80% ngân sách | Báo cáo theo tác vụ/model/ngày | D3 | 3 | PY-09 | S3 | AI10, NFR-C2 |
| AI-29 | US-062 | Bot đặt lịch: 4 tool, schema tham số, giới hạn 6 vòng và timeout 10 giây | Tool lạ bị từ chối; tham số sai bị chặn | D3 | 4 | PY-08, PY-02 | S6 | AC-14.2 |
| AI-01 | US-052 | Bootstrap MongoDB ai + script tạo index AI Search kb-chunks-v1 (và OpenSearch local) | Script idempotent; cùng schema cho hai backend | D3 | 3 | SCF-06, LOC-08 | S1 | 06 §1.2 |
| AI-02 | US-052 | Parser tài liệu: PDF, DOCX, Markdown → văn bản có cấu trúc (mục, trang) | Giữ tiêu đề mục và số trang; test với tài liệu mẫu | D3 | 3 | AI-01 | S1 | 06 §1.2 |
| AI-03 | US-052 | Chunker: 600 token, overlap 80, tách theo tiêu đề mục | Chunk không cắt giữa mục; test độ dài | D3 | 4 | AI-02 | S1 | 06 §1.2 |
| AI-04 | US-052 | Embedding bge-m3 local (Ollama, 1024 chiều) + upsert/xóa index; thay version không có khoảng trống | Chunk mới index xong mới xóa chunk cũ; test | D3 | 4 | AI-03, PY-07 | S1 | 06 §1.2 |
| AI-05 | US-052 | API kho tri thức (AI9): tải lên, duyệt, thay file, gỡ; quyền Admin/Doctor khoa | draft → approved → indexed → retired; lỗi index retry 3 lần | D3 | 4 | AI-04, PY-01 | S2 | AI9 |
| AI-06 | US-053 | Truy xuất hybrid (RRF, rerank cross-encoder tùy chọn theo eval) + security trimming theo role/khoa; xuống cấp khi embedding/reranker lỗi | Người không được phép không bao giờ thấy chunk; test lọc | D3 | 4 | AI-04 | S2 | 06 §1.2 |
| AI-07 | US-053 | Prompt template có version + lắp ngữ cảnh trong giới hạn token | ID phiên bản prompt ghi vào message | D3 | 3 | AI-06 | S2 | 06 §4 |
| AI-08 | US-053 | Sinh câu trả lời + kiểm tra trích dẫn khớp chunk + quy tắc answered=false | Không có nguồn → không gọi LLM sinh nội dung | D3 | 4 | AI-07, PY-08 | S2 | AC-11.1 |
| AI-14 | US-057 | Bộ eval RAG phần 1: 50 câu hỏi + đoạn đáp án chuẩn từ kho tri thức mẫu | File JSON có kiểm tra schema | D3 | 3 | AI-05 | S3 | NFR-AI1 |
| AI-09 | US-053, US-055 | API hỏi trợ lý (AI1) + SSE stream, event reset khi chuyển model | Mode retrieval-only khi mọi model lỗi; p95 token đầu ≤ 3s | D3 | 4 | AI-08, PY-09 | S3 | AI1, AC-11.3 |
| AI-10 | US-054 | Guardrail đầu vào: độ dài, mẫu prompt injection, chặn có audit | Mẫu tấn công bị chặn; không chặn nhầm câu hỏi y khoa thường | D3 | 3 | AI-09 | S3 | AC-11.2 |
| AI-11 | US-054 | Guardrail đầu ra: schema, không có chẩn đoán/chỉ định tự đưa ra, quote khớp chunk | Vi phạm → thử model kế rồi answered=false | D3 | 3 | AI-09 | S3 | AC-11.2 |
| AI-15 | US-057 | Bộ eval RAG phần 2: thêm 50 câu (có câu không có đáp án trong tài liệu) | Tổng ≥ 100 câu, ≥ 15 câu không có đáp án | D3 | 3 | AI-14 | S4 | NFR-AI2 |
| AI-16 | US-057 | Harness eval: Recall@5, MRR, groundedness, độ đúng trích dẫn, tỉ lệ từ chối đúng | Chạy một lệnh, xuất báo cáo Markdown + JSON | D3 | 4 | AI-15 | S4 | NFR-AI1, AI2 |
| AI-17 | US-054 | Bộ tấn công prompt injection 50 mẫu (trong câu hỏi và trong tài liệu) + runner | 0 lần vượt rào là điều kiện qua CI | D3 | 4 | AI-11 | S4 | NFR-AI3 |
| AI-12 | US-056 | Lưu hội thoại/tin nhắn (TTL 30 ngày) + API feedback và lịch sử (AI2, AI3) | Chỉ chủ hội thoại; đánh giá lặp 409 | D3 | 3 | AI-09 | S3 | AI2, AI3 |
| AI-18 | US-057, US-055 | Chạy eval cho từng model trong chuỗi fallback và lập báo cáo so sánh | Báo cáo theo model (NFR-AI7) | D3 | 3 | AI-16, IAC-19 | S5 | NFR-AI7 |
| AI-19 | US-058 | Thu thập dữ liệu cho tóm tắt bằng OBO: timeline, bệnh nền, dị ứng, kết quả bất thường; đánh số nguồn | Không có quyền → 403 trước khi dữ liệu vào prompt | D3 | 4 | PY-02, CLN-23 | S4 | AC-12.1 |
| AI-20 | US-058 | Prompt + schema đầu ra + bộ kiểm tra nguồn (loại ý không có nguồn hợp lệ) | Mọi ý có sources trỏ bản ghi thật | D3 | 4 | AI-19, PY-08 | S4 | AC-12.2 |
| AI-21 | US-058 | API tóm tắt (AI4): Idempotency-Key, cache 10 phút, nhãn AI, fallback structured-timeline | Mọi model lỗi vẫn trả timeline; có nhãn 'Do AI tạo' (AC-12.3) | D3 | 4 | AI-20 | S5 | AI4, AC-12.3 |
| AI-30 | US-062 | pending_action + API xác nhận (AI8): gọi $book/$cancel bằng token bệnh nhân, hết hạn 5 phút | Chưa xác nhận không có lịch hẹn (AC-14.1); 409 → đề xuất lại | D3 | 4 | AI-29, SCH-14 | S6 | AI8, AC-14.1 |
| AI-22 | US-059 | Mô hình job trích xuất + consumer document-uploaded + state machine + retry/DLQ | unique (documentId, version); retry 1/5/15 phút → failed | D3 | 4 | PY-03, DOC-05 | S5 | E8(sub), AC-13.2 |
| AI-23 | US-059 | Bước OCR bằng Tesseract (local); văn bản lưu Blob | Fallback khi dịch vụ lỗi; test với tài liệu mẫu | D3 | 4 | AI-22, DOC-10 | S5 | D9 |
| AI-24 | US-059 | Phân loại + trích trường bằng LLM theo schema + validator giá trị/đơn vị + độ tin cậy | overallConfidence = min các trường bắt buộc; ngưỡng 0,85 | D3 | 4 | AI-23, PY-08 | S5 | 06 §3 |
| AI-25 | US-059, US-060 | API danh sách và chi tiết trích xuất (AI5) kèm vị trí trường để tô trên tài liệu | Lọc theo status/patient; quyền Care/Nurse | D3 | 3 | AI-24 | S6 | AI5 |
| AI-26 | US-060 | API duyệt (AI6): tạo Observation bằng OBO sau khi người xác nhận; ghi editedFields | Chưa xác nhận không có dữ liệu vào hồ sơ; lỗi clinical trả nguyên văn | D3 | 4 | AI-25, PY-02, CLN-17 | S6 | AI6, AC-13.1 |
| AI-31 | US-062 | API chat đặt lịch (AI7) + fallback sang form khi mọi model lỗi | 503 ai-unavailable kèm fallback (AC-14.3) | D3 | 3 | AI-30 | S6 | AI7, AC-14.3 |
| AI-32 | US-052, US-053, US-058, US-060, US-062 | Test ma trận phân quyền và hợp đồng event cho ai-service | Xanh trong CI; bác sĩ không Care không lấy được dữ liệu qua AI | D3 | 4 | AI-31, AI-26 | S6 | NFR-SEC2 |
| AI-27 | US-061 | Bộ tài liệu giả có nhãn cho trích xuất (xét nghiệm, đơn thuốc, chuyển viện) | ≥ 60 tài liệu có nhãn trường | D3 | 4 | AI-24 | S6 | NFR-AI4 |
| AI-28 | US-061 | Eval F1 mức trường và phân loại + báo cáo | F1 ≥ 0,85 hoặc giải thích | D3 | 3 | AI-27 | H1 | NFR-AI4 |
| AI-33 | US-055 | Báo cáo token và chi phí theo tác vụ/model | Số liệu từ AI10; so với NFR-C2 | D3 | 3 | AI-13, PRD-01 | H2 | NFR-C2 |

## ML — ML no-show

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| ML-01 | US-063 | Sinh dữ liệu lịch hẹn có quy luật no-show (lead time, lịch sử vắng, giờ khám, kênh) | Tài liệu mô tả quy luật; tỉ lệ no-show ~18% tổng | D3 | 4 | ARC-11 | P1 | ADR-0007 |
| ML-02 | US-063 | Feature engineering + chia train/validation theo thời gian | Không rò rỉ tương lai; test đặc trưng | D3 | 4 | ML-01 | P2 | 04 §2.3 |
| ML-03 | US-063 | Baseline Logistic Regression + metric (AUC, precision/recall tại ngưỡng) | Ghi vào MLflow | D3 | 3 | ML-02 | P2 | ADR-0007 |
| ML-04 | US-063 | LightGBM + tìm tham số + MLflow tracking | So sánh với baseline; lưu artifact | D3 | 4 | ML-03 | P2 | ADR-0007 |
| ML-05 | US-063 | Cổng chất lượng và model registry: AUC ≥ 0,75 và hơn baseline mới được đăng ký alias production | Model kém bị chặn; có test cho cổng | D3 | 3 | ML-04, LOC-08 | S1 | NFR-AI6, M2 |
| ML-13 | US-071 | Dataset Parquet có phiên bản: ghi hash dataset vào run MLflow, dựng lại model từ hash | Cùng hash + seed → cùng model (kiểm bằng test) | D3 | 3 | ML-01 | P2 | 16-mlops §2 |
| ML-15 | US-071 | Package đặc trưng dùng chung cho huấn luyện và phục vụ | Một nguồn duy nhất cho mọi đặc trưng; có unit test | D3 | 3 | ML-02 | P2 | 04 §2.3 |
| ML-17 | US-072 | Đánh giá theo nhóm con (chuyên khoa, nhóm tuổi, kênh) + calibration + độ trễ dự đoán | Cổng chặn nếu nhóm thấp hơn AUC tổng > 0,07 hoặc p95 > 100 ms | D3 | 4 | ML-04 | P2 | 16-mlops §3 |
| ML-21 | US-065 | Bộ dữ liệu giả thứ hai có phân phối dịch chuyển (lead time dài hơn, tỉ lệ vắng tăng) | Có tài liệu mô tả mức dịch chuyển; dùng cho test detector và demo | D3 | 3 | ML-01 | P2 | 16-mlops §1 |
| ML-11 | US-064 | Khởi tạo MongoDB ml (ADR-0014): patient_stats, appointment_facts, predictions (time-series nếu vCore hỗ trợ), inbox, outbox | Script idempotent; validator ràng buộc ageBand, score; index (modelVersion, predictedAt) | D3 | 2 | SCF-07 | S2 | 04 §2.2 |
| ML-14 | US-071 | Kiểm tra dữ liệu trước khi train (schema, khoảng, tỉ lệ thiếu, rò rỉ thời gian) bằng pandera | Dữ liệu hỏng làm pipeline dừng với lý do rõ; test các ca hỏng | D3 | 3 | ML-13 | P2 | 16-mlops §2 |
| ML-18 | US-072 | Sinh model card tự động khi đăng ký model | Model card lưu cùng artifact trong MLflow; có đủ mục theo 16-mlops §3 | D3 | 3 | ML-17, ML-05 | S1 | 16-mlops §3 |
| ML-06 | US-064 | Consumer E1/E2/E3/E5/E7: ghi appointment_fact, cập nhật outcome và attendance_stats | Event trùng bỏ qua; hủy muộn <24h tính late_cancel | D3 | 4 | SCF-07, PY-03, ML-11 | S2 | E1(sub), E2(sub), E3(sub), E5(sub), E7(sub) |
| ML-07 | US-064, US-046 | Chấm điểm khi nhận E1: dựng đặc trưng, dự đoán, lưu prediction, phát no-show-scored qua outbox | AC-15.2 lưu model version và đặc trưng; cùng transaction | D3 | 4 | ML-06, ML-05 | S2 | E10(pub), AC-15.2 |
| ML-08 | US-063, US-064 | API M1 (xem dự đoán), M2 (model hiện hành), M3 (predict nội bộ) | p95 ≤ 100 ms cho M3; quyền đúng | D3 | 3 | ML-07 | S2 | M1, M2, M3 |
| ML-09 | US-063 | Nạp lại model khi đổi alias production trong ≤ 60 giây không cần deploy | Test đổi alias | D3 | 2 | ML-08 | S3 | ADR-0007 |
| ML-12 | US-064, US-046 | Test hợp đồng: consume E1/E5 và phát E10; ML lỗi không ảnh hưởng nhắc lịch | Xanh trong CI; AC-15.1 có test ở notification | D3 | 2 | ML-08, SHR-06 | S3 | E10(pub), AC-15.1 |
| ML-16 | US-071 | Test chống lệch train/serve: cùng mẫu cho đầu ra khớp giữa hai đường | Test xanh trong CI; lệch thì thất bại | D3 | 3 | ML-15, ML-07 | S2 | 16-mlops §2 |
| ML-24 | US-073 | Chế độ shadow trong serving: model candidate chấm điểm song song, chỉ ghi nhận, không dùng kết quả | Không tăng độ trễ phía người dùng; có metric đồng thuận với bản đang chạy | D3 | 3 | ML-09 | S3 | 16-mlops §6 |
| ML-10 | US-065 | Job drift hằng ngày chạy detector (ML-19, ML-20), xuất metric và cảnh báo | Metric PSI/AUC thực tế có trong Prometheus; cảnh báo khi vượt ngưỡng | D3 | 3 | ML-19, ML-20 | S6 | NFR-AI6 |
| ML-19 | US-065 | Detector drift: PSI từng đặc trưng và điểm dự đoán so với dữ liệu huấn luyện | Ngưỡng PSI > 0,2 ở ≥ 2 đặc trưng chính; test với bộ dữ liệu dịch chuyển | D3 | 4 | ML-07, ML-21 | S2 | 16-mlops §4 |
| ML-20 | US-065 | Giám sát hiệu năng thực tế: ghép dự đoán với kết quả thật, AUC cuộn 14 ngày | Cảnh báo khi AUC giảm > 0,05 và có ≥ 500 mẫu nhãn | D3 | 4 | ML-06, ML-07 | S2 | 16-mlops §4 |
| ML-25 | US-073 | Xuất metric cho phân tích canary: tỉ lệ lỗi, p95, phân phối điểm, đồng thuận, gắn nhãn phiên bản | Prometheus có đủ metric theo model_version để AnalysisTemplate dùng | D3 | 3 | ML-08 | S3 | 16-mlops §6 |
| ML-22 | US-074 | Logic kích hoạt huấn luyện lại: điều kiện, cooldown 7 ngày, ≥ 500 mẫu nhãn mới, chống chạy chồng | Test các ca: drift, AUC giảm, trong cooldown, thiếu mẫu | D3 | 3 | ML-19, ML-20 | S3 | 16-mlops §5 |
| ML-23 | US-074 | Job huấn luyện lại: train → kiểm tra dữ liệu → cổng chất lượng → đăng ký alias candidate, không bao giờ tự đổi production | Không đạt cổng thì dừng và giữ nguyên production; mọi run có liên kết và audit | D3 | 4 | ML-22, ML-05, ML-14, ML-17 | S3 | 16-mlops §5 |

## WEB — Web app (PWA)

| ID | Story | Task | Xong khi | Người | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|---|
| WEB-01 | US-066 | Web shell React/Vite: OIDC (Entra/Keycloak), router theo vai trò, khung giao diện, i18n vi/en | Đăng nhập, menu theo role, ErrorBoundary; build qua pipeline | D1 | 4 | LOC-02, SHR-20 | P2 | NFR-I2 |
| WEB-02 | US-066 | Client API: gắn token, Idempotency-Key, If-Match/ETag, ánh xạ OperationOutcome/Problem Details sang thông điệp | Hiển thị lỗi theo Accept-Language; tự thử lại đăng nhập khi hết phiên | D1 | 3 | WEB-01 | S1 | 07 §3, §5 |
| WEB-10 | US-066 | Pipeline + chart cho web | Container nginx non-root dùng chart chung | SA | 2 | WEB-01 | S3 | ADR-0009 |
| WEB-30 | US-053, US-056 | Giao diện trợ lý: hỏi, stream, hiển thị trích dẫn bấm được, chấm điểm | Hiển thị chế độ retrieval-only; trích dẫn mở đúng mục | D3 | 4 | WEB-02, AI-09 | S4 | AI1, AI2 |
| WEB-06 | US-009, US-010 | Màn hình quản lý lịch bác sĩ: Schedule và mẫu tuần | Nurse/Admin tạo/sửa; lỗi validate hiển thị theo trường | D1 | 4 | WEB-02, SCH-05 | S4 | S1, S5 |
| WEB-20 | US-031 | Màn hình tìm bệnh nhân (POST _search): tên không dấu, SĐT, MRN | Kết quả rút gọn; Doctor không Care thấy gợi ý break-glass | D2 | 4 | WEB-02, CLN-08 | S5 | C6 |
| WEB-24 | US-034 | Luồng break-glass: hộp thoại lý do, banner đếm ngược 60 phút | Banner hiển thị suốt phiên | D2 | 2 | WEB-20, CLN-24 | S6 | C20 |
| WEB-27 | US-001, US-002, US-003 | Màn hình hồ sơ: đăng ký, xem/sửa hồ sơ của tôi, đăng ký tại quầy | Xử lý 422 trùng hồ sơ; field khóa với bệnh nhân | D2 | 4 | WEB-02, CLN-06 | S2 | C1, C4, C5 |
| WEB-31 | US-053, US-052 | Giao diện quản lý kho tri thức và xem chi phí AI (Admin) | Tải lên, duyệt, gỡ; bảng usage | D3 | 4 | WEB-30, AI-13 | S5 | AI9, AI10 |
| WEB-03 | US-014 | Màn hình tìm slot trống: bộ lọc chuyên khoa/bác sĩ/ngày, danh sách phân trang | Chỉ gọi API S8; hiển thị theo múi giờ VN | D1 | 4 | WEB-02, SCH-10 | S3 | S8 |
| WEB-26 | US-049, US-050, US-051 | Màn hình DPO: tra cứu audit, xuất CSV, xem xét break-glass | Chỉ DPO/Admin; Admin không thấy reason | D2 | 4 | WEB-02, AUD-07 | S6 | A1, A2, A5 |
| WEB-04 | US-015 | Màn hình đặt lịch: xác nhận, xử lý 409 (gợi ý slot), 422 BR-03/BR-04, chống bấm đúp | Mỗi lần bấm dùng một Idempotency-Key; gợi ý hiển thị khi 409 | D1 | 4 | WEB-03, SCH-14 | S3 | S11 |
| WEB-05 | US-016, US-017, US-018 | Màn hình lịch hẹn của tôi: xem, hủy (cảnh báo <2h), đổi lịch | Thông điệp rõ khi 422 cancellation-too-late | D1 | 4 | WEB-04, SCH-19 | S4 | S12, S13, S14, S15 |
| WEB-07 | US-011, US-012, US-013 | Lịch slot dạng tuần: sinh slot, chặn/bỏ chặn; bác sĩ xem lịch của mình | Không cho chặn slot đã đặt (hiển thị 409) | D1 | 4 | WEB-06, SCH-11 | S5 | S7, S9, S10 |
| WEB-08 | US-019, US-021 | Màn hình quầy: đặt hộ, hủy/đổi hộ, check-in | Chỉ Nurse; check-in ngoài cửa sổ hiển thị lỗi rõ | D1 | 4 | WEB-05, SCH-20 | S5 | S16 |
| WEB-21 | US-032 | Màn hình timeline bệnh nhân: lọc loại/khoảng, phân trang, hiển thị partial | Hiển thị cảnh báo khi thiếu phần tài liệu | D2 | 4 | WEB-20, CLN-23 | S5 | C19 |
| WEB-22 | US-024, US-025 | Form lần khám: bắt đầu, lý do, bệnh sử, sinh hiệu (Nurse) | Báo lỗi theo trường (value-out-of-range) | D2 | 4 | WEB-21, CLN-17 | S6 | C11, C13, C16 |
| WEB-23 | US-026, US-027, US-028, US-029 | Form chẩn đoán/dị ứng, kết thúc lần khám, bổ sung addendum | Chặn kết thúc khi thiếu chẩn đoán | D2 | 4 | WEB-22, CLN-21 | S6 | C14, C15, C17, C18 |
| WEB-25 | US-035, US-037, US-038, US-039, US-040 | Màn hình tài liệu: upload trực tiếp bằng SAS, xem bằng link 5 phút, phát hành | Tiến độ upload, trạng thái scanning, xử lý hết hạn link | D2 | 4 | WEB-21, DOC-07 | S6 | D1, D2, D5, D6 |
| WEB-32 | US-058 | Panel tóm tắt hồ sơ: nhãn AI, nguồn bấm được sang bản ghi | Hiển thị mode structured-timeline khi LLM lỗi | D3 | 3 | WEB-21, AI-21 | S5 | AI4 |
| WEB-33 | US-059, US-060 | Màn hình duyệt trích xuất: tô trường trên tài liệu gốc, sửa, chấp nhận/từ chối | Trường độ tin cậy thấp được đánh dấu | D3 | 4 | WEB-25, AI-26 | S6 | AI5, AI6 |
| WEB-34 | US-062 | Widget chatbot đặt lịch có bước xác nhận (Could) | Hiển thị fallback form khi 503 | D3 | 4 | WEB-04, AI-31 | S6 | AI7, AI8 |
