# Backlog chi tiết

> Sinh tự động từ `tools/plan/backlog.py` (nguồn sự thật). Sửa file đó rồi chạy lại, không sửa tay file này.
> Import vào GitHub Projects bằng [backlog.csv](backlog.csv).

Tổng: **177 task**, **701 giờ**. Mỗi task ≤ 8 giờ.

## Tải theo người và theo tuần (giờ)

| Tuần | SA | D1 | D2 | D3 |
|---|---|---|---|---|
| W1 | 34 |  | 5 |  |
| W2 | 42 | 4 | 13 | 8 |
| W3 | 42 | 32 | 15 | 9 |
| W4 | 36 | 17 | 18 | 11 |
| W5 | 35 | 25 | 16 | 19 |
| W6 | 10 | 25 | 21 | 14 |
| W7 | 2 | 34 | 19 | 14 |
| W8 | 2 | 21 | 32 | 22 |
| W9 | 2 | 10 | 27 | 22 |
| W10 | 15 |  | 4 |  |
| W11 | 8 |  |  | 7 |
| W12 | 9 |  |  |  |
| **Tổng** | **237** | **168** | **170** | **126** |

## Lịch sprint

| Mã | Thời gian |
|---|---|
| S0 | W1 26/10-01/11: chốt gate G1-G3, tạo repo, onboarding |
| P1 | W2 02/11-08/11: platform phần 1; dev làm local + thư viện |
| P2 | W3 09/11-15/11: platform phần 2; dev làm local + thư viện |
| P3 | W4 16/11-22/11: platform phần 3 (module dữ liệu, identity) — song song S1 |
| P4 | W5 23/11-29/11: identity, môi trường ephemeral, G4 — song song S2 |
| S1 | W4 16/11-22/11 |
| S2 | W5 23/11-29/11 |
| S3 | W6 30/11-06/12 |
| S4 | W7 07/12-13/12 |
| S5 | W8 14/12-20/12 |
| S6 | W9 21/12-27/12 |
| H1 | W10 28/12-03/01: hardening, load/failure test |
| H2 | W11 04/01-10/01: eval, FinOps, bảo mật |
| DEF | W12 11/01-17/01: chuẩn bị bảo vệ |

## ARC — Kiến trúc & quản trị dự án

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| ARC-01 | Duyệt G1: phân tích nghiệp vụ | Phạm vi, KPI, quy tắc nghiệp vụ được nhóm chốt; trạng thái doc = Approved | SA | 2 |  | S0 | docs/01 |
| ARC-02 | Duyệt G2: chốt NFR và MoSCoW | Mọi NFR có số + cách đo; danh sách Must/Should/Could được chốt | SA | 3 | ARC-01 | S0 | docs/02 |
| ARC-03 | Duyệt G3: chuyển ADR 0001-0010 sang Accepted | Mỗi ADR truy về FR/NFR; trạng thái Accepted | SA | 3 | ARC-02 | S0 | ADR |
| ARC-04 | Sơ đồ C4 mức Context và Container | 2 sơ đồ (Mermaid/PlantUML) trong docs/03, khớp service catalog | SA | 4 | ARC-03 | S0 | P1 |
| ARC-05 | Mô hình dữ liệu từng service | Bảng resource FHIR + SearchParameter cần hỗ trợ cho từng service; owner dữ liệu rõ ràng | SA | 6 | ARC-03 | S0 | ADR-0003, ADR-0008 |
| ARC-06 | Hợp đồng API (OpenAPI) khung cho 6 service | File openapi.yaml mỗi service với endpoint, mã lỗi OperationOutcome; lint không lỗi | SA | 6 | ARC-05 | P1 | P2 |
| ARC-07 | Hợp đồng sự kiện (JSON Schema) | Schema cho AppointmentBooked/Cancelled, DocumentUploaded, AuditRecorded, NoShowScored; có version | SA | 4 | ARC-05 | P2 | P2 |
| ARC-08 | Threat model STRIDE | Bảng mối đe dọa theo luồng dữ liệu, biện pháp giảm thiểu truy về NFR-SEC | SA | 6 | ARC-04 | P3 | P3 |
| ARC-09 | Chuẩn coding & conventions cho service | Tài liệu: cấu trúc thư mục, lỗi, logging không PII, tracing, cấu hình, versioning API | SA | 4 | ARC-03 | S0 | NFR-O4 |
| ARC-10 | Definition of Ready/Done, quy trình PR, nhánh | Tài liệu trong docs/00; PR template phản ánh DoD | SA | 2 | ARC-03 | S0 | NFR-D1 |
| ARC-11 | Đặc tả dữ liệu giả (synthetic) | Quy tắc sinh bệnh nhân, bác sĩ, lịch, lần khám tiếng Việt; không dùng dữ liệu thật | SA | 3 | ARC-05 | S0 | NFR-SEC9 |
| ARC-12 | Kế hoạch kiểm thử (test strategy) | Mức test, ngưỡng coverage, kịch bản load/failure test map với NFR | SA | 4 | ARC-02 | P3 | P4 |
| ARC-13 | Sprint review + planning S1 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S1 | PM |
| ARC-14 | Sprint review + planning S2 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S2 | PM |
| ARC-15 | Sprint review + planning S3 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S3 | PM |
| ARC-16 | Sprint review + planning S4 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S4 | PM |
| ARC-17 | Sprint review + planning S5 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S5 | PM |
| ARC-18 | Sprint review + planning S6 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | SA | 2 |  | S6 | PM |

## GOV — Repo, quy trình & GitHub governance

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| GOV-01 | Tạo 4 repo theo ADR-0010 | 4 repo tồn tại, README mô tả trách nhiệm, visibility đúng | SA | 1 | ARC-03 | S0 | ADR-0010 |
| GOV-02 | Ruleset bảo vệ nhánh main cho 4 repo | Bắt buộc PR, 1 review, CI xanh, cấm force-push; bot GitOps được bypass ở shp-gitops | SA | 2 | GOV-01 | S0 | ADR-0009 |
| GOV-03 | CODEOWNERS + PR template + issue template | PR vào infra/gitops-prod cần SA duyệt; template có checklist DoD | SA | 2 | GOV-01 | S0 | NFR-D1 |
| GOV-04 | GitHub Environments và người duyệt | deploy-dev/staging/prod, infra-<env>, infra-<env>-destroy có reviewer và wait timer theo ADR-0009 | SA | 2 | GOV-01 | P1 | ADR-0009 |
| GOV-05 | GitHub App bot cho GitOps | App chỉ có quyền contents:write trên shp-gitops; secret lưu ở org/repo secret | SA | 2 | GOV-01 | P1 | ADR-0010 |
| GOV-06 | Dependabot cho actions, nuget, pip, docker, terraform | dependabot.yml ở mỗi repo; action được ghim theo commit SHA | SA | 2 | GOV-01 | P3 | NFR-SEC6 |
| GOV-07 | GitHub Project + import backlog | Project board có cột Todo/In progress/Review/Done, field Sprint/Owner/Estimate; backlog.csv được import | SA | 2 | GOV-01 | S0 | PM |

## IAC — Hạ tầng Azure bằng Terraform

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| IAC-01 | Stack bootstrap: RG shared, state storage | Storage account có versioning, soft delete, tắt shared key; state truy cập bằng Entra ID | SA | 3 | GOV-01 | P1 | NFR-D3 |
| IAC-02 | Bootstrap: Azure Container Registry | ACR tắt admin user; retention policy; output login server | SA | 2 | IAC-01 | P1 | ADR-0009 |
| IAC-03 | Bootstrap: identity OIDC cho GitHub | Identity plan (read), apply-<env>, acr-push; federated credential theo repo + environment; RBAC tối thiểu | SA | 4 | IAC-01 | P1 | NFR-SEC4 |
| IAC-04 | Bootstrap: budget alert + chính sách tag | Budget cảnh báo 50/80/100%; tag shp-env, created-at, ttl-hours bắt buộc | SA | 2 | IAC-01 | P1 | NFR-C1, C3 |
| IAC-05 | Module network | VNet, subnet AKS/private endpoint/Postgres (delegated), private DNS zones; validate + tflint sạch | SA | 5 | IAC-01 | P2 | NFR-SEC7 |
| IAC-06 | Module monitoring | Log Analytics, Azure Monitor workspace (Prometheus), Managed Grafana, App Insights | SA | 4 | IAC-05 | P2 | NFR-O1, O2 |
| IAC-07 | Module aks | Azure CNI overlay + Cilium, Workload Identity, KV CSI, Azure RBAC, tắt local account, 3 node pool, 3 zone | SA | 8 | IAC-05, IAC-06 | P2 | ADR-0004 |
| IAC-08 | Module key vault | RBAC mode, private endpoint, purge protection chỉ ở prod, tên có hậu tố ngẫu nhiên | SA | 2 | IAC-05 | P3 | NFR-SEC4 |
| IAC-09 | Module postgresql | Flexible Server 16, chỉ Entra auth (không mật khẩu), PITR 7 ngày, HA zone-redundant ở prod | SA | 4 | IAC-05 | P3 | ADR-0008, NFR-A4 |
| IAC-10 | Module mongodb (Cosmos DB for MongoDB vCore) | Cluster private endpoint; mật khẩu sinh bằng random_password lưu Key Vault, không nằm trong code | SA | 4 | IAC-05, IAC-08 | P3 | ADR-0008 |
| IAC-11 | Module redis | Private endpoint, Entra auth, TLS only | SA | 2 | IAC-05 | P3 | ADR-0008 |
| IAC-12 | Module storage | Container documents + audit (immutability WORM), lifecycle Hot->Cool 30 ngày, tắt shared key | SA | 4 | IAC-05 | P3 | NFR-A5, SEC5 |
| IAC-13 | Module service bus | Namespace tắt local auth; RBAC cho service | SA | 2 | IAC-05 | P3 | ADR-0001 |
| IAC-14 | Module ai-search | Tắt API key (RBAC only), SKU theo biến môi trường | SA | 2 | IAC-05 | P3 | ADR-0008 |
| IAC-15 | Module workload identity cho service | Mỗi service một UAMI + federated credential (namespace/serviceaccount) + role tới đúng kho dữ liệu | SA | 4 | IAC-07 | P4 | NFR-SEC4 |
| IAC-16 | Module identity ứng dụng (Entra ID) | App registration API + SPA, app roles patient/doctor/nurse/admin/dpo, user thử nghiệm giả | SA | 4 | IAC-01 | P4 | UC-08, NFR-SEC1 |
| IAC-17 | Stack envs/dev, staging, prod + sizing | 3 stack gọi module; tfvars khác nhau về SKU/HA/spot; plan chạy được | SA | 4 | IAC-05..IAC-16 | P4 | NFR-D4 |

## CIT — CI templates (GitHub Actions)

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| CIT-01 | Reusable workflow .NET CI | format check, build warn-as-error, test + ngưỡng coverage, quét package lỗ hổng; dùng được từ repo khác | SA | 4 | GOV-01 | P1 | ADR-0009 |
| CIT-02 | Reusable workflow Python CI | ruff lint+format, mypy, pytest + coverage, pip-audit | D3 | 3 | GOV-01 | P2 | ADR-0009 |
| CIT-03 | Reusable workflow container | hadolint, buildx build, Trivy chặn High/Critical, SBOM, ký cosign keyless, push ACR chỉ ở main, output digest | SA | 5 | IAC-02, IAC-03 | P1 | NFR-SEC6 |
| CIT-04 | Reusable workflow Helm validate | helm lint, render từng môi trường, kubeconform strict, Trivy config | D1 | 3 | GOP-01 | P2 | ADR-0009 |
| CIT-05 | Reusable workflow Terraform checks + plan | fmt, validate, tflint, Trivy config, plan + tóm tắt vào job summary; plan qua OIDC read-only | SA | 4 | IAC-03 | P1 | NFR-D3 |
| CIT-06 | Reusable workflow GitOps update/promote | Cập nhật digest vào shp-gitops; dev commit sau approval; staging/prod mở PR sau approval | SA | 4 | GOV-05 | P2 | ADR-0009 |
| CIT-07 | Workflow kiểm tra chung cho mọi PR | gitleaks, actionlint, dependency review, CodeQL (csharp, python, actions) | SA | 3 | GOV-01 | P2 | NFR-SEC6 |
| CIT-08 | Caller workflow mẫu cho service + script sinh | Script tạo svc-<name>.yml từ template với path filter đúng | SA | 2 | CIT-01, CIT-02, CIT-03, CIT-06 | P2 | ADR-0009 |
| CIT-09 | Phát hành templates có version | Tag vX.Y.Z trên shp-platform-templates; chart push OCI lên ACR; CHANGELOG | SA | 2 | CIT-01, GOP-01 | P3 | ADR-0010 |

## GOP — Helm & GitOps (Argo CD)

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| GOP-01 | Helm chart chung microservice | Deployment/Rollout, Service, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, ServiceAccount WI, SecretProviderClass; values.schema.json | SA | 8 | ARC-09 | P1 | ADR-0004 |
| GOP-02 | Unit test cho chart | helm-unittest cho các tổ hợp values chính; chạy trong CI | D1 | 3 | GOP-01 | P2 | ADR-0009 |
| GOP-03 | Chart bootstrap Argo CD | AppProject, ApplicationSet platform + services theo môi trường; app-of-apps | SA | 6 | GOP-01 | P2 | ADR-0009 |
| GOP-04 | Platform add-on: Argo Rollouts, KEDA, cert-manager | Application ghim version chart; values theo môi trường | D1 | 3 | GOP-03 | P2 | NFR-A7, S1 |
| GOP-05 | Platform add-on: Envoy Gateway (Gateway API) | GatewayClass/Gateway, TLS qua cert-manager | D2 | 3 | GOP-04 | P2 | NFR-SEC3 |
| GOP-06 | Platform add-on: OpenTelemetry Collector | Nhận OTLP, xuất sang Azure Monitor; local xuất sang Grafana stack | D2 | 3 | GOP-03 | P2 | NFR-O1 |
| GOP-07 | Kyverno + policy | Chỉ image đã ký từ ACR, cấm tag latest, bắt buộc resources/probes, PSS restricted; Enforce | SA | 4 | GOP-03 | P2 | NFR-SEC6 |
| GOP-08 | NetworkPolicy default-deny + namespace | Namespace có nhãn PSA restricted; chỉ mở luồng đã thiết kế | SA | 2 | GOP-03 | P2 | NFR-SEC7 |
| GOP-09 | Canary prod với Argo Rollouts | Bước 10%-50%-100%, dừng chờ promote thủ công, rollback tự động khi lỗi | SA | 3 | GOP-04 | P4 | NFR-A7, D2 |

## ENV — Vòng đời môi trường ephemeral

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| ENV-01 | Workflow env-provision | plan -> approval infra-<env> -> apply -> cài Argo CD + bootstrap -> chờ healthy -> seed dữ liệu giả -> smoke test | SA | 6 | IAC-17, GOP-03 | P4 | ADR-0009 |
| ENV-02 | Workflow env-destroy | Gõ đúng tên môi trường + approval infra-<env>-destroy; destroy sạch, kiểm tra không còn resource | SA | 2 | IAC-17 | P4 | ADR-0009 |
| ENV-03 | Workflow cost-guard | Chạy theo lịch; liệt kê môi trường sống quá TTL; mở/cập nhật issue cảnh báo (không tự hủy) | SA | 2 | IAC-04 | P4 | NFR-C1 |
| ENV-04 | Runbook vòng đời môi trường | Hướng dẫn dựng/kiểm thử/hủy, thời gian dự kiến, xử lý sự cố thường gặp | SA | 2 | ENV-01, ENV-02 | P4 | NFR-D3 |
| ENV-05 | Diễn tập G4 | Service mẫu đi dev -> staging -> prod chỉ qua pipeline; rollback < 5 phút; image chưa ký bị chặn; destroy; có bằng chứng | SA | 4 | ENV-01, LOC-03, LOC-04, GOP-09 | P4 | Gate G4 |

## OBS — Observability nền

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| OBS-01 | Dashboard RED chuẩn cho mọi service | Grafana dashboard theo nhãn service: rate, errors, duration p50/p95/p99 | SA | 3 | GOP-06 | S3 | NFR-O2 |
| OBS-02 | SLO + cảnh báo burn rate | SLO cho NFR-A1, P1, P2; cảnh báo multi-window burn rate | SA | 3 | OBS-01 | S3 | NFR-O2, O3 |
| OBS-03 | Cảnh báo DLQ và lỗi pipeline | Cảnh báo khi dead-letter có message, cert sắp hết hạn, Argo CD app degraded | SA | 2 | OBS-01 | S3 | NFR-O3 |

## LOC — Môi trường phát triển local & walking skeleton

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| LOC-01 | Docker compose hạ tầng local | Postgres, MongoDB, Redis, RabbitMQ, MinIO, Keycloak, OTel + Grafana; một lệnh khởi động | D2 | 5 | ARC-09 | S0 | NFR-I3 |
| LOC-02 | Keycloak realm local | Realm export với role patient/doctor/nurse/admin/dpo, claim fhir_user, user giả | D2 | 3 | LOC-01 | P1 | NFR-SEC1 |
| LOC-03 | Walking skeleton .NET | Service mẫu: health, info, OTel, Dockerfile chiseled non-root; chạy qua pipeline template | D1 | 4 | ARC-09 | P1 | Gate G4 |
| LOC-04 | Walking skeleton Python | FastAPI: health, info, OTel, Dockerfile non-root; chạy qua pipeline template | D3 | 3 | ARC-09 | P1 | Gate G4 |
| LOC-05 | Cluster local bằng kind | Script dựng kind + cài chart chung cho service mẫu | D1 | 3 | GOP-01, LOC-03 | P2 | NFR-I3 |
| LOC-06 | Bộ sinh dữ liệu giả | CLI sinh bệnh nhân/bác sĩ/lịch tiếng Việt theo ARC-11; seed được cả local và cloud | D2 | 6 | ARC-11 | P1 | NFR-SEC9 |
| LOC-07 | Hướng dẫn onboarding dev | Từ clone repo đến chạy local và mở PR đầu tiên trong < 1 giờ | D1 | 2 | LOC-01, LOC-03 | P2 | PM |

## SHR — Thư viện dùng chung (.NET)

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| SHR-01 | FHIR JSON + OperationOutcome middleware | Serialize/parse R4; mọi lỗi trả OperationOutcome đúng mã HTTP; unit test | D2 | 4 | ARC-09 | P1 | ADR-0003 |
| SHR-02 | SearchParameter registry + parser | Hỗ trợ token, reference, date (eq/ge/gt/le/lt, theo độ chính xác), _count; unit test | D2 | 6 | SHR-01 | P2 | ADR-0003 |
| SHR-03 | Hợp đồng IFhirStore / IFhirSession | Interface read/create/update có version/search/outbox/idempotency; tài liệu | D2 | 3 | SHR-02 | P2 | ADR-0003 |
| SHR-04 | Xác thực JWT + CallerContext | Đọc roles, fhir_user từ Entra ID và Keycloak; test với cả hai định dạng claim | D1 | 4 | LOC-02 | P2 | NFR-SEC1 |
| SHR-05 | Khung phân quyền RBAC + ABAC | Policy theo bảng phân quyền, interface kiểm quan hệ điều trị; mặc định từ chối | D1 | 6 | SHR-04 | P2 | NFR-SEC2 |
| SHR-06 | Observability chuẩn | OTel traces/metrics/logs, log có trace-id, enricher che PII; test log không chứa PII | D1 | 4 | LOC-03 | P2 | NFR-O1, O4 |
| SHR-07 | Messaging + transactional outbox | MassTransit RabbitMQ (local) / Service Bus (cloud); outbox publisher; test giao hàng ít nhất một lần | D2 | 6 | SHR-03 | S1 | ADR-0005 |
| SHR-08 | Audit client middleware | Mọi đọc/ghi dữ liệu bệnh nhân và mọi 403 phát AuditRecorded | D2 | 4 | SHR-07 | S1 | NFR-SEC5 |
| SHR-09 | Test kit dùng chung | Testcontainers fixture, test auth handler giả lập role, helper ma trận phân quyền | D1 | 4 | SHR-04 | P2 | NFR-SEC2 |
| SHR-10 | Đóng gói NuGet nội bộ | Thư viện phát hành lên GitHub Packages có version; service tham chiếu theo version | D1 | 3 | SHR-01 | S1 | ADR-0010 |

## SCH — Scheduling service

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| SCH-01 | Scaffold scheduling + pipeline + values | Service từ template; svc-scheduling.yml; values dev/staging/prod | D1 | 2 | LOC-03, CIT-08 | S1 | ADR-0001 |
| SCH-02 | Provider Postgres: CRUD có version + history | Create/read/update với optimistic concurrency; history mọi phiên bản; integration test | D1 | 6 | SHR-03 | S1 | ADR-0003 |
| SCH-03 | Provider Postgres: search index | Bảng index token/reference/date; truy vấn dùng index (có EXPLAIN); test | D1 | 6 | SCH-02 | S1 | ADR-0008 |
| SCH-04 | API Schedule + phân quyền | Nurse/Admin tạo/sửa; Doctor chỉ xem của mình (AC-02.3) | D1 | 4 | SCH-02, SHR-05 | S2 | UC-02 |
| SCH-05 | Sinh slot từ mẫu tuần | AC-02.1: 8:00-11:30, 15 phút -> 14 slot free; tối đa 30 ngày (BR-04) | D1 | 6 | SCH-04 | S2 | UC-02 |
| SCH-06 | Chặn slot | AC-02.2: slot đã đặt không chặn được | D1 | 2 | SCH-05 | S2 | UC-02 |
| SCH-07 | API tìm slot trống | Lọc theo bác sĩ, chuyên khoa, ngày, status; phân trang | D1 | 4 | SCH-03 | S2 | UC-03, NFR-P1 |
| SCH-08 | $book với transaction + optimistic concurrency | Slot + Appointment + outbox trong một transaction; 409 khi xung đột | D1 | 6 | SCH-07, SHR-07 | S3 | ADR-0005 |
| SCH-09 | Idempotency-Key | AC-03.2 và AC-03.3 | D1 | 4 | SCH-08 | S3 | ADR-0005 |
| SCH-10 | Quy tắc BR-03, BR-04 | AC-03.4; không đặt quá 30 ngày | D1 | 3 | SCH-08 | S3 | UC-03 |
| SCH-11 | $cancel và quy tắc 2 giờ | AC-03.5; slot về free; phát AppointmentCancelled | D1 | 3 | SCH-08 | S3 | UC-03 |
| SCH-12 | Đổi lịch nguyên tử | Đặt mới + hủy cũ trong một transaction; lỗi thì giữ lịch cũ | D1 | 4 | SCH-11 | S4 | UC-03 |
| SCH-13 | Test đồng thời 50 request | AC-03.1 chạy trong CI với Postgres thật | D1 | 3 | SCH-08 | S3 | NFR-P2 |
| SCH-14 | Test ma trận phân quyền scheduling | Mỗi ô của bảng phân quyền liên quan scheduling là một test | D1 | 4 | SCH-11, SHR-09 | S4 | NFR-SEC2 |
| SCH-15 | Cache Redis cho tìm slot | Cache-aside theo bác sĩ/ngày; invalidate bằng event; đo hit ratio | D1 | 5 | SCH-07, SHR-07 | S4 | ADR-0008, NFR-S2 |
| SCH-16 | Script k6 tìm slot + đặt lịch | Kịch bản 1x/5x/10x theo workload-analysis; chạy được trên staging | D1 | 4 | SCH-09 | S5 | NFR-P1, P2 |
| SCH-17 | KEDA cron scale trước 07:00 | Values prod/staging scale trước đợt mở slot | D1 | 1 | SCH-01, GOP-04 | S5 | NFR-S1 |

## NTF — Notification service

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| NTF-01 | Scaffold notification worker + pipeline | Service từ template; svc-notification.yml | D1 | 2 | LOC-03, CIT-08 | S3 | ADR-0001 |
| NTF-02 | Lên lịch nhắc T-24h, T-2h | Consume AppointmentBooked; scheduled message | D1 | 5 | NTF-01, SHR-07 | S4 | UC-07 |
| NTF-03 | Hủy nhắc khi hủy lịch | AC-07.1 | D1 | 4 | NTF-02 | S4 | UC-07 |
| NTF-04 | Kênh email/SMS/push + provider giả | Abstraction kênh; provider giả cho test và demo | D1 | 4 | NTF-01 | S4 | UC-07 |
| NTF-05 | Retry, dead-letter, kênh dự phòng | Backoff tối đa 5 lần; DLQ; push lỗi -> email | D1 | 5 | NTF-04 | S5 | NFR-A6 |
| NTF-06 | Consumer idempotent | AC-07.2: không gửi trùng | D1 | 3 | NTF-02 | S5 | UC-07 |
| NTF-07 | Nhắc thêm cho lịch rủi ro cao | Gọi ML qua circuit breaker; ML lỗi vẫn nhắc bình thường (AC-15.1) | D1 | 4 | NTF-02, ML-04 | S6 | UC-15 |
| NTF-08 | Metric độ trễ gửi | Histogram để đo NFR-P8 | D1 | 2 | NTF-02 | S5 | NFR-P8 |

## GW — API Gateway

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| GW-01 | API Gateway YARP | Route tới các service; truyền trace context | D1 | 3 | LOC-03 | S2 | ADR-0001 |
| GW-02 | Xác thực + rate limit theo người dùng | Token sai -> 401; vượt hạn mức -> 429 | D1 | 4 | GW-01, SHR-04 | S3 | NFR-S3 |
| GW-03 | Waiting room lúc mở slot (Could) | Hàng đợi ảo khi vượt ngưỡng tải 10x | D1 | 6 | GW-02 | S6 | NFR-S3 |

## CLN — Clinical service

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| CLN-01 | Scaffold clinical + pipeline + values | Service từ template; svc-clinical.yml | D2 | 2 | LOC-03, CIT-08 | S1 | ADR-0001 |
| CLN-02 | Provider MongoDB: CRUD có version + history | Optimistic concurrency theo versionId; collection history; integration test | D2 | 6 | SHR-03 | S1 | ADR-0008 |
| CLN-03 | Provider MongoDB: search + index | Index theo SearchParameter; partition key patient; test | D2 | 6 | CLN-02 | S2 | ADR-0008 |
| CLN-04 | Đăng ký bệnh nhân + chống trùng | AC-01.1, 01.2, 01.3 | D2 | 5 | CLN-03, SHR-05 | S2 | UC-01 |
| CLN-05 | Practitioner + liên kết tài khoản | Practitioner gắn với user; chuyên khoa | D2 | 3 | CLN-02 | S2 | UC-08 |
| CLN-06 | Kiểm tra quan hệ điều trị (ABAC) | Projection từ sự kiện lịch hẹn; API cho service khác; test | D2 | 6 | CLN-04, SHR-07 | S3 | BR-06 |
| CLN-07 | Vòng đời Encounter | Check-in, kết thúc; AC-04.2 không sửa sau finished | D2 | 5 | CLN-06 | S3 | UC-04 |
| CLN-08 | Observation sinh hiệu + Condition ICD-10 | AC-04.1: chỉ bác sĩ có quan hệ điều trị ghi chẩn đoán | D2 | 4 | CLN-07 | S4 | UC-04 |
| CLN-09 | Tìm bệnh nhân không dấu | AC-06.1 | D2 | 4 | CLN-03 | S3 | UC-06 |
| CLN-10 | Timeline bệnh nhân | Gộp encounter, chẩn đoán, sinh hiệu, tài liệu theo thời gian; p95 <= 500 ms | D2 | 5 | CLN-08 | S4 | UC-06, NFR-P3 |
| CLN-11 | Break-glass | Bắt buộc lý do, hiệu lực 60 phút, phát audit đặc biệt (UC-10) | D2 | 5 | CLN-06, SHR-08 | S5 | UC-10 |
| CLN-12 | Test ma trận phân quyền clinical | Mỗi ô liên quan clinical là một test | D2 | 4 | CLN-11, SHR-09 | S5 | NFR-SEC2 |

## DOC — Document service

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| DOC-01 | Scaffold document + pipeline | Service từ template; svc-document.yml | D2 | 2 | LOC-03, CIT-08 | S4 | ADR-0001 |
| DOC-02 | IBlobStore: Azure Blob + MinIO | Một interface, hai implementation; integration test với MinIO | D2 | 4 | DOC-01 | S4 | ADR-0008 |
| DOC-03 | Link upload + xác nhận + kiểm tra file | User delegation SAS; AC-05.1 (loại file, <= 20 MB) | D2 | 5 | DOC-02 | S5 | UC-05 |
| DOC-04 | Quét mã độc | Defender for Storage trên cloud / ClamAV local; file nhiễm bị cách ly | D2 | 4 | DOC-03 | S5 | UC-05 |
| DOC-05 | DocumentReference + link tải 5 phút | AC-05.2; mỗi lần xin link có audit | D2 | 4 | DOC-03, SHR-08 | S5 | BR-08 |
| DOC-06 | Phát hành tài liệu cho bệnh nhân | AC-05.3 | D2 | 2 | DOC-05 | S6 | UC-05 |
| DOC-07 | Sự kiện DocumentUploaded | Phát qua outbox sau khi quét sạch | D2 | 2 | DOC-04, SHR-07 | S5 | UC-13 |

## AUD — Audit service

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| AUD-01 | Scaffold audit worker + pipeline | Service từ template; svc-audit.yml | D2 | 2 | LOC-03, CIT-08 | S2 | ADR-0001 |
| AUD-02 | Ghi WORM có chuỗi hash | Batch NDJSON theo giờ vào container immutable; mỗi batch chứa hash batch trước | D2 | 6 | AUD-01, SHR-07 | S3 | NFR-SEC5 |
| AUD-03 | Đẩy sang Log Analytics / Loki | Xuất hiện trong công cụ tra cứu <= 1 phút | D2 | 4 | AUD-02 | S4 | NFR-SEC5 |
| AUD-04 | API tra cứu audit cho DPO | Lọc theo bệnh nhân/người/thời gian; xuất CSV (UC-09) | D2 | 4 | AUD-03 | S6 | UC-09 |
| AUD-05 | Cảnh báo break-glass cho DPO | AC-10.1 <= 5 phút | D2 | 3 | AUD-03, CLN-11 | S6 | UC-10 |
| AUD-06 | Kiểm chứng tính bất biến | Test: sửa/xóa blob audit bị từ chối; công cụ kiểm tra chuỗi hash | D2 | 3 | AUD-02 | S6 | AC-09.2 |

## AI — AI service

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| AI-01 | Scaffold ai-service + pipeline + values | FastAPI từ template; svc-ai.yml; node pool ai | D3 | 2 | LOC-04, CIT-08 | S1 | ADR-0001 |
| AI-02 | LLM router: provider + chuỗi fallback | Interface provider; chuỗi model theo tác vụ; timeout; unit test với provider giả | D3 | 6 | AI-01 | S1 | ADR-0006 |
| AI-03 | Circuit breaker, budget guard, metric theo model | Mở mạch khi lỗi liên tiếp; hạn mức token/ngày; metric fallback | D3 | 5 | AI-02 | S2 | ADR-0006, NFR-C2 |
| AI-04 | Redact PII trước provider ngoài Azure | Che tên, SĐT, mã định danh; test với dữ liệu giả | D3 | 4 | AI-02 | S2 | NFR-SEC8 |
| AI-05 | RAG ingestion | Parse, chunk 500-800 token có overlap, embed, index vào AI Search/OpenSearch; metadata quyền | D3 | 6 | AI-01 | S2 | UC-11 |
| AI-06 | Truy xuất hybrid + security trimming | Chỉ trả chunk người dùng được xem | D3 | 5 | AI-05 | S3 | UC-11 |
| AI-07 | Sinh câu trả lời có trích dẫn | AC-11.1; không có nguồn thì từ chối | D3 | 5 | AI-06, AI-03 | S3 | UC-11 |
| AI-08 | Chống prompt injection + bộ test 50 mẫu | AC-11.2; NFR-AI3 = 0 lần vượt rào | D3 | 6 | AI-07 | S4 | NFR-AI3 |
| AI-09 | Bộ eval RAG 100 câu + harness | Recall@5, MRR, groundedness, citation; chạy cho từng model trong chuỗi | D3 | 8 | AI-07 | S4 | NFR-AI1, AI2, AI7 |
| AI-10 | Tóm tắt hồ sơ bệnh nhân | UC-12; gọi clinical bằng token người dùng (on-behalf-of); mỗi ý có link nguồn | D3 | 6 | AI-03, CLN-10 | S5 | UC-12 |
| AI-11 | Pipeline trích xuất tài liệu | Consume DocumentUploaded -> OCR -> phân loại -> trích -> hàng chờ duyệt; retry + DLQ | D3 | 8 | AI-03, DOC-07 | S5 | UC-13 |
| AI-12 | Eval trích xuất (F1) | Bộ tài liệu giả có nhãn; báo cáo F1 mức trường | D3 | 6 | AI-11 | S6 | NFR-AI4 |
| AI-13 | Chatbot đặt lịch (Could) | Tool tìm slot/đặt lịch bằng quyền bệnh nhân; bắt buộc xác nhận (AC-14.1) | D3 | 8 | AI-03, SCH-09 | S6 | UC-14 |
| AI-14 | Báo cáo token và chi phí | Chi phí/request theo tác vụ và model | D3 | 3 | AI-09 | H2 | NFR-C2 |

## ML — ML no-show (MLOps)

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| ML-01 | Sinh dữ liệu lịch hẹn giả có quy luật | Lead time, lịch sử vắng, giờ khám...; tài liệu mô tả quy luật | D3 | 5 | ARC-11 | P1 | ADR-0007 |
| ML-02 | Train baseline + LightGBM với MLflow | Theo dõi tham số/metric; so sánh với baseline | D3 | 6 | ML-01 | P2 | ADR-0007 |
| ML-03 | Quality gate + model registry | Chỉ đăng ký model khi AUC >= 0.75 và hơn baseline | D3 | 3 | ML-02 | S1 | NFR-AI6 |
| ML-04 | API dự đoán no-show | /predict/no-show trả xác suất + phiên bản model (AC-15.2) | D3 | 4 | ML-03, LOC-04 | S2 | UC-15 |
| ML-05 | Pipeline huấn luyện trong CI | Chạy khi dữ liệu/code đổi; artifact model; canary deploy | D3 | 4 | ML-03, CIT-02 | S3 | ADR-0007 |
| ML-06 | Theo dõi drift | Job hằng tuần so sánh phân phối; cảnh báo | D3 | 4 | ML-04 | S6 | NFR-AI6 |

## WEB — Web app (PWA)

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| WEB-01 | PWA shell + đăng nhập OIDC + điều hướng theo role | React + Vite; Entra ID và Keycloak; build qua pipeline | D1 | 6 | LOC-02 | S2 | NFR-I2 |
| WEB-02 | Bệnh nhân: tìm slot, đặt, xem lịch | Gọi gateway; xử lý 409 và gợi ý slot khác | D1 | 8 | WEB-01, SCH-09 | S4 | UC-03 |
| WEB-03 | Nhân viên: quản lý lịch bác sĩ | Tạo mẫu tuần, xem/chặn slot | D1 | 6 | WEB-01, SCH-06 | S5 | UC-02 |
| WEB-04 | Nhân viên: tìm bệnh nhân + timeline | Tìm không dấu; timeline | D2 | 8 | WEB-01, CLN-10 | S5 | UC-06 |
| WEB-05 | Form lần khám | Check-in, sinh hiệu, chẩn đoán | D2 | 6 | WEB-04, CLN-08 | S6 | UC-04 |
| WEB-06 | Upload/xem tài liệu | Upload trực tiếp bằng SAS; xem bằng link 5 phút | D2 | 5 | WEB-04, DOC-05 | S6 | UC-05 |
| WEB-07 | Giao diện AI: trợ lý, tóm tắt, duyệt trích xuất | Hiển thị trích dẫn, nhãn do AI tạo | D3 | 8 | WEB-01, AI-07 | S5 | UC-11..13 |
| WEB-08 | Widget chatbot (Could) | Hội thoại + bước xác nhận | D3 | 4 | WEB-07, AI-13 | S6 | UC-14 |
| WEB-09 | Màn hình audit cho DPO | Lọc, xuất CSV | D2 | 4 | WEB-01, AUD-04 | S6 | UC-09 |
| WEB-10 | Pipeline + chart cho web | Container nginx non-root dùng chart chung | SA | 2 | WEB-01, CIT-08 | S2 | ADR-0009 |

## PRD — Production engineering & bảo vệ

| ID | Task | Tiêu chí nghiệm thu | Owner | Giờ | Phụ thuộc | Sprint | Truy vết |
|---|---|---|---|---|---|---|---|
| PRD-01 | Load test 1x/5x/10x + báo cáo | Chạy trên staging ephemeral; so với NFR-P/S | SA | 6 | SCH-16, ENV-01 | H1 | NFR-P1, P2, S2, S3 |
| PRD-02 | Failure test | Kill pod, DB failover, provider SMS lỗi, LLM lỗi, consumer crash; ghi kết quả | SA | 6 | ENV-01 | H1 | NFR-A3, A6 |
| PRD-03 | Diễn tập backup/restore | PITR Postgres + restore Mongo; ghi thời gian thực tế so với RTO | D2 | 4 | ENV-01 | H1 | UC-16, NFR-A4 |
| PRD-04 | Dashboard SLO cuối cùng | Error budget theo NFR-A1 | SA | 3 | OBS-02 | H1 | NFR-O2 |
| PRD-05 | Báo cáo FinOps | Chi phí theo giờ-môi trường, dự phóng tháng, khuyến nghị | SA | 4 | ENV-03 | H2 | NFR-C1..C3 |
| PRD-06 | Rà soát bảo mật | Checklist theo threat model; kết quả quét; ma trận phân quyền xanh | SA | 4 | ARC-08 | H2 | P3 |
| PRD-07 | Báo cáo eval AI/ML | RAG, trích xuất, chatbot, no-show; theo từng model fallback | D3 | 4 | AI-09, AI-12, ML-06 | H2 | P5 |
| PRD-08 | Kịch bản demo + slide bảo vệ | Trả lời 12 câu hỏi review của đề; demo end-to-end | SA | 6 | PRD-01..PRD-07 | DEF | Đề bài |
| PRD-09 | Diễn tập demo trên prod ephemeral | Dựng prod, chạy kịch bản, hủy; có timing | SA | 3 | PRD-08 | DEF | Đề bài |
