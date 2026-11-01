# Việc của SA — Solution Architect / PM / DevOps; AI, ML, dữ liệu giả

> Sinh tự động bằng `python3 -I tools/plan/build.py`. Mỗi task là một PR (tên nhánh `<ID>-mo-ta-ngan`). Làm đúng thứ tự tuần; task có phụ thuộc chưa xong thì không bắt đầu.

**Definition of Done mọi task:** test đơn vị xanh, lint sạch, không secret, PR được review và merge qua pipeline.

Tổng: **206 task, 695 giờ**. Đã xong: **20**, làm một phần: **23** (☑ xong, ◐ một phần, ☐ chưa; ghi trong `tools/plan/status.py`).

## Tuần 1 — 42 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | ARC-01 | Duyệt G1: phân tích nghiệp vụ | Phạm vi, KPI, quy tắc nghiệp vụ được nhóm chốt; trạng thái doc = Approved | 2 |  | ENABLER |
| ☐ | ARC-02 | Duyệt G2: chốt NFR và MoSCoW | Mọi NFR có số + cách đo; danh sách Must/Should/Could được chốt | 3 | ARC-01 | ENABLER |
| ☐ | ARC-03 | Duyệt G3: chuyển ADR 0001-0010 sang Accepted | Mỗi ADR truy về FR/NFR; trạng thái Accepted | 3 | ARC-02 | ENABLER |
| ☐ | ARC-04 | Sơ đồ C4 mức Context và Container | 2 sơ đồ (Mermaid/PlantUML) trong docs/03, khớp service catalog | 4 | ARC-03 | ENABLER |
| ☐ | ARC-05 | Mô hình dữ liệu từng service | Bảng resource FHIR + SearchParameter cần hỗ trợ cho từng service; owner dữ liệu rõ ràng | 6 | ARC-03 | ENABLER |
| ☑ | ARC-09 | Chuẩn coding & conventions cho service **[xong, commit 3e827ec: docs/00-conventions.md: cấu trúc thư mục, lỗi, log không PII, tracing, cấu hình, versioning]** | Tài liệu: cấu trúc thư mục, lỗi, logging không PII, tracing, cấu hình, versioning API | 4 | ARC-03 | ENABLER |
| ☑ | ARC-10 | Definition of Ready/Done, quy trình PR, nhánh **[xong, commit 3e827ec: docs/00-workflow.md: DoR/DoD, quy trình PR, main được bảo vệ; PR template đã phản ánh DoD]** | Tài liệu trong docs/00; PR template phản ánh DoD | 2 | ARC-03 | ENABLER |
| ☑ | ARC-11 | Đặc tả dữ liệu giả (synthetic) **[xong, commit 3e827ec: docs/00-synthetic-data.md: quy tắc sinh dữ liệu giả tiếng Việt, scale, phân bố, kiểm tự động]** | Quy tắc sinh bệnh nhân, bác sĩ, lịch, lần khám tiếng Việt; không dùng dữ liệu thật | 3 | ARC-05 | ENABLER |
| ☑ | GOV-01 | Tạo 4 repo theo ADR-0010 **[xong, commit viethung12062005: 4 repo private, nhánh main, squash-only, xóa nhánh sau merge, Dependabot alerts; secret scanning của GitHub không có ở gói hiện tại (thay bằng gitleaks trong CI)]** | 4 repo tồn tại, README mô tả trách nhiệm, visibility đúng | 1 | ARC-03 | US-070 |
| ☑ | GOV-02 | Ruleset bảo vệ nhánh main cho 4 repo **[xong, commit ruleset protect-main: Cả 4 repo: bắt buộc PR, 1 review + Code Owner, hủy approval khi push mới, resolve thread, lịch sử tuyến tính, cấm force-push/xóa; chỉ chủ repo bypass qua PR]** | Bắt buộc PR, 1 review, CI xanh, cấm force-push; bot GitOps được bypass ở shp-gitops | 2 | GOV-01 | US-070 |
| ◐ | GOV-03 | CODEOWNERS + PR template + issue template **[làm một phần, commit gitops 3ce293d, infra 124859b, templates 97aea24: CODEOWNERS + PR/issue template cho 3 repo (chờ merge); app repo đã có từ trước; CODEOWNERS dev (D1..D3) chờ tài khoản GitHub thật]** | PR vào infra/gitops-prod cần SA duyệt; template có checklist DoD | 2 | GOV-01 | US-070 |
| ☐ | GOV-07 | GitHub Project + import backlog | Project board có cột Todo/In progress/Review/Done, field Sprint/Owner/Estimate; backlog.csv được import | 2 | GOV-01 | US-070 |
| ☐ | LOC-01 | Bộ công cụ dev local trên kind (profile core): cụm K8s nhỏ + Helm cho PostgreSQL, MongoDB replica set, Redis, RabbitMQ, Azurite, Keycloak; Makefile up/down/status | `make up` dựng cụm và mọi phụ thuộc cốt lõi trong < 10 phút trên máy 16 GB; `make down` dọn sạch; có README | 8 | ARC-09 | ENABLER |

## Tuần 2 — 83 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☑ | ARC-19 | Tạo contracts/authz/matrix.yaml đầy đủ từ ma trận phân quyền **[xong, commit cd8c789: 64 rule, validator bất biến an toàn, test của validator]** | Mọi ô của ma trận có dòng quy tắc; schema YAML được validate; dev dùng làm đầu vào cho SHR-22 | 4 | ARC-03 | ENABLER |
| ☐ | CIT-01 | Reusable workflow Java CI (Maven) | Spotless check, build -Werror, test + JaCoCo ngưỡng coverage, quét dependency lỗ hổng; dùng được từ repo khác | 4 | GOV-01 | US-070 |
| ☐ | CIT-03 | Reusable workflow container | hadolint, buildx build, Trivy chặn High/Critical, SBOM, ký cosign keyless, push ACR chỉ ở main, output digest | 5 | IAC-02, IAC-03 | US-070 |
| ☐ | DAT-01 | Bộ sinh dữ liệu giả tiếng Việt: bệnh nhân, bác sĩ, điều dưỡng (không dữ liệu thật) | Tên/SĐT/ngày sinh hợp lệ nhưng giả; seed cố định để tái lập | 4 | ARC-11 | ENABLER |
| ☐ | GOP-01 | Helm chart chung microservice | Deployment/Rollout, Service, HPA/KEDA, PDB, NetworkPolicy, HTTPRoute, ServiceAccount WI, SecretProviderClass; values.schema.json | 8 | ARC-09 | US-070 |
| ☐ | IAC-01 | Stack bootstrap: RG shared, state storage | Storage account có versioning, soft delete, tắt shared key; state truy cập bằng Entra ID | 3 | GOV-01 | ENABLER |
| ☐ | IAC-02 | Bootstrap: Azure Container Registry | ACR tắt admin user; retention policy; output login server | 2 | IAC-01 | ENABLER |
| ☐ | IAC-03 | Bootstrap: identity OIDC cho GitHub | Identity plan (read), apply-<env>, acr-push; federated credential theo repo + environment; RBAC tối thiểu | 4 | IAC-01 | ENABLER |
| ☐ | LOC-02 | Keycloak realm local | Realm export với role patient/doctor/nurse/admin/dpo, claim fhir_user, user giả | 3 | LOC-01 | US-008, US-066 |
| ◐ | LOC-03 | Walking skeleton Java (Spring Boot) **[làm một phần, commit c909d5b: 6 service Java: health/info/prometheus, OTel, Dockerfile distroless non-root (đã build + chạy thử gateway, scheduling); CHƯA chạy qua pipeline GitHub Actions thật]** | Service mẫu: actuator health, info, OTel, Dockerfile distroless non-root; chạy qua pipeline template | 4 | ARC-09 | ENABLER |
| ☐ | LOC-04 | Walking skeleton Python | FastAPI: health, info, OTel, Dockerfile non-root; chạy qua pipeline template | 3 | ARC-09 | ENABLER |
| ◐ | LOC-08 | Profile full cho dev local: ClamAV, OpenSearch, Ollama, MLflow, Loki/Grafana/OTel; seed dữ liệu giả vào cụm **[làm một phần, commit e8e2fe2: chart shp-local-full (ClamAV, OpenSearch, Ollama, MLflow, Loki, Grafana, OTel) + make up PROFILE=full + pull-models; đã helm lint/template; CHƯA chạy trên kind thật]** | `make up PROFILE=full` dựng đủ hạ tầng cho mọi service; healthcheck; tài liệu tài nguyên cần thiết | 3 | LOC-01 | ENABLER |
| ☐ | ML-01 | Sinh dữ liệu lịch hẹn có quy luật no-show (lead time, lịch sử vắng, giờ khám, kênh) | Tài liệu mô tả quy luật; tỉ lệ no-show ~18% tổng | 4 | ARC-11 | US-063 |
| ☐ | PY-01 | Thư viện Python: xác thực JWT (Keycloak), dependency role cho FastAPI | Test token hợp lệ/sai/hết hạn | 3 | LOC-02 | US-066 |
| ☐ | PY-05 | Problem Details + danh mục mã lỗi cho FastAPI | Khớp 07 §5; test | 2 | LOC-04 | ENABLER |
| ☐ | PY-06 | Bộ che PII (tên, SĐT, email, địa chỉ, mã định danh → token giả có bản đồ ngược) | Bộ test dữ liệu giả: không còn PII trong prompt gửi ra ngoài Azure | 4 |  | US-055 |
| ☐ | SHR-01 | FHIR R4 JSON serialize/parse (HAPI FHIR) + test vòng khứ hồi cho resource trong phạm vi | Parse sai trả 400 validation-failed; test từng resource type | 3 | LOC-03 | ENABLER |
| ☑ | SHR-02 | Mô hình lỗi: exception → OperationOutcome / Problem Details + danh mục mã lỗi **[xong, commit 5e423ab: libs/java/shp-web/errors: ErrorCatalog (27 mã, test đối chiếu 07 §5), ShpException, ErrorWriter, ShpExceptionHandler]** | Mọi mã ở 07 §5 có test; thông điệp vi/en, không lộ PII/stack trace | 3 | SHR-01 | ENABLER |
| ☐ | SHR-03 | Search parameter registry + parser (token, reference, date theo độ chính xác, _count) | ge/gt/le/lt/eq, '2026-11' = cả tháng; tham số lạ → 400 | 4 | SHR-01 | ENABLER |
| ◐ | SHR-20 | JWT bearer từ Keycloak (OIDC chuẩn): map claim roles, kiểm amr=mfa với nhân viên **[làm một phần, commit 5e423ab: shp-web ShpJwt + SecurityFilterChain (issuer, audience, hạn, MFA nhân viên); test bằng token HMAC cục bộ; CHƯA thử với Keycloak thật]** | Test token hợp lệ/sai chữ ký/hết hạn/sai aud/nhân viên thiếu MFA; cấu hình qua biến môi trường | 3 | LOC-02 | US-008, US-066 |
| ☑ | SHR-21 | Identity resolver: sub → fhirUser + roles + disabled + linkedProfiles (hồ sơ, phạm vi), cache 60 giây **[xong, commit 5e423ab: shp-web IdentityResolver (cache 60s/5s) + HttpIdentityLinkClient + IdentityInterceptor; endpoint nội bộ phía clinical là task dev]** | Tài khoản bị khóa bị từ chối ≤ 60 giây; cache hết hạn đúng; clinical lỗi thì dùng cache, không có cache thì 503 | 3 | SHR-20 | US-007 |
| ☑ | SHR-22 | Authz: schema + loader + validator cho contracts/authz/matrix.yaml **[xong, commit 95d2f74: libs/java/shp-authz: AuthzMatrix (Java)]** | Matrix sai schema (thiếu role, predicate lạ) làm build thất bại; unit test | 3 | ARC-19 | ENABLER |
| ☑ | SHR-23 | Authz: các predicate own, self-practitioner, care, breakglass, released, before-2h, encounter-finished, not-care **[xong, commit 95d2f74: Predicates trong AuthorizationEngine (care/breakglass nhận qua ResourceContext, không gọi mạng)]** | Mỗi predicate có test đúng/sai; predicate care/breakglass gọi qua interface có thể giả lập | 4 | SHR-22 | ENABLER |

## Tuần 3 — 135 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ◐ | ARC-06 | Hợp đồng API (OpenAPI) khung cho 6 service **[làm một phần, commit cd8c789: Có khung 83 operation cho 7 service, validator OpenAPI; CHƯA có schema thân request/response (dev thêm khi làm endpoint)]** | File openapi.yaml mỗi service với endpoint, mã lỗi OperationOutcome; lint không lỗi | 6 | ARC-05 | ENABLER |
| ☑ | ARC-07 | Hợp đồng sự kiện (JSON Schema) **[xong, commit cd8c789: 11 schema event + ví dụ + envelope; validator khớp danh mục E1–E11]** | Schema cho AppointmentBooked/Cancelled, DocumentUploaded, AuditRecorded, NoShowScored; có version | 4 | ARC-05 | ENABLER |
| ◐ | ARC-20 | Dữ liệu tham chiếu: ICD-10 (~300 mã), LOINC sinh hiệu/xét nghiệm, CodeSystem chuyên khoa và lý do hủy **[làm một phần, commit cd8c789: Có 12 chuyên khoa, 6 lý do hủy, 10 LOINC sinh hiệu, 6 loại tài liệu, 12 ICD-10; CHƯA đủ ~300 mã ICD-10 và chưa đối chiếu nguồn chính thức]** | File JSON trong contracts/terminology, có script nạp, được CLN-16/CLN-18 dùng | 4 | ARC-03 | ENABLER |
| ☐ | CIT-05 | Reusable workflow Terraform checks + plan | fmt, validate, tflint, Trivy config, plan + tóm tắt vào job summary; plan qua OIDC read-only | 4 | IAC-03 | US-070 |
| ☐ | CIT-06 | Reusable workflow GitOps update/promote | Cập nhật digest vào shp-gitops; uat commit sau approval; prod mở PR sau approval | 4 | GOV-05 | US-070 |
| ☐ | DAT-02 | Bộ sinh lịch làm việc, lịch hẹn, lần khám, sinh hiệu, chẩn đoán, tài liệu mẫu | Dữ liệu nhất quán giữa các service (ID khớp) | 4 | DAT-01 | ENABLER |
| ☑ | GOP-03 | Chart bootstrap Argo CD **[xong, commit gitops 4314a40: repo shp-gitops: bootstrap Argo CD, AppProject platform/shp-apps, ApplicationSet platform + services theo môi trường (app-of-apps)]** | AppProject, ApplicationSet platform + services theo môi trường; app-of-apps | 6 | GOP-01 | US-070 |
| ☐ | GOV-05 | GitHub App bot cho GitOps | App chỉ có quyền contents:write trên shp-gitops; secret lưu ở org/repo secret | 2 | GOV-01 | US-070 |
| ☐ | IAC-05 | Module network | VNet, subnet AKS/private endpoint/Postgres (delegated), private DNS zones; validate + tflint sạch | 5 | IAC-01 | ENABLER |
| ☐ | IAC-06 | Module monitoring | Log Analytics, Azure Monitor workspace (Prometheus), Managed Grafana, App Insights | 4 | IAC-05 | ENABLER |
| ☐ | IAC-07 | Module aks | Azure CNI overlay + Cilium, Workload Identity, KV CSI, Azure RBAC, tắt local account, 3 node pool, 3 zone | 8 | IAC-05, IAC-06 | ENABLER |
| ☐ | ML-02 | Feature engineering + chia train/validation theo thời gian | Không rò rỉ tương lai; test đặc trưng | 4 | ML-01 | US-063 |
| ☐ | ML-03 | Baseline Logistic Regression + metric (AUC, precision/recall tại ngưỡng) | Ghi vào MLflow | 3 | ML-02 | US-063 |
| ☐ | ML-04 | LightGBM + tìm tham số + MLflow tracking | So sánh với baseline; lưu artifact | 4 | ML-03 | US-063 |
| ☐ | ML-13 | Dataset Parquet có phiên bản: ghi hash dataset vào run MLflow, dựng lại model từ hash | Cùng hash + seed → cùng model (kiểm bằng test) | 3 | ML-01 | US-071 |
| ☐ | ML-14 | Kiểm tra dữ liệu trước khi train (schema, khoảng, tỉ lệ thiếu, rò rỉ thời gian) bằng pandera | Dữ liệu hỏng làm pipeline dừng với lý do rõ; test các ca hỏng | 3 | ML-13 | US-071 |
| ☐ | ML-15 | Package đặc trưng dùng chung cho huấn luyện và phục vụ | Một nguồn duy nhất cho mọi đặc trưng; có unit test | 3 | ML-02 | US-071 |
| ☐ | ML-17 | Đánh giá theo nhóm con (chuyên khoa, nhóm tuổi, kênh) + calibration + độ trễ dự đoán | Cổng chặn nếu nhóm thấp hơn AUC tổng > 0,07 hoặc p95 > 100 ms | 4 | ML-04 | US-072 |
| ☐ | ML-21 | Bộ dữ liệu giả thứ hai có phân phối dịch chuyển (lead time dài hơn, tỉ lệ vắng tăng) | Có tài liệu mô tả mức dịch chuyển; dùng cho test detector và demo | 3 | ML-01 | US-065 |
| ☐ | PY-02 | Client đổi token thay mặt người dùng (Keycloak, RFC 8693 token exchange) | Token trao đổi mang danh tính người dùng, aud đúng; test với IdP giả | 3 | PY-01 | US-053 |
| ☐ | PY-03 | Consumer CloudEvents Python: validate schema, inbox (Mongo/Postgres), retry/DLQ | Event trùng xử lý một lần | 4 | ARC-07 | US-059, US-064 |
| ☐ | PY-07 | Giao diện LLM provider + adapter DeepSeek, Gemini, Ollama (OpenAI-compatible) + redact PII bắt buộc + provider giả cho test | Cùng một interface; mô phỏng timeout/429/5xx | 4 | LOC-04 | US-055 |
| ☐ | PY-08 | Router: chuỗi model theo tác vụ, timeout, chuyển model kế, kết quả cuối cùng khi hết chuỗi | Ghi fallbackLevel; test mọi nhánh lỗi | 4 | PY-07 | US-055 |
| ☐ | SHR-04 | Keyset page token (mờ, ký) + dựng Bundle searchset | Token bị sửa → 400; link next/self đúng | 3 | SHR-03 | ENABLER |
| ☐ | SHR-05 | Filter ETag/If-Match (412/428) và Idempotency-Key bắt buộc (428) | Thiếu header → 428; ETag W/"version" trên mọi response | 2 | SHR-02 | ENABLER |
| ☑ | SHR-06 | CloudEvents envelope + validate JSON Schema + helper test hợp đồng **[xong, commit 5e423ab: libs/java/shp-events: CloudEvent + CloudEvents.create (UUIDv7, traceparent, ≤64KB) + EventSchemas.validate (schema nhúng)]** | Event sai schema bị từ chối; helper dùng cho mọi service | 3 | ARC-07 | ENABLER |
| ◐ | SHR-07 | Outbox relay dùng chung (store PostgreSQL và MongoDB), poll 1 giây, SKIP LOCKED **[làm một phần, commit 3764d57: shp-data-postgres + shp-data-mongo: outbox ghi cùng transaction + relay (SKIP LOCKED / findAndModify có khóa hết hạn); CHƯA có EventPublisher thật (Spring Cloud Stream)]** | Bus chết rồi sống lại vẫn phát đủ; nhiều replica không trùng | 4 | SHR-06 | ENABLER |
| ◐ | SHR-08 | Consumer nền tảng: inbox idempotent + Spring Cloud Stream (RabbitMQ local, Service Bus cloud), retry/DLQ **[làm một phần, commit 3764d57: PostgresInbox + MongoInbox (đúng một lần, rollback cả dấu inbox); CHƯA có consumer Spring Cloud Stream, retry/DLQ]** | Event trùng chỉ xử lý một lần; 5 lần giao rồi DLQ | 4 | SHR-06 | ENABLER |
| ☐ | SHR-10 | Mongo resource store: create/update với version CAS + history + outbox trong một transaction | Update version cũ khớp 0; transaction rollback toàn phần khi lỗi | 4 | SHR-03, LOC-08 | ENABLER |
| ☑ | SHR-24 | Authz: decision engine default-deny, ràng buộc trường (fields), purposeOfUse **[xong, commit 95d2f74: AuthorizationEngine.decide (Java): default-deny, fields, purpose, 238 test]** | Không có quy tắc khớp → Deny; nhiều quy tắc khớp lấy Allow đầu tiên có điều kiện thỏa; test bảng | 3 | SHR-23 | ENABLER |
| ☑ | SHR-25 | Authz: filter endpoint Spring + test 'mọi endpoint phải có quy tắc' **[xong, commit 5e423ab: shp-web @RequireShpAuthz + AuthzInterceptor + UnprotectedEndpoints.find (test chặn quên phân quyền)]** | Thêm endpoint không có quy tắc làm test CI thất bại; 403/404 đúng quy ước | 4 | SHR-24 | ENABLER |
| ☐ | SHR-26 | Authz: bộ sinh test từ matrix.yaml (role × điều kiện) | Từ matrix sinh test Allow/Deny cho mọi ô; dùng được trong test của từng service | 4 | SHR-24 | ENABLER |
| ☑ | SHR-28 | Idempotency-Key middleware cho PostgreSQL (claim, request hash, replay) **[xong, commit 3764d57: shp-data-postgres PostgresIdempotency + shp-web IdempotencyKeys; 28 test với PostgreSQL thật]** | Cùng key+body → trả kết quả cũ; khác body → 422; thiếu key → 428; test song song | 4 | SHR-05 | US-015 |
| ☐ | SHR-30 | Authz: điều kiện linked, linked-records, guardian; onBehalfOf trong audit; sinh test từ ma trận cho hồ sơ liên kết | Mỗi quy tắc *-LINKED có test Allow và Deny; audit ghi tài khoản thực hiện và hồ sơ được truy cập | 3 | SHR-24, SHR-21 | US-076, US-077 |
| ◐ | WEB-01 | Web shell React/Vite: OIDC (Keycloak), router theo vai trò, khung giao diện, i18n vi/en **[làm một phần, commit nhánh WEB-01-frontend-base: web/: React 18 + TS + Vite + Mantine 7, OIDC PKCE (shp-web), route/menu theo vai trò, i18n vi/en, ErrorBoundary, hệ thiết kế + AppShell chuyển từ portal PoC (Apache-2.0); 6 màn hình mẫu chạy với MSW; CHƯA thử đăng nhập Keycloak thật trên trình duyệt]** | Đăng nhập, menu theo role, ErrorBoundary; build qua pipeline | 4 | LOC-02, SHR-20 | US-066 |

## Tuần 4 — 87 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-01 | Bootstrap MongoDB ai + script tạo index AI Search kb-chunks-v1 (và OpenSearch local) | Script idempotent; cùng schema cho hai backend | 3 | SCF-06, LOC-08 | US-052 |
| ☐ | AI-02 | Parser tài liệu: PDF, DOCX, Markdown → văn bản có cấu trúc (mục, trang) | Giữ tiêu đề mục và số trang; test với tài liệu mẫu | 3 | AI-01 | US-052 |
| ☐ | AI-03 | Chunker: 600 token, overlap 80, tách theo tiêu đề mục | Chunk không cắt giữa mục; test độ dài | 4 | AI-02 | US-052 |
| ☐ | AI-04 | Embedding bge-m3 local (Ollama, 1024 chiều) + upsert/xóa index; thay version không có khoảng trống | Chunk mới index xong mới xóa chunk cũ; test | 4 | AI-03, PY-07 | US-052 |
| ☐ | ARC-13 | Sprint review + planning S1 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | CIT-02 | Reusable workflow Python CI | ruff lint+format, mypy, pytest + coverage, pip-audit | 3 | GOV-01 | US-070 |
| ☐ | CIT-04 | Reusable workflow Helm validate | helm lint, render từng môi trường, kubeconform strict, Trivy config | 3 | GOP-01 | US-070 |
| ☐ | CIT-07 | Workflow kiểm tra chung cho mọi PR | gitleaks, actionlint, dependency review, CodeQL (java-kotlin, python, actions) | 3 | GOV-01 | US-070 |
| ☐ | CIT-08 | Caller workflow mẫu cho service + script sinh | Script tạo svc-<name>.yml từ template với path filter đúng | 2 | CIT-01, CIT-02, CIT-03, CIT-06 | US-070 |
| ◐ | GOP-04 | Platform add-on: Argo Rollouts, KEDA, cert-manager **[làm một phần, commit gitops 4314a40: ApplicationSet ghim version: argo-rollouts 2.43.7, keda 2.20.2, cert-manager v1.21.2; CHƯA chạy trên AKS thật]** | Application ghim version chart; values theo môi trường | 3 | GOP-03 | US-070 |
| ◐ | GOP-05 | Platform add-on: Envoy Gateway (Gateway API) **[làm một phần, commit gitops 4314a40: envoy-gateway 1.9.3 + GatewayClass/Gateway + ClusterIssuer Let's Encrypt (shp-baseline); CHƯA có tên miền, chưa chạy thật]** | GatewayClass/Gateway, TLS qua cert-manager | 3 | GOP-04 | US-070 |
| ◐ | GOV-04 | GitHub Environments và người duyệt **[làm một phần, commit Environments: Đã tạo deploy-uat/prod, infra-uat/prod(+destroy); CHƯA có reviewer/wait timer vì gói GitHub không hỗ trợ cho repo private]** | deploy-uat, deploy-prod, infra-<env>, infra-<env>-destroy (env = uat, prod) có reviewer và wait timer theo ADR-0009 | 2 | GOV-01 | US-070 |
| ☐ | IAC-04 | Bootstrap: budget alert + chính sách tag | Budget cảnh báo 50/80/100%; tag shp-env, created-at, ttl-hours bắt buộc | 2 | IAC-01 | ENABLER |
| ☐ | IAC-08 | Module key vault | RBAC mode, private endpoint, purge protection chỉ ở prod, tên có hậu tố ngẫu nhiên | 2 | IAC-05 | ENABLER |
| ◐ | LOC-05 | Chạy service trên cụm local bằng chart microservice chung (make deploy SVC=...) **[làm một phần, commit c909d5b: deploy.sh + app-values-local cho Java (SPRING_*, JWKS trong cụm); chart render cho từng service; CHƯA chạy make quickstart trên kind thật]** | Service mẫu build, nạp vào kind và chạy bằng đúng chart dùng cho UAT/prod; có hướng dẫn debug | 3 | GOP-01, LOC-03, LOC-01 | ENABLER |
| ☐ | LOC-07 | Hướng dẫn onboarding dev | Từ clone repo đến chạy local và mở PR đầu tiên trong < 1 giờ | 2 | LOC-01, LOC-03 | ENABLER |
| ☐ | ML-05 | Cổng chất lượng và model registry: AUC ≥ 0,75 và hơn baseline mới được đăng ký alias production | Model kém bị chặn; có test cho cổng | 3 | ML-04, LOC-08 | US-063 |
| ☐ | ML-18 | Sinh model card tự động khi đăng ký model | Model card lưu cùng artifact trong MLflow; có đủ mục theo 16-mlops §3 | 3 | ML-17, ML-05 | US-072 |
| ☐ | OBS-04 | Thư viện observability chuẩn (OTel, log có trace-id, che PII) | OTel traces/metrics/logs, log có trace-id, enricher che PII; test log không chứa PII | 4 | LOC-03 | ENABLER |
| ☐ | PY-04 | Audit emitter Python (E11) cho AI/ML | Không ghi nội dung câu hỏi hay PII; test | 2 | PY-03 | US-047 |
| ☐ | PY-09 | Circuit breaker + hạn mức token/ngày + metric theo model | Mạch mở sau 5 lỗi/60s; vượt ngân sách → ai-budget-exceeded | 3 | PY-08 | US-055 |
| ◐ | SCF-01 | Scaffold scheduling service (khung code, Dockerfile, pipeline svc-scheduling, Helm values, kết nối DB/bus/OTel) **[làm một phần, commit c909d5b: scheduling: khung Spring Boot + Flyway + outbox/inbox/idempotency PostgreSQL, Dockerfile, svc-scheduling.yml, values-local; CHƯA nối bus (Spring Cloud Stream), values uat/prod ở repo GitOps]** | Service từ template; svc-scheduling.yml; values local/uat/prod | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-02 | Scaffold notification worker (khung code, pipeline, values, kết nối DB/bus) **[làm một phần, commit c909d5b: notification: khung + Mongo outbox/inbox, collection do make up tạo; CHƯA nối bus, values uat/prod]** | Service từ template; svc-notification.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-03 | Scaffold clinical service (khung code, pipeline, values, kết nối MongoDB/bus) **[làm một phần, commit c909d5b: clinical: khung + Mongo + MongoIdentityLinkClient; CHƯA nối bus, values uat/prod]** | Service từ template; svc-clinical.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-04 | Scaffold document service (khung code, pipeline, values, Blob/MongoDB) **[làm một phần, commit c909d5b: document: khung + Mongo; CHƯA Blob client, nối bus, values uat/prod]** | Service từ template; svc-document.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ◐ | SCF-05 | Scaffold audit worker (khung code, pipeline, values, Blob WORM/Log Analytics) **[làm một phần, commit c909d5b: audit: khung + Mongo; CHƯA Blob WORM/Log Analytics, nối bus, values uat/prod]** | Service từ template; svc-audit.yml | 2 | LOC-03, CIT-08 | ENABLER |
| ☐ | SCF-06 | Scaffold ai-service FastAPI (khung code, pipeline, values, node pool ai) | FastAPI từ template; svc-ai.yml; node pool ai | 2 | LOC-04, CIT-08 | ENABLER |
| ☐ | SCF-07 | Scaffold ml-service (khung code Python, Dockerfile, pipeline, values, kết nối MongoDB/bus/MLflow) | Service mẫu chạy qua pipeline template | 2 | LOC-04, CIT-08 | ENABLER |
| ◐ | SHR-09 | Thư viện audit emitter: dựng audit.recorded (schema 05), che IP, chặn PII, middleware FHIR **[làm một phần, commit 5e423ab: shp-events AuditRecordBuilder (che IP, chặn giá trị trong resource) + AuditSink; CHƯA có sink ghi outbox]** | Audit không chứa nội dung lâm sàng; search chỉ ghi tên tham số | 4 | SHR-06 | US-047 |
| ☐ | SHR-11 | Mongo search executor: biên dịch tiêu chí search thành truy vấn + dùng index | Kế hoạch truy vấn dùng IXSCAN cho các truy vấn chính | 3 | SHR-10 | ENABLER |
| ☑ | SHR-12 | Idempotency-Key cho MongoDB (document service) **[xong, commit 3764d57: shp-data-mongo MongoIdempotency + MongoMessaging.inTransaction (thử lại khi xung đột); 21 test với MongoDB thật]** | Cùng key → cùng kết quả; khác body 422 | 2 | SHR-05, SHR-10 | US-035 |
| ☑ | SHR-27 | Authz: phát audit cho mọi quyết định (cho phép và từ chối) **[xong, commit 5e423ab: AuthzAudit.fromDecision + interceptor ghi audit cho cho-phép, từ-chối, lỗi 5xx]** | Mỗi Deny sinh audit outcome=denied kèm mã lý do; test | 2 | SHR-24, SHR-09 | US-047 |
| ☑ | WEB-02 | Client API: gắn token, Idempotency-Key, If-Match/ETag, ánh xạ OperationOutcome/Problem Details sang thông điệp **[xong, commit nhánh WEB-01-frontend-base: web/src/api/client.ts: token, Accept-Language, Idempotency-Key, If-Match/ETag, OperationOutcome/Problem Details + slot gợi ý 409; test]** | Hiển thị lỗi theo Accept-Language; tự thử lại đăng nhập khi hết phiên | 3 | WEB-01 | US-066 |

## Tuần 5 — 76 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-05 | API kho tri thức (AI9): tải lên, duyệt, thay file, gỡ; quyền Admin/Doctor khoa | draft → approved → indexed → retired; lỗi index retry 3 lần | 4 | AI-04, PY-01 | US-052 |
| ☐ | AI-06 | Truy xuất hybrid (RRF, rerank cross-encoder tùy chọn theo eval) + security trimming theo role/khoa; xuống cấp khi embedding/reranker lỗi | Người không được phép không bao giờ thấy chunk; test lọc | 4 | AI-04 | US-053 |
| ☐ | AI-07 | Prompt template có version + lắp ngữ cảnh trong giới hạn token | ID phiên bản prompt ghi vào message | 3 | AI-06 | US-053 |
| ☐ | AI-08 | Sinh câu trả lời + kiểm tra trích dẫn khớp chunk + quy tắc answered=false | Không có nguồn → không gọi LLM sinh nội dung | 4 | AI-07, PY-08 | US-053 |
| ☐ | ARC-14 | Sprint review + planning S2 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | ENV-01 | Workflow env-provision | plan -> approval infra-<env> -> apply -> cài Argo CD + bootstrap -> chờ healthy -> seed dữ liệu giả -> smoke test | 6 | IAC-17, GOP-03 | US-070 |
| ☐ | ENV-02 | Workflow env-destroy | Gõ đúng tên môi trường + approval infra-<env>-destroy; destroy sạch, kiểm tra không còn resource | 2 | IAC-17 | US-070 |
| ◐ | GOP-06 | Platform add-on: OpenTelemetry Collector **[làm một phần, commit gitops 4314a40: opentelemetry-collector 0.177.0: OTLP → Azure Monitor; CHƯA có Secret appinsights (ENV-01)]** | Nhận OTLP, xuất sang Azure Monitor; local xuất sang Grafana stack | 3 | GOP-03 | US-070 |
| ◐ | GOP-07 | Kyverno + policy **[làm một phần, commit gitops 4314a40: kyverno 3.9.1 + 4 policy Enforce (chỉ ACR, chữ ký cosign keyless, cấm latest, resources/probe); CHƯA kiểm với image ký thật]** | Chỉ image đã ký từ ACR, cấm tag latest, bắt buộc resources/probes, PSS restricted; Enforce | 4 | GOP-03 | US-070 |
| ☑ | GOP-08 | NetworkPolicy default-deny + namespace **[xong, commit gitops 4314a40: namespace shp-apps PSA restricted + NetworkPolicy default-deny; service mở ingress theo values]** | Namespace có nhãn PSA restricted; chỉ mở luồng đã thiết kế | 2 | GOP-03 | US-070 |
| ☐ | IAC-09 | Module postgresql | Flexible Server 16, chỉ Entra auth (không mật khẩu), PITR 7 ngày, HA zone-redundant ở prod | 4 | IAC-05 | US-067 |
| ☐ | IAC-10 | Module mongodb (Cosmos DB for MongoDB vCore) | Cluster private endpoint; mật khẩu sinh bằng random_password lưu Key Vault, không nằm trong code | 4 | IAC-05, IAC-08 | US-067 |
| ☐ | IAC-11 | Module redis | Private endpoint, Entra auth, TLS only | 2 | IAC-05 | ENABLER |
| ☐ | IAC-12 | Module storage | Container documents + audit (immutability WORM), lifecycle Hot->Cool 30 ngày, tắt shared key | 4 | IAC-05 | US-048 |
| ☐ | IAC-17 | Stack envs/uat, prod (lõi: network, AKS, Key Vault, ACR) + sizing | 2 stack gọi module; tfvars khác nhau về SKU/HA/spot; UAT có sizing=prod để đo hiệu năng; plan chạy được | 4 | IAC-05, IAC-07, IAC-08 | ENABLER |
| ☐ | ML-06 | Consumer E1/E2/E3/E5/E7: ghi appointment_fact, cập nhật outcome và attendance_stats | Event trùng bỏ qua; hủy muộn <24h tính late_cancel | 4 | SCF-07, PY-03, ML-11 | US-064 |
| ☐ | ML-07 | Chấm điểm khi nhận E1: dựng đặc trưng, dự đoán, lưu prediction, phát no-show-scored qua outbox | AC-15.2 lưu model version và đặc trưng; cùng transaction | 4 | ML-06, ML-05 | US-064, US-046 |
| ☐ | ML-08 | API M1 (xem dự đoán), M2 (model hiện hành), M3 (predict nội bộ) | p95 ≤ 100 ms cho M3; quyền đúng | 3 | ML-07 | US-063, US-064 |
| ◐ | ML-11 | Khởi tạo MongoDB ml (ADR-0014): patient_stats, appointment_facts, predictions (time-series nếu vCore hỗ trợ), inbox, outbox **[làm một phần, commit 3b7390f: contracts/db/mongo-notification-ml.js: collection ml, predictions time-series + index (modelVersion, predictedAt), validator ageBand/outcome; CHƯA ràng buộc score (time-series không nhận validator — kiểm ở code)]** | Script idempotent; validator ràng buộc ageBand, score; index (modelVersion, predictedAt) | 2 | SCF-07 | US-064 |
| ☐ | ML-16 | Test chống lệch train/serve: cùng mẫu cho đầu ra khớp giữa hai đường | Test xanh trong CI; lệch thì thất bại | 3 | ML-15, ML-07 | US-071 |
| ☐ | ML-19 | Detector drift: PSI từng đặc trưng và điểm dự đoán so với dữ liệu huấn luyện | Ngưỡng PSI > 0,2 ở ≥ 2 đặc trưng chính; test với bộ dữ liệu dịch chuyển | 4 | ML-07, ML-21 | US-065 |
| ☐ | ML-20 | Giám sát hiệu năng thực tế: ghép dự đoán với kết quả thật, AUC cuộn 14 ngày | Cảnh báo khi AUC giảm > 0,05 và có ≥ 500 mẫu nhãn | 4 | ML-06, ML-07 | US-065 |

## Tuần 6 — 66 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-09 | API hỏi trợ lý (AI1) + SSE stream, event reset khi chuyển model | Mode retrieval-only khi mọi model lỗi; p95 token đầu ≤ 3s | 4 | AI-08, PY-09 | US-053, US-055 |
| ☐ | AI-10 | Guardrail đầu vào: độ dài, mẫu prompt injection, chặn có audit | Mẫu tấn công bị chặn; không chặn nhầm câu hỏi y khoa thường | 3 | AI-09 | US-054 |
| ☐ | AI-11 | Guardrail đầu ra: schema, không có chẩn đoán/chỉ định tự đưa ra, quote khớp chunk | Vi phạm → thử model kế rồi answered=false | 3 | AI-09 | US-054 |
| ☐ | AI-12 | Lưu hội thoại/tin nhắn (TTL 30 ngày) + API feedback và lịch sử (AI2, AI3) | Chỉ chủ hội thoại; đánh giá lặp 409 | 3 | AI-09 | US-056 |
| ☐ | AI-13 | Theo dõi token/chi phí/fallback + API usage (AI10) + cảnh báo 80% ngân sách | Báo cáo theo tác vụ/model/ngày | 3 | PY-09 | US-055 |
| ☐ | AI-14 | Bộ eval RAG phần 1: 50 câu hỏi + đoạn đáp án chuẩn từ kho tri thức mẫu | File JSON có kiểm tra schema | 3 | AI-05 | US-057 |
| ☐ | ARC-15 | Sprint review + planning S3 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | DAT-03 | CLI seed gọi API công khai: local và môi trường cloud | Một lệnh nạp xong dữ liệu mẫu; chạy lại không trùng | 3 | DAT-02, SCH-14, CLN-05 | ENABLER |
| ☐ | ENV-05 | Diễn tập G4 | Service mẫu đi local -> uat -> prod (dựng tạm) chỉ qua pipeline; rollback bằng GitOps revert < 5 phút; image chưa ký bị chặn; destroy; có bằng chứng | 4 | ENV-01, LOC-03, LOC-04, GOP-07 | US-070 |
| ☐ | GOP-10 | Chart MLflow (tracking + registry) cho môi trường cluster | MLflow chạy với backend lưu trữ bền, truy cập qua Workload Identity | 3 | GOP-03 | US-063 |
| ☐ | IAC-13 | Module service bus | Namespace tắt local auth; RBAC cho service | 2 | IAC-05 | ENABLER |
| ☐ | IAC-14 | Module ai-search | Tắt API key (RBAC only), SKU theo biến môi trường | 2 | IAC-05 | ENABLER |
| ☐ | IAC-15 | Module workload identity cho service | Mỗi service một UAMI + federated credential (namespace/serviceaccount) + role tới đúng kho dữ liệu | 4 | IAC-07 | ENABLER |
| ☐ | IAC-16 | Hạ tầng cho Keycloak (ADR-0017) | PostgreSQL riêng cho IAM, secret trong Key Vault, đường dẫn /idp qua Gateway, NetworkPolicy chỉ cho gateway và admin IP | 4 | IAC-01 | US-008, US-066 |
| ☐ | IAC-20 | Gắn các module dữ liệu và identity (IAC-09..IAC-16) vào 2 stack | plan chạy được cho cả 2 môi trường; mỗi service chỉ có quyền đúng kho dữ liệu của mình | 4 | IAC-17, IAC-12, IAC-15 | US-070 |
| ☐ | ML-09 | Nạp lại model khi đổi alias production trong ≤ 60 giây không cần deploy | Test đổi alias | 2 | ML-08 | US-063 |
| ☐ | ML-12 | Test hợp đồng: consume E1/E5 và phát E10; ML lỗi không ảnh hưởng nhắc lịch | Xanh trong CI; AC-15.1 có test ở notification | 2 | ML-08, SHR-06 | US-064, US-046 |
| ☐ | ML-22 | Logic kích hoạt huấn luyện lại: điều kiện, cooldown 7 ngày, ≥ 500 mẫu nhãn mới, chống chạy chồng | Test các ca: drift, AUC giảm, trong cooldown, thiếu mẫu | 3 | ML-19, ML-20 | US-074 |
| ☐ | ML-23 | Job huấn luyện lại: train → kiểm tra dữ liệu → cổng chất lượng → đăng ký alias candidate, không bao giờ tự đổi production | Không đạt cổng thì dừng và giữ nguyên production; mọi run có liên kết và audit | 4 | ML-22, ML-05, ML-14, ML-17 | US-074 |
| ☐ | ML-24 | Chế độ shadow trong serving: model candidate chấm điểm song song, chỉ ghi nhận, không dùng kết quả | Không tăng độ trễ phía người dùng; có metric đồng thuận với bản đang chạy | 3 | ML-09 | US-073 |
| ☐ | ML-25 | Xuất metric cho phân tích canary: tỉ lệ lỗi, p95, phân phối điểm, đồng thuận, gắn nhãn phiên bản | Prometheus có đủ metric theo model_version để AnalysisTemplate dùng | 3 | ML-08 | US-073 |
| ◐ | WEB-10 | Pipeline + chart cho web **[làm một phần, commit nhánh WEB-01-frontend-base; shp-gitops nhánh WEB-10-web-app; templates v0.1.5: Dockerfile nginx non-root (Trivy 0 HIGH/CRITICAL), svc-web.yml + node-ci, chart pathPrefixes/configFiles, values uat/prod; CHƯA chạy pipeline thật và chưa deploy lên cụm]** | Container nginx non-root dùng chart chung | 2 | WEB-01 | US-066 |

## Tuần 7 — 61 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-15 | Bộ eval RAG phần 2: thêm 50 câu (có câu không có đáp án trong tài liệu) | Tổng ≥ 100 câu, ≥ 15 câu không có đáp án | 3 | AI-14 | US-057 |
| ☐ | AI-16 | Harness eval: Recall@5, MRR, groundedness, độ đúng trích dẫn, tỉ lệ từ chối đúng | Chạy một lệnh, xuất báo cáo Markdown + JSON | 4 | AI-15 | US-057 |
| ☐ | AI-17 | Bộ tấn công prompt injection 50 mẫu (trong câu hỏi và trong tài liệu) + runner | 0 lần vượt rào là điều kiện qua CI | 4 | AI-11 | US-054 |
| ☐ | AI-19 | Thu thập dữ liệu cho tóm tắt bằng OBO: timeline, bệnh nền, dị ứng, kết quả bất thường; đánh số nguồn | Không có quyền → 403 trước khi dữ liệu vào prompt | 4 | PY-02, CLN-23 | US-058 |
| ☐ | AI-20 | Prompt + schema đầu ra + bộ kiểm tra nguồn (loại ý không có nguồn hợp lệ) | Mọi ý có sources trỏ bản ghi thật | 4 | AI-19, PY-08 | US-058 |
| ☐ | ARC-08 | Threat model STRIDE | Bảng mối đe dọa theo luồng dữ liệu, biện pháp giảm thiểu truy về NFR-SEC | 6 | ARC-04 | ENABLER |
| ☐ | ARC-12 | Kế hoạch kiểm thử (test strategy) | Mức test, ngưỡng coverage, kịch bản load/failure test map với NFR | 4 | ARC-02 | ENABLER |
| ☐ | ARC-16 | Sprint review + planning S4 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | CIT-09 | Phát hành templates có version | Tag vX.Y.Z trên shp-platform-templates; chart push OCI lên ACR; CHANGELOG | 2 | CIT-01, GOP-01 | US-070 |
| ☐ | CIT-10 | Đóng gói Maven nội bộ cho thư viện dùng chung | Thư viện phát hành lên GitHub Packages (Maven) có version; service tham chiếu theo version | 3 | SHR-01 | US-070 |
| ☐ | GOP-02 | Unit test cho chart | helm-unittest cho các tổ hợp values chính; chạy trong CI | 3 | GOP-01 | US-070 |
| ☐ | GOP-09 | Canary prod với Argo Rollouts | Bước 10%-50%-100%, dừng chờ promote thủ công, rollback tự động khi lỗi | 3 | GOP-04 | US-070 |
| ☐ | GOV-06 | Dependabot cho actions, maven, pip, docker, terraform | dependabot.yml ở mỗi repo; action được ghim theo commit SHA | 2 | GOV-01 | US-070 |
| ☐ | IAC-18 | Module Defender for Storage + Event Grid → queue cho kết quả quét | Blob mới được quét; kết quả vào queue để document service xử lý | 3 | IAC-12 | US-036 |
| ☐ | IAC-19 | Node pool ai chạy Ollama (bge-m3, qwen2.5) + secret API key DeepSeek/Gemini trong Key Vault | Ollama có PVC cache model; key chỉ trong Key Vault, nạp bằng CSI, không trong repo/values | 4 | IAC-05 | US-055 |
| ☐ | OBS-01 | Dashboard RED chuẩn cho mọi service | Grafana dashboard theo nhãn service: rate, errors, duration p50/p95/p99 | 3 | GOP-06 | ENABLER |
| ☐ | OBS-02 | SLO + cảnh báo burn rate | SLO cho NFR-A1, P1, P2; cảnh báo multi-window burn rate | 3 | OBS-01 | ENABLER |
| ☐ | WEB-30 | Giao diện trợ lý: hỏi, stream, hiển thị trích dẫn bấm được, chấm điểm | Hiển thị chế độ retrieval-only; trích dẫn mở đúng mục | 4 | WEB-02, AI-09 | US-053, US-056 |

## Tuần 8 — 57 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-18 | Chạy eval cho từng model trong chuỗi fallback và lập báo cáo so sánh | Báo cáo theo model (NFR-AI7) | 3 | AI-16, IAC-19 | US-057, US-055 |
| ☐ | AI-21 | API tóm tắt (AI4): Idempotency-Key, cache 10 phút, nhãn AI, fallback structured-timeline | Mọi model lỗi vẫn trả timeline; có nhãn 'Do AI tạo' (AC-12.3) | 4 | AI-20 | US-058 |
| ☐ | AI-22 | Mô hình job trích xuất + consumer document-uploaded + state machine + retry/DLQ | unique (documentId, version); retry 1/5/15 phút → failed | 4 | PY-03, DOC-05 | US-059 |
| ☐ | AI-23 | Bước OCR bằng Tesseract (local); văn bản lưu Blob | Fallback khi dịch vụ lỗi; test với tài liệu mẫu | 4 | AI-22, DOC-10 | US-059 |
| ☐ | AI-24 | Phân loại + trích trường bằng LLM theo schema + validator giá trị/đơn vị + độ tin cậy | overallConfidence = min các trường bắt buộc; ngưỡng 0,85 | 4 | AI-23, PY-08 | US-059 |
| ☐ | ARC-17 | Sprint review + planning S5 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | ENV-03 | Workflow cost-guard | Chạy theo lịch; liệt kê môi trường sống quá TTL; mở/cập nhật issue cảnh báo (không tự hủy) | 2 | IAC-04 | US-070 |
| ☐ | ENV-04 | Runbook vòng đời môi trường | Hướng dẫn dựng/kiểm thử/hủy, thời gian dự kiến, xử lý sự cố thường gặp | 2 | ENV-01, ENV-02 | US-070 |
| ☐ | MLO-01 | Pipeline huấn luyện ML trong CI + canary deploy model | Chạy khi dữ liệu/code đổi; artifact model; canary deploy | 4 | ML-05 | US-063 |
| ☐ | MLO-02 | Image huấn luyện tái lập được: pin phiên bản thư viện, seed, image cố định, quét lỗ hổng | Cùng image + dataset hash + seed cho cùng model; image được ký | 3 | MLO-01 | US-071 |
| ☐ | MLO-03 | Argo Rollouts canary cho ml-service + AnalysisTemplate (Prometheus), tự tăng tỉ lệ hoặc rollback | Bước 10%, 50%, 100% có phân tích; model xấu tự rollback; autoPromote cấu hình theo môi trường | 6 | GOP-04, SCF-07, ML-25 | US-073 |
| ☐ | MLO-04 | Điều phối huấn luyện lại trên cluster: CronJob/KEDA nhận yêu cầu từ ML-22, chạy ML-23, theo dõi và audit | Cảnh báo drift giả lập khởi động đúng một lần huấn luyện lại; không chạy chồng | 5 | ML-23, GOP-10 | US-074 |
| ☐ | OBS-03 | Cảnh báo DLQ và lỗi pipeline | Cảnh báo khi dead-letter có message, cert sắp hết hạn, Argo CD app degraded | 2 | OBS-01 | ENABLER |
| ☐ | PRD-10 | Script k6: tìm slot + đặt lịch (kịch bản 1x/5x/10x) | Kịch bản 1x/5x/10x theo workload-analysis; chạy được trên UAT (cỡ prod) | 4 | SCH-14 | US-068 |
| ☐ | PRD-11 | KEDA cron scale trước 07:00 + HPA cho scheduling | Values prod/uat scale trước đợt mở slot | 1 | SCF-01, GOP-04 | US-068 |
| ☐ | WEB-31 | Giao diện quản lý kho tri thức và xem chi phí AI (Admin) | Tải lên, duyệt, gỡ; bảng usage | 4 | WEB-30, AI-13 | US-053, US-052 |
| ☐ | WEB-32 | Panel tóm tắt hồ sơ: nhãn AI, nguồn bấm được sang bản ghi | Hiển thị mode structured-timeline khi LLM lỗi | 3 | WEB-21, AI-21 | US-058 |

## Tuần 9 — 42 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-25 | API danh sách và chi tiết trích xuất (AI5) kèm vị trí trường để tô trên tài liệu | Lọc theo status/patient; quyền Care/Nurse | 3 | AI-24 | US-059, US-060 |
| ☐ | AI-26 | API duyệt (AI6): tạo Observation bằng OBO sau khi người xác nhận; ghi editedFields | Chưa xác nhận không có dữ liệu vào hồ sơ; lỗi clinical trả nguyên văn | 4 | AI-25, PY-02, CLN-17 | US-060 |
| ☐ | AI-27 | Bộ tài liệu giả có nhãn cho trích xuất (xét nghiệm, đơn thuốc, chuyển viện) | ≥ 60 tài liệu có nhãn trường | 4 | AI-24 | US-061 |
| ☐ | AI-29 | Bot đặt lịch: 4 tool, schema tham số, giới hạn 6 vòng và timeout 10 giây | Tool lạ bị từ chối; tham số sai bị chặn | 4 | PY-08, PY-02 | US-062 |
| ☐ | AI-30 | pending_action + API xác nhận (AI8): gọi $book/$cancel bằng token bệnh nhân, hết hạn 5 phút | Chưa xác nhận không có lịch hẹn (AC-14.1); 409 → đề xuất lại | 4 | AI-29, SCH-14 | US-062 |
| ☐ | AI-31 | API chat đặt lịch (AI7) + fallback sang form khi mọi model lỗi | 503 ai-unavailable kèm fallback (AC-14.3) | 3 | AI-30 | US-062 |
| ☐ | AI-32 | Test ma trận phân quyền và hợp đồng event cho ai-service | Xanh trong CI; bác sĩ không Care không lấy được dữ liệu qua AI | 4 | AI-31, AI-26 | US-052, US-053, US-058, US-060, US-062 |
| ☐ | ARC-18 | Sprint review + planning S6 | Demo, cập nhật trạng thái backlog, rủi ro; biên bản ngắn | 2 |  | ENABLER |
| ☐ | ML-10 | Job drift hằng ngày chạy detector (ML-19, ML-20), xuất metric và cảnh báo | Metric PSI/AUC thực tế có trong Prometheus; cảnh báo khi vượt ngưỡng | 3 | ML-19, ML-20 | US-065 |
| ☐ | MLO-05 | Cảnh báo drift và AUC thực tế (Alertmanager) + panel dashboard MLOps | Cảnh báo tới đúng kênh; dashboard có PSI, AUC thực tế, phiên bản model, lịch sử huấn luyện lại | 3 | ML-10, OBS-01 | US-065 |
| ☐ | WEB-33 | Màn hình duyệt trích xuất: tô trường trên tài liệu gốc, sửa, chấp nhận/từ chối | Trường độ tin cậy thấp được đánh dấu | 4 | WEB-25, AI-26 | US-059, US-060 |
| ☐ | WEB-34 | Widget chatbot đặt lịch có bước xác nhận (Could) | Hiển thị fallback form khi 503 | 4 | WEB-04, AI-31 | US-062 |

## Tuần 10 — 26 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-28 | Eval F1 mức trường và phân loại + báo cáo | F1 ≥ 0,85 hoặc giải thích | 3 | AI-27 | US-061 |
| ☐ | ENV-06 | Diễn tập dựng prod ephemeral lần 1: đo thời gian dựng/hủy, seed dữ liệu, kịch bản dự phòng nếu dựng trễ | Có số đo thời gian thực tế; có bản dự phòng (video/ảnh chụp/UAT); prod được hủy sạch sau diễn tập | 4 | ENV-01, ENV-02, ENV-05 | US-070 |
| ☐ | PRD-01 | Load test 1x/5x/10x + báo cáo | Chạy trên UAT ephemeral (cỡ prod); so với NFR-P/S | 6 | PRD-10, ENV-01 | US-068 |
| ☐ | PRD-02 | Failure test | Kill pod, DB failover, provider SMS lỗi, LLM lỗi, consumer crash; ghi kết quả | 6 | ENV-01 | US-069 |
| ☐ | PRD-03 | Diễn tập backup/restore | PITR Postgres + restore Mongo; ghi thời gian thực tế so với RTO | 4 | ENV-01 | US-067 |
| ☐ | PRD-04 | Dashboard SLO cuối cùng | Error budget theo NFR-A1 | 3 | OBS-02 | ENABLER |

## Tuần 11 — 11 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | AI-33 | Báo cáo token và chi phí theo tác vụ/model | Số liệu từ AI10; so với NFR-C2 | 3 | AI-13, PRD-01 | US-055 |
| ☐ | PRD-05 | Báo cáo FinOps | Chi phí theo giờ-môi trường, dự phóng tháng, khuyến nghị | 4 | ENV-03 | ENABLER |
| ☐ | PRD-06 | Rà soát bảo mật | Checklist theo threat model; kết quả quét; ma trận phân quyền xanh | 4 | ARC-08 | ENABLER |

## Tuần 12 — 9 giờ

| ✓ | ID | Việc | Xong khi | Giờ | Phụ thuộc | Story |
|---|---|---|---|---|---|---|
| ☐ | PRD-08 | Kịch bản demo + slide bảo vệ | Trả lời 12 câu hỏi review của đề; demo end-to-end | 6 | PRD-01..PRD-07 | ENABLER |
| ☐ | PRD-09 | Diễn tập demo trên prod ephemeral | Dựng prod, chạy kịch bản, hủy; có timing | 3 | PRD-08 | ENABLER |
