# Việc của SA — Solution Architect / PM / DevOps

> Sinh tự động bằng `python3 -I tools/plan/build.py`. Mỗi task là một PR (tên nhánh `<ID>-mo-ta-ngan`). Làm đúng thứ tự tuần; task có phụ thuộc chưa xong thì không bắt đầu.

**Definition of Done mọi task:** test đơn vị xanh, lint sạch, không secret, PR được review và merge qua pipeline.

Tổng: **109 task, 365 giờ**.

## Tuần 1 — 42 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-01 | Duyệt G1: phân tích nghiệp vụ | Phạm vi, KPI, quy tắc nghiệp vụ được nhóm chốt; trạng thái doc = Approved | 2 |  | ENABLER |
| ☐ | ARC-02 | Duyệt G2: chốt NFR và MoSCoW | Mọi NFR có số + cách đo; danh sách Must/Should/Could được chốt | 3 | ARC-01 | ENABLER |
| ☐ | ARC-03 | Duyệt G3: chuyển ADR 0001-0010 sang Accepted | Mỗi ADR truy về FR/NFR; trạng thái Accepted | 3 | ARC-02 | ENABLER |
| ☐ | ARC-04 | Sơ đồ C4 mức Context và Container | 2 sơ đồ (Mermaid/PlantUML) trong docs/03, khớp service catalog | 4 | ARC-03 | ENABLER |
| ☐ | ARC-05 | Mô hình dữ liệu từng service | Bảng resource FHIR + SearchParameter cần hỗ trợ cho từng service; owner dữ liệu rõ ràng | 6 | ARC-03 | ENABLER |
| ☐ | ARC-09 | Chuẩn coding & conventions cho service | Tài liệu: cấu trúc thư mục, lỗi, logging không PII, tracing, cấu hình, versioning API | 4 | ARC-03 | ENABLER |
| ☐ | ARC-10 | Definition of Ready/Done, quy trình PR, nhánh | Tài liệu trong docs/00; PR template phản ánh DoD | 2 | ARC-03 | ENABLER |
| ☐ | ARC-11 | Đặc tả dữ liệu giả (synthetic) | Quy tắc sinh bệnh nhân, bác sĩ, lịch, lần khám tiếng Việt; không dùng dữ liệu thật | 3 | ARC-05 | ENABLER |
| ☐ | GOV-01 | Tạo 4 repo theo ADR-0010 | 4 repo tồn tại, README mô tả trách nhiệm, visibility đúng | 1 | ARC-03 | US-070 |
| ☐ | GOV-02 | Ruleset bảo vệ nhánh main cho 4 repo | Bắt buộc PR, 1 review, CI xanh, cấm force-push; bot GitOps được bypass ở shp-gitops | 2 | GOV-01 | US-070 |
| ☐ | GOV-03 | CODEOWNERS + PR template + issue template | PR vào infra/gitops-prod cần SA duyệt; template có checklist DoD | 2 | GOV-01 | US-070 |
| ☐ | GOV-07 | GitHub Project + import backlog | Project board có cột Todo/In progress/Review/Done, field Sprint/Owner/Estimate; backlog.csv được import | 2 | GOV-01 | US-070 |
| ☐ | LOC-01 | Bộ công cụ dev local trên kind (profile core): cụm K8s nhỏ + Helm cho PostgreSQL, MongoDB replica set, Redis, RabbitMQ, MinIO, Keycloak; Makefile up/down/status | `make up` dựng cụm và mọi phụ thuộc cốt lõi trong < 10 phút trên máy 16 GB; `make down` dọn sạch; có README | 8 | ARC-09 | ENABLER |

## Tuần 2 — 43 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-19 | Tạo contracts/authz/matrix.yaml đầy đủ từ ma trận phân quyền | Mọi ô của ma trận có dòng quy tắc; schema YAML được validate; dev dùng làm đầu vào cho SHR-22 | 4 | ARC-03 | ENABLER |
| ☐ | CIT-01 | Reusable workflow .NET CI | format check, build warn-as-error, test + ngưỡng coverage, quét package lỗ hổng; dùng được từ repo khác | 4 | GOV-01 | US-070 |
| ☐ | CIT-03 | Reusable workflow container | hadolint, buildx build, Trivy chặn High/Critical, SBOM, ký cosign keyless, push ACR chỉ ở main, output digest | 5 | IAC-02, IAC-03 | US-070 |
| ☐ | GOP-01 | Helm chart chung microservice | Deployment/Rollout, Service, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, ServiceAccount WI, SecretProviderClass; values.schema.json | 8 | ARC-09 | US-070 |
| ☐ | IAC-01 | Stack bootstrap: RG shared, state storage | Storage account có versioning, soft delete, tắt shared key; state truy cập bằng Entra ID | 3 | GOV-01 | ENABLER |
| ☐ | IAC-02 | Bootstrap: Azure Container Registry | ACR tắt admin user; retention policy; output login server | 2 | IAC-01 | ENABLER |
| ☐ | IAC-03 | Bootstrap: identity OIDC cho GitHub | Identity plan (read), apply-<env>, acr-push; federated credential theo repo + environment; RBAC tối thiểu | 4 | IAC-01 | ENABLER |
| ☐ | LOC-02 | Keycloak realm local | Realm export với role patient/doctor/nurse/admin/dpo, claim fhir_user, user giả | 3 | LOC-01 | US-008, US-066 |
| ☐ | LOC-03 | Walking skeleton .NET | Service mẫu: health, info, OTel, Dockerfile chiseled non-root; chạy qua pipeline template | 4 | ARC-09 | ENABLER |
| ☐ | LOC-04 | Walking skeleton Python | FastAPI: health, info, OTel, Dockerfile non-root; chạy qua pipeline template | 3 | ARC-09 | ENABLER |
| ☐ | LOC-08 | Profile full cho dev local: ClamAV, OpenSearch, Ollama, MLflow, Loki/Grafana/OTel; seed dữ liệu giả vào cụm | `make up PROFILE=full` dựng đủ hạ tầng cho mọi service; healthcheck; tài liệu tài nguyên cần thiết | 3 | LOC-01 | ENABLER |

## Tuần 3 — 47 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-06 | Hợp đồng API (OpenAPI) khung cho 6 service | File openapi.yaml mỗi service với endpoint, mã lỗi OperationOutcome; lint không lỗi | 6 | ARC-05 | ENABLER |
| ☐ | ARC-07 | Hợp đồng sự kiện (JSON Schema) | Schema cho AppointmentBooked/Cancelled, DocumentUploaded, AuditRecorded, NoShowScored; có version | 4 | ARC-05 | ENABLER |
| ☐ | ARC-20 | Dữ liệu tham chiếu: ICD-10 (~300 mã), LOINC sinh hiệu/xét nghiệm, CodeSystem chuyên khoa và lý do hủy | File JSON trong contracts/terminology, có script nạp, được CLN-16/CLN-18 dùng | 4 | ARC-03 | ENABLER |
| ☐ | CIT-05 | Reusable workflow Terraform checks + plan | fmt, validate, tflint, Trivy config, plan + tóm tắt vào job summary; plan qua OIDC read-only | 4 | IAC-03 | US-070 |
| ☐ | CIT-06 | Reusable workflow GitOps update/promote | Cập nhật digest vào shp-gitops; uat commit sau approval; prod mở PR sau approval | 4 | GOV-05 | US-070 |
| ☐ | GOP-03 | Chart bootstrap Argo CD | AppProject, ApplicationSet platform + services theo môi trường; app-of-apps | 6 | GOP-01 | US-070 |
| ☐ | GOV-05 | GitHub App bot cho GitOps | App chỉ có quyền contents:write trên shp-gitops; secret lưu ở org/repo secret | 2 | GOV-01 | US-070 |
| ☐ | IAC-05 | Module network | VNet, subnet AKS/private endpoint/Postgres (delegated), private DNS zones; validate + tflint sạch | 5 | IAC-01 | ENABLER |
| ☐ | IAC-06 | Module monitoring | Log Analytics, Azure Monitor workspace (Prometheus), Managed Grafana, App Insights | 4 | IAC-05 | ENABLER |
| ☐ | IAC-07 | Module aks | Azure CNI overlay + Cilium, Workload Identity, KV CSI, Azure RBAC, tắt local account, 3 node pool, 3 zone | 8 | IAC-05, IAC-06 | ENABLER |

## Tuần 4 — 48 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-13 | Sprint review + planning S1 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | CIT-02 | Reusable workflow Python CI | ruff lint+format, mypy, pytest + coverage, pip-audit | 3 | GOV-01 | US-070 |
| ☐ | CIT-04 | Reusable workflow Helm validate | helm lint, render từng môi trường, kubeconform strict, Trivy config | 3 | GOP-01 | US-070 |
| ☐ | CIT-07 | Workflow kiểm tra chung cho mọi PR | gitleaks, actionlint, dependency review, CodeQL (csharp, python, actions) | 3 | GOV-01 | US-070 |
| ☐ | CIT-08 | Caller workflow mẫu cho service + script sinh | Script tạo svc-<name>.yml từ template với path filter đúng | 2 | CIT-01, CIT-02, CIT-03, CIT-06 | US-070 |
| ☐ | GOP-04 | Platform add-on: Argo Rollouts, KEDA, cert-manager | Application ghim version chart; values theo môi trường | 3 | GOP-03 | US-070 |
| ☐ | GOP-05 | Platform add-on: Envoy Gateway (Gateway API) | GatewayClass/Gateway, TLS qua cert-manager | 3 | GOP-04 | US-070 |
| ☐ | GOV-04 | GitHub Environments và người duyệt | deploy-uat, deploy-prod, infra-<env>, infra-<env>-destroy (env = uat, prod) có reviewer và wait timer theo ADR-0009 | 2 | GOV-01 | US-070 |
| ☐ | IAC-04 | Bootstrap: budget alert + chính sách tag | Budget cảnh báo 50/80/100%; tag shp-env, created-at, ttl-hours bắt buộc | 2 | IAC-01 | ENABLER |
| ☐ | IAC-08 | Module key vault | RBAC mode, private endpoint, purge protection chỉ ở prod, tên có hậu tố ngẫu nhiên | 2 | IAC-05 | ENABLER |
| ☐ | LOC-05 | Chạy service trên cụm local bằng chart microservice chung (make deploy SVC=...) | Service mẫu build, nạp vào kind và chạy bằng đúng chart dùng cho UAT/prod; có hướng dẫn debug | 3 | GOP-01, LOC-03, LOC-01 | ENABLER |
| ☐ | LOC-07 | Hướng dẫn onboarding dev | Từ clone repo đến chạy local và mở PR đầu tiên trong < 1 giờ | 2 | LOC-01, LOC-03 | ENABLER |
| ☐ | OBS-04 | Thư viện observability chuẩn (OTel, log có trace-id, che PII) | OTel traces/metrics/logs, log có trace-id, enricher che PII; test log không chứa PII | 4 | LOC-03 | ENABLER |
| ☐ | SCF-01 | Scaffold scheduling service (khung code, Dockerfile, pipeline svc-scheduling, Helm values, kết nối DB/bus/OTel) | Service từ template; svc-scheduling.yml; values local/uat/prod | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-02 | Scaffold notification worker (khung code, pipeline, values, kết nối DB/bus) | Service từ template; svc-notification.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-03 | Scaffold clinical service (khung code, pipeline, values, kết nối MongoDB/bus) | Service từ template; svc-clinical.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-04 | Scaffold document service (khung code, pipeline, values, Blob/MongoDB) | Service từ template; svc-document.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-05 | Scaffold audit worker (khung code, pipeline, values, Blob WORM/Log Analytics) | Service từ template; svc-audit.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-06 | Scaffold ai-service FastAPI (khung code, pipeline, values, node pool ai) | FastAPI từ template; svc-ai.yml; node pool ai | 2 | LOC-04, CIT-08 | ENABLER |
| ☐ | SCF-07 | Scaffold ml-service (khung code Python, Dockerfile, pipeline, values, kết nối PostgreSQL/bus/MLflow) | Service mẫu chạy qua pipeline template | 2 | LOC-04, CIT-08 | ENABLER |

## Tuần 5 — 40 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-14 | Sprint review + planning S2 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | ENV-01 | Workflow env-provision | plan -> approval infra-<env> -> apply -> cài Argo CD + bootstrap -> chờ healthy -> seed dữ liệu giả -> smoke test | 6 | IAC-17, GOP-03 | US-070 |
| ☐ | ENV-02 | Workflow env-destroy | Gõ đúng tên môi trường + approval infra-<env>-destroy; destroy sạch, kiểm tra không còn resource | 2 | IAC-17 | US-070 |
| ☐ | GOP-06 | Platform add-on: OpenTelemetry Collector | Nhận OTLP, xuất sang Azure Monitor; local xuất sang Grafana stack | 3 | GOP-03 | US-070 |
| ☐ | GOP-07 | Kyverno + policy | Chỉ image đã ký từ ACR, cấm tag latest, bắt buộc resources/probes, PSS restricted; Enforce | 4 | GOP-03 | US-070 |
| ☐ | GOP-08 | NetworkPolicy default-deny + namespace | Namespace có nhãn PSA restricted; chỉ mở luồng đã thiết kế | 2 | GOP-03 | US-070 |
| ☐ | GW-01 | API Gateway YARP | Route tới các service; truyền trace context | 3 | LOC-03 | ENABLER |
| ☐ | IAC-09 | Module postgresql | Flexible Server 16, chỉ Entra auth (không mật khẩu), PITR 7 ngày, HA zone-redundant ở prod | 4 | IAC-05 | US-067 |
| ☐ | IAC-10 | Module mongodb (Cosmos DB for MongoDB vCore) | Cluster private endpoint; mật khẩu sinh bằng random_password lưu Key Vault, không nằm trong code | 4 | IAC-05, IAC-08 | US-067 |
| ☐ | IAC-11 | Module redis | Private endpoint, Entra auth, TLS only | 2 | IAC-05 | ENABLER |
| ☐ | IAC-12 | Module storage | Container documents + audit (immutability WORM), lifecycle Hot->Cool 30 ngày, tắt shared key | 4 | IAC-05 | US-048 |
| ☐ | IAC-17 | Stack envs/uat, prod (lõi: network, AKS, Key Vault, ACR) + sizing | 2 stack gọi module; tfvars khác nhau về SKU/HA/spot; UAT có sizing=prod để đo hiệu năng; plan chạy được | 4 | IAC-05, IAC-07, IAC-08 | ENABLER |

## Tuần 6 — 27 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-15 | Sprint review + planning S3 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | ENV-05 | Diễn tập G4 | Service mẫu đi local -> uat -> prod (dựng tạm) chỉ qua pipeline; rollback bằng GitOps revert < 5 phút; image chưa ký bị chặn; destroy; có bằng chứng | 4 | ENV-01, LOC-03, LOC-04, GOP-07 | US-070 |
| ☐ | GOP-10 | Chart MLflow (tracking + registry) cho môi trường cluster | MLflow chạy với backend lưu trữ bền, truy cập qua Workload Identity | 3 | GOP-03 | US-063 |
| ☐ | IAC-13 | Module service bus | Namespace tắt local auth; RBAC cho service | 2 | IAC-05 | ENABLER |
| ☐ | IAC-14 | Module ai-search | Tắt API key (RBAC only), SKU theo biến môi trường | 2 | IAC-05 | ENABLER |
| ☐ | IAC-15 | Module workload identity cho service | Mỗi service một UAMI + federated credential (namespace/serviceaccount) + role tới đúng kho dữ liệu | 4 | IAC-07 | ENABLER |
| ☐ | IAC-16 | Module identity ứng dụng (Entra ID) | App registration API + SPA, app roles patient/doctor/nurse/admin/dpo, user thử nghiệm giả | 4 | IAC-01 | US-008, US-066 |
| ☐ | IAC-20 | Gắn các module dữ liệu và identity (IAC-09..IAC-16) vào 2 stack | plan chạy được cho cả 2 môi trường; mỗi service chỉ có quyền đúng kho dữ liệu của mình | 4 | IAC-17, IAC-12, IAC-15 | US-070 |
| ☐ | WEB-10 | Pipeline + chart cho web | Container nginx non-root dùng chart chung | 2 | WEB-01 | US-066 |

## Tuần 7 — 42 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-08 | Threat model STRIDE | Bảng mối đe dọa theo luồng dữ liệu, biện pháp giảm thiểu truy về NFR-SEC | 6 | ARC-04 | ENABLER |
| ☐ | ARC-12 | Kế hoạch kiểm thử (test strategy) | Mức test, ngưỡng coverage, kịch bản load/failure test map với NFR | 4 | ARC-02 | ENABLER |
| ☐ | ARC-16 | Sprint review + planning S4 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | CIT-09 | Phát hành templates có version | Tag vX.Y.Z trên shp-platform-templates; chart push OCI lên ACR; CHANGELOG | 2 | CIT-01, GOP-01 | US-070 |
| ☐ | CIT-10 | Đóng gói NuGet nội bộ cho thư viện dùng chung | Thư viện phát hành lên GitHub Packages có version; service tham chiếu theo version | 3 | SHR-01 | US-070 |
| ☐ | GOP-02 | Unit test cho chart | helm-unittest cho các tổ hợp values chính; chạy trong CI | 3 | GOP-01 | US-070 |
| ☐ | GOP-09 | Canary prod với Argo Rollouts | Bước 10%-50%-100%, dừng chờ promote thủ công, rollback tự động khi lỗi | 3 | GOP-04 | US-070 |
| ☐ | GOV-06 | Dependabot cho actions, nuget, pip, docker, terraform | dependabot.yml ở mỗi repo; action được ghim theo commit SHA | 2 | GOV-01 | US-070 |
| ☐ | GW-02 | Xác thực + rate limit theo người dùng | Token sai -> 401; vượt hạn mức -> 429 | 4 | GW-01, SHR-20 | US-068 |
| ☐ | IAC-18 | Module Defender for Storage + Event Grid → queue cho kết quả quét | Blob mới được quét; kết quả vào queue để document service xử lý | 3 | IAC-12 | US-036 |
| ☐ | IAC-19 | Module Azure OpenAI + Document Intelligence và các deployment | Deployment chat + embedding; truy cập bằng Managed Identity, không API key | 4 | IAC-05 | US-055 |
| ☐ | OBS-01 | Dashboard RED chuẩn cho mọi service | Grafana dashboard theo nhãn service: rate, errors, duration p50/p95/p99 | 3 | GOP-06 | ENABLER |
| ☐ | OBS-02 | SLO + cảnh báo burn rate | SLO cho NFR-A1, P1, P2; cảnh báo multi-window burn rate | 3 | OBS-01 | ENABLER |

## Tuần 8 — 34 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-17 | Sprint review + planning S5 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | ENV-03 | Workflow cost-guard | Chạy theo lịch; liệt kê môi trường sống quá TTL; mở/cập nhật issue cảnh báo (không tự hủy) | 2 | IAC-04 | US-070 |
| ☐ | ENV-04 | Runbook vòng đời môi trường | Hướng dẫn dựng/kiểm thử/hủy, thời gian dự kiến, xử lý sự cố thường gặp | 2 | ENV-01, ENV-02 | US-070 |
| ☐ | MLO-01 | Pipeline huấn luyện ML trong CI + canary deploy model | Chạy khi dữ liệu/code đổi; artifact model; canary deploy | 4 | ML-05 | US-063 |
| ☐ | MLO-02 | Image huấn luyện tái lập được: pin phiên bản thư viện, seed, image cố định, quét lỗ hổng | Cùng image + dataset hash + seed cho cùng model; image được ký | 3 | MLO-01 | US-071 |
| ☐ | MLO-03 | Argo Rollouts canary cho ml-service + AnalysisTemplate (Prometheus), tự tăng tỉ lệ hoặc rollback | Bước 10%, 50%, 100% có phân tích; model xấu tự rollback; autoPromote cấu hình theo môi trường | 6 | GOP-04, SCF-07, ML-25 | US-073 |
| ☐ | MLO-04 | Điều phối huấn luyện lại trên cluster: CronJob/KEDA nhận yêu cầu từ ML-22, chạy ML-23, theo dõi và audit | Cảnh báo drift giả lập khởi động đúng một lần huấn luyện lại; không chạy chồng | 5 | ML-23, GOP-10 | US-074 |
| ☐ | MLO-05 | Cảnh báo drift và AUC thực tế (Alertmanager) + panel dashboard MLOps | Cảnh báo tới đúng kênh; dashboard có PSI, AUC thực tế, phiên bản model, lịch sử huấn luyện lại | 3 | ML-10, OBS-01 | US-065 |
| ☐ | OBS-03 | Cảnh báo DLQ và lỗi pipeline | Cảnh báo khi dead-letter có message, cert sắp hết hạn, Argo CD app degraded | 2 | OBS-01 | ENABLER |
| ☐ | PRD-10 | Script k6: tìm slot + đặt lịch (kịch bản 1x/5x/10x) | Kịch bản 1x/5x/10x theo workload-analysis; chạy được trên UAT (cỡ prod) | 4 | SCH-14 | US-068 |
| ☐ | PRD-11 | KEDA cron scale trước 07:00 + HPA cho scheduling | Values prod/uat scale trước đợt mở slot | 1 | SCF-01, GOP-04 | US-068 |

## Tuần 9 — 2 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-18 | Sprint review + planning S6 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |

## Tuần 10 — 23 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ENV-06 | Diễn tập dựng prod ephemeral lần 1: đo thời gian dựng/hủy, seed dữ liệu, kịch bản dự phòng nếu dựng trễ | Có số đo thời gian thực tế; có bản dự phòng (video/ảnh chụp/UAT); prod được hủy sạch sau diễn tập | 4 | ENV-01, ENV-02, ENV-05 | US-070 |
| ☐ | PRD-01 | Load test 1x/5x/10x + báo cáo | Chạy trên UAT ephemeral (cỡ prod); so với NFR-P/S | 6 | PRD-10, ENV-01 | US-068 |
| ☐ | PRD-02 | Failure test | Kill pod, DB failover, provider SMS lỗi, LLM lỗi, consumer crash; ghi kết quả | 6 | ENV-01 | US-069 |
| ☐ | PRD-03 | Diễn tập backup/restore | PITR Postgres + restore Mongo; ghi thời gian thực tế so với RTO | 4 | ENV-01 | US-067 |
| ☐ | PRD-04 | Dashboard SLO cuối cùng | Error budget theo NFR-A1 | 3 | OBS-02 | ENABLER |

## Tuần 11 — 8 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | PRD-05 | Báo cáo FinOps | Chi phí theo giờ-môi trường, dự phóng tháng, khuyến nghị | 4 | ENV-03 | ENABLER |
| ☐ | PRD-06 | Rà soát bảo mật | Checklist theo threat model; kết quả quét; ma trận phân quyền xanh | 4 | ARC-08 | ENABLER |

## Tuần 12 — 9 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | PRD-08 | Kịch bản demo + slide bảo vệ | Trả lời 12 câu hỏi review của đề; demo end-to-end | 6 | PRD-01..PRD-07 | ENABLER |
| ☐ | PRD-09 | Diễn tập demo trên prod ephemeral | Dựng prod, chạy kịch bản, hủy; có timing | 3 | PRD-08 | ENABLER |
