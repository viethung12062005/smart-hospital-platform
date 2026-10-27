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
3. **Thay đổi (commit 4bf82ef; xem bằng `git show --stat 4bf82ef`):**

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

## Phần D1b — Cập nhật kế hoạch: chuyển SHR-22/23/24 sang SA và thêm theo dõi trạng thái task ✅
1. **Mục tiêu:** kế hoạch phản ánh đúng ai làm gì sau D1; có chỗ ghi task nào đã xong (trước đây checkbox luôn `☐`).
2. **Việc đã làm:** (1) cắt SHR-22/23/24 khỏi `tasks_dev.py`, thêm vào `tasks_sa.py` với owner `SA` (giữ ID, giờ 3/4/3, phụ thuộc); (2) thêm `tools/plan/status.py` (ID → `done|partial`, commit, ghi chú); (3) sửa `build.py` vẽ `☑/◐/☐` và tổng số xong trong `docs/05-plan/by-person/*.md`, báo lỗi nếu `status.py` nhắc task không tồn tại; (4) sinh lại tài liệu.
3. **Thay đổi (commit 23bc32a):**

| File | | Nội dung |
|---|---|---|
| `tools/plan/tasks_dev.py` | M | xóa 3 tuple SHR-22/23/24 (owner `D1`) |
| `tools/plan/tasks_sa.py` | M | thêm 3 tuple đó với owner `SA` ở cuối danh sách |
| `tools/plan/status.py` | A | 7 mục: ARC-06 (một phần), ARC-07, ARC-19, ARC-20 (một phần), SHR-22/23/24 |
| `tools/plan/build.py` | M | import `STATUS`; hàm `mark`, `status_note`; dòng tổng "Đã xong/làm một phần"; kiểm status hợp lệ |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **sinh lại** |

   Tải sau khi đổi (giờ/tuần): SA 375h tổng (W2: 50, W3: 50), D1 202h (từ 212h). Các kiểm tra của `build.py` (thứ tự phụ thuộc, ≤ 4h/task dev, ≤ 8h/task SA, ≤ 30h/tuần dev) vẫn qua.
4. **Lệnh làm lại / kiểm chứng:** `python3 -I tools/plan/build.py` → `OK: 74 stories, 317 tasks, 1070h`; mở `docs/05-plan/by-person/SA.md` thấy `☑ SHR-22`.
5. **Quy ước từ nay:** khi xong một task, thêm một dòng vào `tools/plan/status.py` (kèm commit) rồi chạy lại `build.py`, **cùng commit** với phần việc.
6. **Lưu ý trung thực:** ARC-06 và ARC-20 chỉ `partial` (OpenAPI chưa có schema thân; ICD-10 mới 12/~300 mã). Các task SA khác đã có sản phẩm từ trước (IaC, workflow, `dev/`) **chưa được đối chiếu và ghi vào status.py**; việc đối chiếu là một bước riêng (D-recon) để không ghi sai.

## Phần D2 — Thư viện .NET dùng chung: lỗi, xác thực JWT, tra danh tính (SHR-02, SHR-20, SHR-21) ✅ / SHR-20 một phần
1. **Mục tiêu:** mọi service trả lỗi cùng một định dạng, xác thực token cùng một cách (Entra ID cloud, Keycloak local) và tra `fhirUser` theo `sub` có cache 60 giây (AC-08.1) mà dev không phải tự viết.
2. **Việc đã làm (theo thứ tự):**
   1. Thêm 2 gói vào `Directory.Packages.props`: `Microsoft.AspNetCore.Authentication.JwtBearer 10.0.14`, `Microsoft.AspNetCore.TestHost 10.0.14`.
   2. Tạo `libs/dotnet/Shp.Web` (tham chiếu `Shp.Authorization`): `Errors/` (danh mục lỗi, ngoại lệ, bộ ghi lỗi, middleware) và `Auth/` (cấu hình, JWT, resolver, middleware danh tính).
   3. Tạo `libs/dotnet/Shp.Web.Tests` (19 test) dùng TestServer + token ký cục bộ (không cần Keycloak/Entra).
   4. Thêm 2 project vào `libs/dotnet/Shp.Libs.slnx`.
   5. Chuyển SHR-02/20/21 sang SA, ghi trạng thái, đổi cách tính tải tuần (task đã xong không tính giờ).
3. **Thay đổi (commit 55c56a5):**

| File | | Nội dung |
|---|---|---|
| `Directory.Packages.props` | M | + JwtBearer, TestHost (10.0.14) |
| `libs/dotnet/Shp.Web/Errors/ErrorCatalog.cs` | A | 27 mã lỗi: HTTP, loại issue FHIR, thông điệp vi/en; bảng đối chiếu với `07-api-conventions.md §5` |
| `libs/dotnet/Shp.Web/Errors/ShpException.cs` | A | ném lỗi có mã; mã chưa khai báo → `ArgumentException` ngay khi tạo; `RetryAfter` tùy chọn |
| `libs/dotnet/Shp.Web/Errors/ErrorWriter.cs` | A | `/fhir/*` → `OperationOutcome` (`application/fhir+json`); còn lại → Problem Details có `code`; `X-Request-Id` = trace id; `Retry-After` cho 429/503; ngôn ngữ theo `Accept-Language` |
| `libs/dotnet/Shp.Web/Errors/ErrorHandlingMiddleware.cs` | A | `UseShpErrors()`; lỗi lạ → 500 `internal-error` **không lộ nội dung exception** |
| `libs/dotnet/Shp.Web/Auth/ShpAuthOptions.cs` | A | `Auth:Authority`, `Auth:Audience` (hoặc env `OIDC_AUTHORITY`, `OIDC_AUDIENCE`), `RequireHttpsMetadata`, `RequireMfaForStaff`, `ClockSkew` |
| `libs/dotnet/Shp.Web/Auth/AuthExtensions.cs` | A | `AddShpAuthentication`: kiểm chữ ký, issuer, audience, hạn; không đổi tên claim; MFA (`amr` chứa `mfa`) bắt buộc với doctor/nurse/admin/dpo (→ 401); challenge/forbidden trả đúng định dạng lỗi |
| `libs/dotnet/Shp.Web/Auth/Roles.cs` | A | 5 role hợp lệ + nhóm nhân viên |
| `libs/dotnet/Shp.Web/Auth/IdentityResolver.cs` | A | `IIdentityLinkClient`, `IdentityResolver` (link giữ 60s, "chưa liên kết" giữ 5s), `HttpIdentityLinkClient` gọi `GET /internal/v1/identity/{subject}` |
| `libs/dotnet/Shp.Web/Auth/IdentityMiddleware.cs` | A | `UseShpIdentity()`: thiếu role hợp lệ → 403; chưa liên kết hoặc bị khóa → 403 (trừ endpoint `[AllowUnlinkedIdentity]` cho đăng ký, nhưng tài khoản bị khóa vẫn 403); tạo `Principal` cho `AuthorizationEngine`; `HttpContext.GetShpPrincipal()` |
| `libs/dotnet/Shp.Web.Tests/*` | A | 19 test |
| `libs/dotnet/Shp.Libs.slnx` | M | + Shp.Web, Shp.Web.Tests |
| `tools/plan/tasks_dev.py`, `tasks_sa.py`, `status.py`, `build.py` | M | SHR-02/20/21 → SA; trạng thái; tải tuần chỉ tính task chưa xong |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **sinh lại** |

4. **Cách dùng cho dev** (trong `Program.cs` của service, theo thứ tự):
```csharp
builder.Services.AddShpAuthentication(builder.Configuration);
builder.Services.AddShpIdentity<TClient>();           // clinical: client đọc DB; service khác: HttpIdentityLinkClient
...
app.UseShpErrors(); app.UseRouting(); app.UseAuthentication(); app.UseShpIdentity(); app.UseAuthorization();
// trong endpoint: var principal = ctx.GetShpPrincipal(); engine.Decide(principal, "Appointment", "cancel", context);
// lỗi nghiệp vụ: throw new ShpException("slot-unavailable");
```
5. **Kiểm chứng:**
```bash
dotnet test libs/dotnet/Shp.Libs.slnx -c Release         # mong đợi: Shp.Web.Tests 19 passed; Shp.Authorization.Tests 236 passed
cd libs/dotnet && dotnet format --verify-no-changes       # không in gì
```
   **Kiểm chứng bằng đột biến (đã làm, cả 5 đều bị test bắt):** bỏ kiểm MFA; bỏ kiểm audience; cho phép tài khoản chưa liên kết ở mọi endpoint; bỏ qua cờ `Disabled`; đổi TTL cache 60s → 600s.
6. **Lỗi gặp và cách sửa:** `JsonSerializer.SerializeAsync` cần thêm `JsonSerializerOptions.Default` trước `CancellationToken`; analyzer CA1018 yêu cầu `[AttributeUsage]`; xunit 2.9.3 dùng `Task` (không phải `ValueTask`) cho `IAsyncLifetime`; lần đầu mutation "bỏ MFA" gây lỗi biên dịch (`if (false)`) nên không hợp lệ, làm lại bằng `hasMfa = true`.
7. **Giới hạn đã biết (đừng bỏ qua):**
   - **SHR-20 mới `partial`:** chưa chạy với Keycloak/Entra thật. Cần thử với `make -C dev up` + `make -C dev token` (việc kế tiếp D-int).
   - Chưa nối thư viện vào 6 service .NET (khung hiện chỉ dùng `Shp.ServiceDefaults`); bước nối cần sửa Dockerfile để `COPY contracts/authz` và `libs/dotnet` đủ.
   - Endpoint `GET /internal/v1/identity/{subject}` phía clinical là task của dev; token workload identity cho `HttpIdentityLinkClient` chưa có.
   - Tải kế hoạch: sau khi chuyển các task đã xong, D1 chỉ còn 11h ở W2 và 10h ở W3; người lập lịch có thể kéo việc sớm hơn nếu phụ thuộc cho phép.

## Phần D3 — Thư viện .NET dùng chung: CloudEvents, audit, filter phân quyền gắn endpoint (SHR-06, SHR-09, SHR-25, SHR-27)
1. **Mục tiêu:** (a) mọi event dùng cùng một envelope và được kiểm theo schema trong `contracts/events`; (b) mọi truy cập dữ liệu bệnh nhân, **kể cả bị từ chối**, sinh bản ghi audit đúng quy tắc (không PII, IP bị che); (c) dev gắn phân quyền vào endpoint bằng **một dòng**, và quên phân quyền làm test đỏ.
2. **Việc đã làm (theo thứ tự):**
   1. Thêm `JsonSchema.Net 7.3.4` vào `Directory.Packages.props` (đã thử khôi phục gói trước khi dùng).
   2. Tạo `libs/dotnet/Shp.Events` (nhúng toàn bộ schema trong `contracts/events/**`, trừ file ví dụ): `CloudEvent`, `CloudEvents.Create`, `EventSchemas.Validate`, thư mục `Audit/`.
   3. Tạo `libs/dotnet/Shp.Events.Tests` (42 test).
   4. Mở rộng `Shp.Web` (tham chiếu thêm `Shp.Events`, `Shp.ServiceDefaults`): `Auth/AuthzEndpoint.cs`.
   5. Mở rộng `Shp.Web.Tests` (29 test, thêm 10) và `Shp.Libs.slnx`.
   6. Chuyển SHR-06/09/25/27 sang SA, ghi trạng thái.
3. **Thay đổi (commit c81d3ec):**

| File | | Nội dung |
|---|---|---|
| `Directory.Packages.props` | M | + `JsonSchema.Net 7.3.4` |
| `libs/dotnet/Shp.Events/Shp.Events.csproj` | A | nhúng `contracts/events/**/*.json` (LogicalName `events/<ctx>/<event>.v1.json`) |
| `libs/dotnet/Shp.Events/CloudEvent.cs` | A | envelope; `CloudEvents.Create(service, type, subject, data)`: id UUIDv7, `source=/services/<service>`, `dataschema` suy từ type, `traceparent` từ Activity, giới hạn 64 KB, bỏ trường null |
| `libs/dotnet/Shp.Events/EventSchemas.cs` | A | `Validate(evt)`: kiểm envelope + data (có kiểm `format`); type lạ là lỗi; `KnownTypes` |
| `libs/dotnet/Shp.Events/Audit/AuditRecord.cs` | A | bản ghi E11, hằng số action/outcome; `ToCloudEvent()` |
| `libs/dotnet/Shp.Events/Audit/AuditRecordBuilder.cs` | A | che IP (IPv4 `a.b.c.x`, IPv6 3 nhóm đầu), rút gọn user-agent (120), `reason` ≤ 200, **`resource` chỉ cho loại/id/TÊN tham số** (giá trị tìm kiếm bị từ chối) |
| `libs/dotnet/Shp.Events/Audit/IAuditSink.cs` | A | interface + `InMemoryAuditSink` (production sẽ là sink ghi outbox, SHR-07) |
| `libs/dotnet/Shp.Events/Audit/AuthzAudit.cs` | A | `FromDecision`: ánh xạ hành động ma trận → hành động audit (19 hành động); outcome success/denied/error; role đại diện ưu tiên dpo>admin>doctor>nurse>patient |
| `libs/dotnet/Shp.Web/Auth/AuthzEndpoint.cs` | A | `AddShpAuthorization()`; `RequireShpAuthz(resource, action, contextFactory?, denyAsNotFound?)`; `GetShpDecision()` (để áp `Fields`/`Hide`); `FindUnprotectedEndpoints()`; `[ShpPublicEndpoint]` |
| `libs/dotnet/Shp.Web/Shp.Web.csproj` | M | + tham chiếu Shp.Events, Shp.ServiceDefaults |
| `libs/dotnet/Shp.Events.Tests/*`, `libs/dotnet/Shp.Web.Tests/*`, `libs/dotnet/Shp.Libs.slnx` | A/M | test mới |
| `tools/plan/*`, `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | SHR-06/09/25/27 → SA; trạng thái; sinh lại |

4. **Hành vi của filter (để dev biết không phải tự làm):** từ chối → ghi audit `denied` + lý do → 403 `forbidden` / `no-care-relationship` (hoặc 404 nếu `denyAsNotFound`); cho phép → chạy handler rồi ghi audit `success` (kể cả khi handler ném lỗi nghiệp vụ 409/422); lỗi bất ngờ → audit `error` rồi 500 không lộ chi tiết; chưa đăng nhập → 401, không audit.
5. **Dev dùng:**
```csharp
builder.Services.AddShpAuthorization();                       // + AddSingleton<IAuditSink, ...>() do service chọn
app.MapPost("/fhir/Appointment/{id}/$cancel", Cancel).RequireShpAuthz("Appointment", "cancel", LoadAppointmentFacts);
// test của service:  Assert.Empty(factory.Services.FindUnprotectedEndpoints());
// test hợp đồng producer: Assert.Empty(EventSchemas.Validate(CloudEvents.Create("scheduling", "shp.scheduling.appointment-booked.v1", subject, payload)));
```
6. **Kiểm chứng:**
```bash
dotnet test libs/dotnet/Shp.Libs.slnx -c Release     # Shp.Authorization 236, Shp.Events 42, Shp.Web 29: tất cả passed
cd libs/dotnet && dotnet format --verify-no-changes    # không in gì
```
   **Đột biến (đã làm, cả 5 bị bắt):** bỏ audit khi từ chối; luôn trả `forbidden` (mất mã `no-care-relationship`); đưa giá trị query vào `resource` audit; tắt kiểm `format` của schema; tắt che IP. Một lần đột biến đầu bị lỗi biên dịch nên không hợp lệ, đã làm lại.
7. **Lỗi gặp và cách sửa:** (a) **kế hoạch bị lỗi sau khi chuyển task** — `build.py` báo `MLO-04 (W8) scheduled before dependency ML-23 (W10)`: task SA bị cố định theo sprint nên không lùi theo task dev đã bị xếp muộn hơn; lúc đó mình đã lỡ commit (`c81d3ec`, `ff09115`) mà chưa kiểm kết quả nên các file sinh trong `docs/05-plan` của hai commit đó **chưa cập nhật**; đã sửa ở bước D3b ngay bên dưới. Bài học: chạy `python3 -I tools/plan/build.py` và dừng nếu in `validation error` **trước** khi commit. (b) `TheoryData` không liệt kê trực tiếp được → tách hàm trả mảng đường dẫn; LogicalName của resource nhúng dùng `%(RecursiveDir)` nên chuẩn hóa dấu `\` thành `/` cho Windows.
8. **Giới hạn đã biết:**
   - SHR-09 `partial`: chưa có sink ghi outbox (SHR-07, bước kế tiếp) nên audit chưa được lưu bền; chưa có middleware audit riêng cho đọc/tìm kiếm FHIR ngoài `RequireShpAuthz`.
   - IP lấy từ `RemoteIpAddress`: sau gateway cần cấu hình `ForwardedHeaders` (việc nối vào service).
   - Chưa nối thư viện vào 6 service .NET.

## Phần D3b — Sửa bộ xếp lịch của kế hoạch: task SA cũng phải tôn trọng phụ thuộc ✅
1. **Mục tiêu:** `build.py` chạy lại không lỗi sau khi nhiều task được chuyển giữa dev và SA.
2. **Nguyên nhân:** `schedule()` cố định tuần của mọi task SA theo sprint, chỉ xếp lại task dev. Khi task dev bị lùi (vì D1/D2/D3 nhẹ/nặng đổi), task SA phụ thuộc vào chúng (MLO-04 phụ thuộc ML-23) rơi vào tuần trước phụ thuộc.
3. **Thay đổi (commit 483f2b5):**

| File | | Nội dung |
|---|---|---|
| `tools/plan/build.py` | M | `schedule()`: mọi task đi vào cùng vòng xếp lịch; task SA bắt đầu ở tuần sprint hoặc khi phụ thuộc cho phép (lấy muộn hơn), **không giới hạn giờ**; SA được phép tới W12 (tuần bảo vệ), dev tới W11; thông báo lỗi nêu đúng giới hạn |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **sinh lại** (gồm cả phần bị bỏ sót ở D3) |

4. **Kiểm chứng:** `python3 -I tools/plan/build.py` → `OK: 74 stories, 317 tasks, 1070h`. Tải còn lại: SA 364h (W4 = 52h, vượt ngưỡng cảnh báo 45h, được in đậm), D1 190h, D2 225h, D3 258h.
5. **Hệ quả cần lưu ý:** D1 chỉ còn 11h ở W2 và 4h ở W3 (nhiều task nền của D1 đã được SA làm sẵn); lịch dev bắt đầu thực chất từ W4. Có thể cho D1 nhận thêm việc sớm hoặc giữ làm dự phòng (quyết định của PM).

## Phần D4 — Thư viện .NET dùng chung cho PostgreSQL: outbox, inbox, Idempotency-Key (SHR-07/08/28) — một phần
1. **Mục tiêu:** event luôn được ghi **cùng transaction** với thay đổi dữ liệu và không bao giờ mất khi bus lỗi; consumer xử lý **đúng một lần** dù bus giao nhiều lần; gửi lại cùng `Idempotency-Key` không tạo trùng. Bản PostgreSQL dùng cho scheduling (theo ADR-0014 đề xuất là service duy nhất còn dùng PostgreSQL).
2. **Việc đã làm (theo thứ tự):**
   1. Thử khôi phục gói trước khi dùng: `Npgsql 10.0.3`, `Testcontainers.PostgreSql 4.16.0` (bản 4.6.0 kéo `SSH.NET` có cảnh báo lỗ hổng NU1903 và sẽ làm build đỏ vì cảnh báo = lỗi, nên chọn 4.16.0, đã xác nhận không cảnh báo).
   2. Tạo `libs/dotnet/Shp.Data.Postgres`: DDL nhúng, `PostgresOutbox`, `OutboxRelay`, `PostgresInbox`, `PostgresIdempotency`, đăng ký DI.
   3. Tạo `libs/dotnet/Shp.Data.Postgres.Tests` chạy trên **PostgreSQL 16 thật** bằng Testcontainers (25 test).
   4. Thêm 2 project vào `Shp.Libs.slnx`; chuyển SHR-07/08/28 sang SA và ghi trạng thái.
3. **Thay đổi (commit COMMIT_D4):**

| File | | Nội dung |
|---|---|---|
| `Directory.Packages.props` | M | + `Npgsql 10.0.3`, `Testcontainers.PostgreSql 4.16.0` |
| `libs/dotnet/Shp.Data.Postgres/Sql/messaging.sql` | A | DDL `outbox`, `inbox`, `idempotency_key` (khớp `02-db-scheduling.md`, chạy lại được) |
| `.../MessagingSchema.cs` | A | đọc DDL nhúng; `ApplyAsync` |
| `.../PostgresOutbox.cs` | A | `AddAsync(tx, evt, validate)` ghi vào outbox trong transaction của người gọi; `validate:true` kiểm schema |
| `.../OutboxRelay.cs` | A | `IEventPublisher`; `OutboxRelay` (BackgroundService): khóa ≤100 dòng `FOR UPDATE SKIP LOCKED`, phát theo thứ tự, đánh dấu `published_at`; bus lỗi → ghi `attempts`/`last_error` (không ghi payload), **dừng lượt để giữ thứ tự**, backoff tối đa 30 giây; metric `shp_outbox_published_total`, `shp_outbox_publish_failures_total` |
| `.../PostgresInbox.cs` | A | `TryBeginAsync`, `HandleOnceAsync(consumer, evt, handler)`: ghi dấu inbox + chạy handler + commit trong một transaction; handler lỗi → rollback cả dấu |
| `.../PostgresIdempotency.cs` | A | `RequireKey` (thiếu → 428, sai → 400), `HashBody` (JSON chuẩn hóa: sắp xếp thuộc tính, bỏ khoảng trắng), `ClaimAsync` (xem dưới) |
| `.../DependencyInjection.cs` | A | `AddShpOutboxRelay()` |
| `libs/dotnet/Shp.Data.Postgres.Tests/*` | A | fixture Testcontainers + 25 test |
| `libs/dotnet/Shp.Libs.slnx`, `tools/plan/*`, `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | project mới; SHR-07/08/28 → SA; sinh lại kế hoạch |

4. **Hành vi `ClaimAsync` (để dev biết không phải tự làm):** lần đầu → ghi dòng, cấp sẵn `ResourceId` (UUIDv7); cùng key + cùng thao tác + cùng body → `IsReplay=true` cùng `ResourceId` (service đọc lại kết quả cũ, không xử lý lại); khác body/thao tác → 422 `idempotency-key-reused`; 20 request song song cùng key → đúng 1 request mới; claim rollback → claim lại được; quá 24 giờ → coi như mới; key tách theo người gọi.
5. **Kiểm chứng:**
```bash
# cần Docker. Nếu Docker Hub giới hạn lượt kéo: export SHP_TEST_POSTGRES_IMAGE=mirror.gcr.io/library/postgres:16-alpine
dotnet test libs/dotnet/Shp.Libs.slnx -c Release      # Shp.Data.Postgres.Tests: 25 passed (cùng 236 + 42 + 29 của các thư viện khác)
cd libs/dotnet && dotnet format --verify-no-changes
```
   **Đột biến (đã làm, cả 3 bị bắt):** bỏ 422 khi khác body; bỏ `SKIP LOCKED` (4 replica phát trùng); inbox ghi đè thay vì bỏ qua.
   Các kịch bản được kiểm trên DB thật: rollback giữ nguyên cả dữ liệu lẫn outbox; thứ tự theo thời gian; bus chết giữa chừng rồi sống lại vẫn phát đủ và đúng thứ tự (1 phát, 1 lỗi có `attempts=1`, 1 chưa bị chạm); 4 relay × 60 event → mỗi event đúng 1 lần; 20 giao trùng → handler chạy đúng 1 lần.
6. **Lỗi gặp và cách sửa:** analyzer CA1711 cấm tên lớp kết thúc bằng `Collection` → đổi `PostgresTestGroup`; sau khi **phiên làm việc khởi động lại**, Docker daemon không còn chạy và để lại file pid cũ → xóa `/var/run/docker.pid` rồi bật lại `dockerd`, chạy lại toàn bộ test; Testcontainers cần `TESTCONTAINERS_RYUK_DISABLED=true` trong môi trường dựng này (không kéo được image Ryuk).
7. **Giới hạn đã biết:** SHR-07 `partial` (chưa có bản MongoDB, chưa có `IEventPublisher` thật); SHR-08 `partial` (chưa có MassTransit RabbitMQ/Service Bus, retry/DLQ, bản MongoDB). Thứ tự giữa nhiều replica relay chỉ đảm bảo trong từng lượt; consumer phải bỏ qua event có `version` cũ hơn (đã nêu ở 13-events §1). **Nếu ADR-0014 được duyệt, bản MongoDB trở thành bản dùng nhiều nhất (5 service).**

## Phần D4b — Đề xuất ADR-0014: rà soát CSDL từng service (CHƯA áp dụng, chờ duyệt)
1. **Mục tiêu:** trả lời nhận xét của PM "lúc nào cũng PostgreSQL, phải tối ưu DB" bằng số liệu tải thật, không đổi engine theo cảm tính.
2. **Việc đã làm:** đọc `workload-analysis.md` và ADR-0008; lập bảng khối lượng thực (notification ~2.000 nhắc/ngày, ml ~900 dự đoán/ngày và ~330k fact/năm); đánh giá từng service với các phương án thay thế; viết ADR; thống kê tệp bị ảnh hưởng.
3. **Thay đổi (commit COMMIT_D4):**

| File | | Nội dung |
|---|---|---|
| `docs/03-architecture/adr/0014-database-per-service-review.md` | A | **Proposed**: scheduling giữ PostgreSQL (ràng buộc DB chống đặt trùng, đã kiểm 50 lượt đồng thời); notification → MongoDB (document + lấy việc nguyên tử `findOneAndUpdate` + TTL); ml → MongoDB (trạng thái đặc trưng + nhật ký dự đoán) + Parquet trên Blob (tập huấn luyện bất biến); bảng tối ưu từng engine và cách đo; rủi ro (time-series collection trên Cosmos vCore chưa kiểm) |
| `docs/03-architecture/adr/README.md` | M | thêm dòng 0014 (Proposed) |

4. **Cố ý CHƯA làm:** chưa sửa `04-db-notification-ml.md`, DDL, chart local, task NTF/ML/IAC cho tới khi PM duyệt (tránh viết lại thiết kế dựa trên đề xuất chưa chốt).
5. **Việc cần PM quyết:** duyệt/sửa ADR-0014, đặc biệt (a) notification → MongoDB hay Redis; (b) ml → MongoDB + Parquet; (c) chấp nhận kiểm khả dụng time-series collection trên Cosmos DB vCore trước khi chốt.

## Phần D — Kế tiếp (theo thứ tự)
| # | Bước | Ai | Trạng thái |
|---|---|---|---|
| D1 | Thư viện dùng chung `libs/dotnet`: **phân quyền ✅ (D1), lỗi + JWT + identity ✅ (D2)**; **CloudEvents + audit + filter phân quyền ✅ (D3)**; còn: outbox/inbox + MassTransit, Idempotency-Key, FHIR helper, fixture Testcontainers | SA | đang làm |
| D2 | Thư viện Python (`libs/python`): LLM router (DeepSeek → Gemini → Ollama, redact PII), shared feature package cho ML | SA | ⏳ |
| D3 | Repo GitOps: Argo CD bootstrap, AppProject, ApplicationSet, add-on, Kyverno, `envs/{uat,prod}` | SA | ⏳ |
| D4 | ENV-01: cài Argo CD trong workflow `env-provision` | SA | ⏳ |
| D5 | Module IaC dữ liệu (IAC-09..16), node pool `ai` cho Ollama + secret key LLM | SA | ⏳ |
| D6 | Profile local `full` (OpenSearch, Ollama, ClamAV, MLflow, observability), realm MFA | SA | ⏳ |
| D7 | Cài GitHub (ruleset, CODEOWNERS, Environments, GitHub App, Variables) theo `github-setup.md` | 👤 bạn | ⏳ |
| D8 | Bootstrap Azure một lần, điền GitHub Variables | 👤 bạn | ⏳ |
| D9 | Xoay key bị lộ ở caremate + xóa lịch sử | 👤 bạn | ⏳ |
| D10 | Chuyển repo templates/infra sang private | 👤 bạn | đang làm |
