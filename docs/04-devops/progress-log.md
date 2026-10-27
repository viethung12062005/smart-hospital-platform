# Nhật ký tiến trình Pha 4 — từng bước theo thứ tự, để kiểm tra và tự làm lại

> Cách dùng: đọc từ trên xuống. Mỗi bước có **mục đích → việc làm → lệnh → kiểm chứng → kết quả (commit)**.
> Muốn làm lại một bước: chạy phần "Lệnh", rồi chạy phần "Kiểm chứng"; kết quả phải giống phần "Kết quả".
> Tổng quan trạng thái và việc còn lại: [phase4-build-log.md](phase4-build-log.md). File này là bản chi tiết theo thời gian, **cập nhật cuối mỗi bước**.
> Ký hiệu trạng thái: ✅ xong và đã kiểm chứng · 🟡 xong nhưng chưa chạy được ở môi trường thật · ⏳ chưa làm · 👤 cần bạn làm.
> Chuẩn bị chung để chạy lệnh: Python 3.12+, .NET SDK 10, Docker, `pip install -r tools/contracts/requirements.txt`.

## Mẫu ghi mỗi bước (bắt buộc cho mọi bước từ nay)
Mỗi bước phải có đủ 7 mục, để truy vết và làm lại được:
1. **Mục tiêu**: vì sao làm.
2. **Việc đã làm**: các hành động theo thứ tự.
3. **Thay đổi**: bảng `File | A/M/D | Nội dung thay đổi` (A thêm, M sửa, D xóa). Mô tả **giá trị cụ thể đổi từ gì sang gì** với file sửa.
4. **Lệnh làm lại**.
5. **Kiểm chứng** và kết quả mong đợi.
6. **Lỗi gặp và cách sửa** (nếu có).
7. **Commit** và lệnh xem đúng thay đổi: `git show --stat <commit>` (tổng quan), `git show <commit> -- <file>` (chi tiết một file).

## Phần A — Nền tảng đã dựng trước đó (chi tiết xem build-log)
| # | Bước | Trạng thái | Commit |
|---|---|---|---|
| A1 | Pha 1–3: BA, yêu cầu, 74 user story, thiết kế chi tiết, 317 task theo người (`docs/`, `tools/plan`) | ✅ | 3cace72, c378bfc, 4826847 |
| A2 | ADR-0011: 2 môi trường cloud + dev trên kind | ✅ | 3a72b78 |
| A3 | Bộ công cụ local `dev/` (kind + phụ thuộc + Keycloak) | 🟡 chưa chạy trên kind thật | 365405e |
| A4 | Repo templates: chart `microservice` + workflow CI/CD | ✅ kiểm tĩnh | c352248, ab2a514 |
| A5 | Repo infra: Terraform bootstrap/modules/uat/prod | ✅ validate, không apply | 3918ddc |
| A6 | Khung 8 service, pipeline từng service, `make quickstart`, hướng dẫn dev | ✅ / 🟡 quickstart | 20599dd, 157f448 |

## Phần B — Quyết định ngày 2026-10-27
| # | Quyết định | Nơi ghi | Commit |
|---|---|---|---|
| B1 | Blob local = Azurite (cùng Azure SDK với cloud); WORM kiểm trên UAT | ADR-0008/0011, task DOC-02, AUD-08, LOC-01 | 621de97 |
| B2 | ADR-0012 Accepted: giữ .NET + Python | `adr/0012-language-per-service.md` | 621de97 |
| B3 | ADR-0013: DeepSeek (API gốc) chính, Gemini free tier dự phòng, embedding bge-m3 local trong cluster, bỏ Claude và mọi model Azure | `adr/0013-*.md`, `06-data-ai.md`, `12-api-ai.md`, `14-sequences.md`, NFR-C2, task PY-07/AI-04/AI-06/AI-23/IAC-19 | eb38e94 |

**Thay đổi (commit 621de97 cho B1+B2; eb38e94 cho B3)** — xem bằng `git show --stat 621de97` và `git show --stat eb38e94`

| File | | Nội dung thay đổi |
|---|---|---|
| `tools/plan/tasks_sa.py` (LOC-01) | M | "RabbitMQ, MinIO, Keycloak" → "RabbitMQ, Azurite, Keycloak" |
| `tools/plan/tasks_dev.py` (DOC-02) | M | "Azure Blob (user delegation SAS) + MinIO" → "Azure Blob SDK một code cho local Azurite và cloud; chọn cách ký SAS qua cấu hình"; test "với MinIO" → "với Azurite (Testcontainers)" |
| `tools/plan/tasks_dev.py` (AUD-08) | M | "xanh trong CI (MinIO object lock)" → chuỗi hash kiểm trong CI với Azurite, WORM kiểm bằng smoke test trên UAT |
| `docs/03-architecture/adr/0008-…`, `0011-…` | M | bỏ MinIO; blob lifecycle "→ MinIO" → "Cold sau 180 ngày"; local dùng Azurite |
| `docs/03-architecture/adr/0012-…` | M | `Proposed` → `Accepted (2026-10-27)`; câu "SDK Azure OpenAI" → "client OpenAI-compatible/Gemini" |
| `docs/03-architecture/adr/0013-llm-deepseek-gemini-local-embedding.md` | A | ADR mới: bảng chuỗi model, redact PII bắt buộc, embedding local |
| `docs/03-architecture/adr/0006-…` | M | trạng thái ghi rõ bảng chuỗi model bị ADR-0013 thay |
| `docs/03-architecture/detailed-design/06-data-ai.md` | M | embedding `text-embedding-3-small` 1536 chiều → `bge-m3` 1024 chiều (`dimensions: 1536` → `1024`); bỏ khối `semantic` của AI Search; cấu hình router: azure-openai/anthropic → deepseek/gemini/ollama + `embedding` |
| `12-api-ai.md`, `14-sequences.md`, `overview.md` | M | tên provider/model trong ví dụ và sơ đồ |
| `docs/02-requirements/non-functional-requirements.md` | M | NFR-C2: ≤ 0,02 → ≤ 0,005 USD/request RAG, Gemini chỉ trong free tier |
| `tools/plan/tasks_dev.py`, `tasks_sa.py` | M | PY-07 (adapter DeepSeek/Gemini/Ollama), AI-04 (bge-m3), AI-06 (RRF, bỏ semantic ranker), AI-23 (Tesseract), IAC-19 (node pool `ai` + key LLM trong Key Vault, thay module Azure OpenAI) |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **sinh lại** bằng `python3 -I tools/plan/build.py` (không sửa tay) |

Làm lại B1–B3: sửa các file trên theo ADR, rồi `python3 -I tools/plan/build.py` (phải in `OK: 74 stories, 317 tasks`).

## Phần C — Hợp đồng chung (contracts) — dev và service dùng làm chuẩn
Mục đích: biến thiết kế (văn bản) thành **file máy đọc được + bộ kiểm tra tự động**, để service viết sai là CI đỏ.
Thứ tự làm và lý do: phân quyền → event → API → terminology (dev cần phân quyền và API trước nhất), cuối cùng gói tất cả vào một lệnh kiểm + CI.

### C1. Ma trận phân quyền `contracts/authz/matrix.yaml` ✅
- **Việc làm:** chuyển `access-control-matrix.md` + `15-authorization.md` thành 64 rule (resource × action × role × điều kiện `when` × purposeOfUse), khai báo 16 resource (đánh dấu `clinical`), 9 predicate, `denyAll` (Audit sửa/xóa).
- **Bất biến được kiểm tự động** (`tools/contracts/validate_authz.py`): id không trùng; role/predicate/purpose hợp lệ; mọi action khai báo đều có rule hoặc nằm trong `denyAll`; admin và dpo **không** có rule với dữ liệu lâm sàng; dpo chỉ có rule cho Audit; `breakglass` chỉ cho doctor; `own` chỉ cho patient.
- **Kiểm chứng:** `python3 -I tools/contracts/validate_authz.py` → `OK: 64 rules, 16 resources, 2 denyAll`; `python3 -I -m unittest discover -s tools/contracts -p 'test_*.py'` → 7 test (cố tình đưa rule sai vào và kiểm bộ kiểm tra bắt được).
- **Dev dùng:** task SHR-09 nạp file này để sinh test mọi ô ma trận; thư viện chính sách đọc cùng file.

### C2. Schema event `contracts/events/` ✅
- **Việc làm:** `tools/contracts/gen_events.py` chứa định nghĩa 11 event (E1–E11) và **sinh ra** `envelope.json`, `<context>/<event>.v1.json` (JSON Schema 2020-12) và `.example.json`. Sửa schema ở bộ sinh, chạy lại; không sửa tay file sinh.
- **Kiểm chứng:** `python3 -I tools/contracts/validate_events.py` → mọi schema hợp lệ, ví dụ khớp schema, ví dụ bọc trong envelope CloudEvents hợp lệ, **không có tên trường nghi PII**, tập event khớp danh mục trong `13-events.md`.
- **Lỗi gặp và cách sửa:** `modelName` bị cờ PII (khớp "name") → thêm vào danh sách ngoại lệ có chủ đích; enum kênh đặt lịch ban đầu có `mobile` trái thiết kế → sửa về `web|chatbot|staff` theo `01-domain-model.md`.

### C3. Khung OpenAPI `contracts/openapi/<service>.yaml` ✅
- **Việc làm:** `tools/contracts/gen_openapi.py` đọc các bảng endpoint trong `08..12-api-*.md` và sinh OpenAPI 3.1 cho 7 service: 83 operation (operationId = ID trong tài liệu như `S11`), quyền (`x-roles`, `x-permission`), tham số đường dẫn, lỗi chuẩn (FHIR `OperationOutcome` / `problem+json`), `/internal/*` gắn `x-internal`.
- **Giới hạn đã biết:** chưa có schema thân request/response; dev thêm khi làm task endpoint, lấy từ mục tương ứng trong tài liệu.
- **Sửa tài liệu thiết kế:** dòng AI9 trong `12-api-ai.md` viết gộp (`GET/POST/PUT ... , POST .../approve`) → tách thành 5 endpoint tường minh để máy đọc được (cùng nội dung).
- **Kiểm chứng:** `python3 -I tools/contracts/validate_openapi.py` → `OK: 83 operation, 7 service khớp tài liệu` (kiểm OpenAPI hợp lệ bằng `openapi-spec-validator`, đủ và không thừa endpoint).

### C4. Terminology `contracts/terminology/code-systems.yaml` ✅ (kèm lưu ý)
- 12 chuyên khoa, 6 lý do hủy, 3 kênh đặt lịch, 10 mã LOINC sinh hiệu, 6 loại tài liệu LOINC, 12 mã ICD-10 thường gặp.
- **Lưu ý trung thực:** mã LOINC/ICD-10 là tập con do SA đối chiếu theo kiến thức chuẩn, **chưa đối chiếu với bản chính thức**; đủ cho dữ liệu giả của đồ án.
- **Kiểm chứng:** `python3 -I tools/contracts/validate_terminology.py` → định dạng mã đúng, khớp `01-domain-model.md` §3 và ví dụ E8.

### C5. Gói kiểm và CI ✅ (CI chưa chạy trên GitHub)
- `tools/contracts/check_all.sh`: chạy test, 4 bộ kiểm, rồi sinh lại event/OpenAPI và `git diff --exit-code` (phát hiện file sinh bị sửa tay hoặc lệch bộ sinh).
- `.github/workflows/contracts.yml`: chạy `check_all.sh` khi `contracts/**`, `tools/contracts/**` hoặc thiết kế chi tiết đổi (action ghim theo SHA).
- **Kiểm chứng:** `tools/contracts/check_all.sh` → `ALL CONTRACT CHECKS PASSED`; `actionlint` sạch.

**Thay đổi cho C1–C5 (commit cd8c789)** — xem bằng `git show --stat cd8c789`

| File / thư mục | | Nội dung |
|---|---|---|
| `contracts/authz/matrix.yaml` | A | 64 rule, 16 resource, 9 predicate, 2 denyAll |
| `contracts/events/envelope.json` + `contracts/events/<ctx>/<event>.v1{,.example}.json` (23 file) | A | **sinh bởi** `gen_events.py` |
| `contracts/openapi/{scheduling,clinical,document,notification,audit,ml,ai}.yaml` | A | **sinh bởi** `gen_openapi.py` (83 operation) |
| `contracts/terminology/code-systems.yaml` | A | 6 code system, 49 mã |
| `tools/contracts/gen_events.py`, `gen_openapi.py` | A | bộ sinh (nguồn sự thật của file sinh) |
| `tools/contracts/validate_{authz,events,openapi,terminology}.py`, `test_validators.py`, `check_all.sh`, `requirements.txt` | A | bộ kiểm + test của bộ kiểm |
| `.github/workflows/contracts.yml` | A | CI cho hợp đồng, action ghim SHA |
| `docs/03-architecture/detailed-design/12-api-ai.md` | M | dòng AI9: `GET/POST/PUT /api/v1/ai/kb/documents`, `POST .../{id}/approve`, `POST .../{id}/retire` → 5 endpoint đầy đủ đường dẫn |
| `docs/04-devops/progress-log.md` | A | file này |
| `docs/04-devops/phase4-build-log.md` | M | thêm liên kết tới file này |

## Phần D1 — Thư viện .NET dùng chung: phân quyền (SHR-22, SHR-23, SHR-24) ✅
1. **Mục tiêu:** dev không phải tự viết logic phân quyền; mọi service dùng một engine đọc đúng `contracts/authz/matrix.yaml` (NFR-SEC2). Chọn làm phần này trước vì là logic thuần, kiểm thử được hoàn toàn và mọi endpoint lâm sàng đều cần.
2. **Việc đã làm (theo thứ tự):**
   1. Thêm `YamlDotNet 16.3.0` vào `Directory.Packages.props` (quản lý phiên bản tập trung).
   2. Tạo `libs/dotnet/Shp.Authorization`: `AuthzMatrix` (nạp YAML + kiểm bất biến an toàn giống `validate_authz.py`), `Model.cs` (`Principal`, `ResourceContext`, `AuthzDecision`, tên predicate), `AuthorizationEngine` (hàm thuần `Decide`).
   3. Nhúng `contracts/authz/matrix.yaml` vào assembly (`EmbeddedResource`), nên service luôn chạy đúng bản đã review.
   4. Tạo `libs/dotnet/Shp.Authorization.Tests` với test **sinh từ chính ma trận** (mỗi rule: cho phép khi đủ điều kiện, từ chối khi thiếu từng điều kiện; mọi cặp role không có rule bị từ chối kể cả khi mọi predicate thỏa).
   5. Tạo `libs/dotnet/Shp.Libs.slnx` gom các thư viện; thêm workflow `libs-dotnet.yml`.
3. **Thay đổi (commit: xem cuối mục):**

| File | | Nội dung |
|---|---|---|
| `Directory.Packages.props` | M | thêm `<PackageVersion Include="YamlDotNet" Version="16.3.0" />` |
| `libs/dotnet/Shp.Authorization/Shp.Authorization.csproj` | A | tham chiếu YamlDotNet; nhúng matrix.yaml với LogicalName `matrix.yaml` |
| `libs/dotnet/Shp.Authorization/AuthzMatrix.cs` | A | nạp + kiểm: id trùng, role/predicate/purpose lạ, denyAll, admin/dpo không chạm lâm sàng, dpo chỉ Audit, breakglass chỉ doctor, own chỉ patient, action không có rule |
| `libs/dotnet/Shp.Authorization/Model.cs` | A | `Principal(Subject, Roles, FhirUserId)`, `ResourceContext` (dữ kiện đã tra cứu), `AuthzDecision(Allowed, RuleId, Purpose, Fields, Hide, DenyReason)` |
| `libs/dotnet/Shp.Authorization/AuthorizationEngine.cs` | A | quy tắc: mặc định từ chối; rule đầu tiên theo thứ tự file thỏa thì Allow (nên care → purpose TREAT, đứng trước break-glass → ETREAT); thiếu dữ kiện = predicate không thỏa; `before-2h` tính bằng `TimeProvider`; lý do từ chối: `no-rule`, `no-care-relationship`, `unmet-<predicate>`, `role-not-allowed` |
| `libs/dotnet/Shp.Authorization.Tests/*` | A | 236 test |
| `libs/dotnet/Shp.Libs.slnx` | A | ServiceDefaults + Authorization + Tests |
| `.github/workflows/libs-dotnet.yml` | A | gọi `dotnet-ci` (format, build cảnh báo=lỗi, test, coverage ≥ 70%) |

4. **Cách dùng cho dev (task SHR-25 còn lại cho dev):** service tra cứu dữ kiện (quan hệ điều trị, break-glass, trạng thái encounter...) → dựng `ResourceContext` → `engine.Decide(principal, "Appointment", "cancel", ctx)`. Nếu `Allowed=false`: trả 403 và phát audit với `DenyReason`.
5. **Lệnh làm lại / kiểm chứng:**
```bash
dotnet test libs/dotnet/Shp.Libs.slnx -c Release        # mong đợi: Passed 236, Failed 0
cd libs/dotnet && dotnet format --verify-no-changes       # mong đợi: không in gì
```
6. **Kiểm chứng bằng đột biến (đã làm):** tạm sửa engine rồi chạy test: `own` luôn đúng → 10 test đỏ; cửa sổ hủy 2h thành 1h → 2 test đỏ. Đột biến thứ ba (predicate lạ → cho phép) **không bị bắt**, vì bộ nạp đã từ chối predicate lạ trước khi tới engine: đây là nhánh phòng thủ, không thể chạm tới qua ma trận hợp lệ.
7. **Lỗi gặp và cách sửa:** test `BG-START-DOC` (`not-care`) đỏ vì dựng ngữ cảnh sai (`care` và `not-care` loại trừ nhau) → thêm tham số `notCareFails` cho hàm dựng ngữ cảnh trong test; không sửa engine.
8. **Ảnh hưởng tới kế hoạch:** SHR-22/23/24 (do dev) đã được SA làm sẵn; dev chỉ còn SHR-25 (endpoint filter), SHR-26 (sinh test cho từng endpoint), SHR-27 (audit mỗi quyết định). Chưa đổi chủ sở hữu trong `tools/plan` (xem mục D-next).

## Phần D — Kế tiếp (theo thứ tự)
| # | Bước | Ai | Trạng thái |
|---|---|---|---|
| D1 | Thư viện dùng chung `libs/dotnet`: **phân quyền ✅ (xem Phần D1)**; còn: xác thực JWT + identity link, audit middleware, outbox/inbox + MassTransit, Idempotency-Key, FHIR helper, fixture Testcontainers | SA | đang làm |
| D2 | Thư viện Python (`libs/python`): LLM router (DeepSeek → Gemini → Ollama, redact PII), shared feature package cho ML | SA | ⏳ |
| D3 | Repo GitOps: Argo CD bootstrap, AppProject, ApplicationSet, add-on, Kyverno, `envs/{uat,prod}` | SA | ⏳ |
| D4 | ENV-01: cài Argo CD trong workflow `env-provision` | SA | ⏳ |
| D5 | Module IaC dữ liệu (IAC-09..16), node pool `ai` cho Ollama + secret key LLM | SA | ⏳ |
| D6 | Profile local `full` (OpenSearch, Ollama, ClamAV, MLflow, observability), realm MFA | SA | ⏳ |
| D7 | Cài GitHub (ruleset, CODEOWNERS, Environments, GitHub App, Variables) theo `github-setup.md` | 👤 bạn | ⏳ |
| D8 | Bootstrap Azure một lần, điền GitHub Variables | 👤 bạn | ⏳ |
| D9 | Xoay key bị lộ ở caremate + xóa lịch sử | 👤 bạn | ⏳ |
| D10 | Chuyển repo templates/infra sang private | 👤 bạn | đang làm |
