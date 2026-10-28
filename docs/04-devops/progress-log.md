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
3. **Thay đổi (commit b74e54a):**

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
3. **Thay đổi (commit b74e54a):**

| File | | Nội dung |
|---|---|---|
| `docs/03-architecture/adr/0014-database-per-service-review.md` | A | **Proposed**: scheduling giữ PostgreSQL (ràng buộc DB chống đặt trùng, đã kiểm 50 lượt đồng thời); notification → MongoDB (document + lấy việc nguyên tử `findOneAndUpdate` + TTL); ml → MongoDB (trạng thái đặc trưng + nhật ký dự đoán) + Parquet trên Blob (tập huấn luyện bất biến); bảng tối ưu từng engine và cách đo; rủi ro (time-series collection trên Cosmos vCore chưa kiểm) |
| `docs/03-architecture/adr/README.md` | M | thêm dòng 0014 (Proposed) |

4. **Cố ý CHƯA làm:** chưa sửa `04-db-notification-ml.md`, DDL, chart local, task NTF/ML/IAC cho tới khi PM duyệt (tránh viết lại thiết kế dựa trên đề xuất chưa chốt).
5. **Việc cần PM quyết:** duyệt/sửa ADR-0014, đặc biệt (a) notification → MongoDB hay Redis; (b) ml → MongoDB + Parquet; (c) chấp nhận kiểm khả dụng time-series collection trên Cosmos DB vCore trước khi chốt.

## Phần J — Chuyển toàn bộ code base sang Java (quyết định PM ngày 2026-10-28)
> PM: "chuyển toàn bộ code base sang Java… tôi duyệt mọi thiết kế bạn đề ra". Thứ tự làm: J1 quyết định + tài liệu → J2 Maven + thư viện nền đầu tiên → J3 → J4 (chuyển các thư viện .NET đã viết sang Java, giữ nguyên hành vi và test) → J5 (8 service, CI, Docker, bộ công cụ local; xóa .NET/Python).
> Phiên bản đã tra trực tiếp trên Maven Central ngày 2026-10-28 (không chọn theo trí nhớ): Spring Boot **4.1.2**, Spring AI **2.0.2**, HAPI FHIR **8.12.1**, Testcontainers **2.0.5**, networknt json-schema-validator **3.0.8**, CloudEvents SDK **5.1.0**, Flyway **13.11.1**, JaCoCo **0.8.16**, ArchUnit **1.5.1**, XGBoost4J **3.4.0**, DuckDB JDBC **1.5.6.0**, MLflow client **3.11.1**, Tess4J **5.20.0**, PostgreSQL JDBC **42.7.14**, MongoDB driver **5.14.0**. JDK trong môi trường dựng: **21.0.12**, Maven **3.9.11**.

### J1 — Quyết định Java, duyệt ADR-0014 và cập nhật tài liệu/kế hoạch ✅
1. **Mục tiêu:** ghi quyết định thành ADR, áp dụng ADR-0014 (PM đã duyệt) vào thiết kế chi tiết, và đổi mọi task/tài liệu đang ghi .NET/Python sang Java trước khi viết code.
2. **Việc đã làm (theo thứ tự):**
   1. Viết ADR-0015 (bảng ánh xạ từng công nghệ .NET/Python → Java); đánh dấu ADR-0002, ADR-0012 **Superseded**; ADR-0014 → **Accepted**; gạch dòng notification/ml trong ADR-0008.
   2. Sửa tài liệu kiến trúc còn nhắc .NET/Python/Firely/MassTransit/YARP/LightGBM/pandera (`overview.md`, ADR-0001/0003/0004/0007/0009, `13-events.md`, `16-mlops.md`, `04-devops/README.md`).
   3. **Viết lại `04-db-notification-ml.md` sang MongoDB** (ADR-0014): collection, document mẫu, index (unique chống trùng, partial cho lấy việc, TTL), thao tác lấy việc bằng `findOneAndUpdate`; ml = `patient_stats` + `appointment_facts` + `predictions` (time-series) + Parquet/DuckDB cho huấn luyện.
   4. Viết script khởi tạo `contracts/db/mongo-notification-ml.js` và bộ kiểm `contracts/db/mongo-notification-ml.test.js`; **chạy trên MongoDB 8 thật** (replica set): 12/12 kiểm tra qua, chạy script 2 lần không lỗi.
   5. Sửa kế hoạch: task .NET/Python → Java; các task PY-01/03/04/05 thu nhỏ thành "nối thư viện chung Java" (vì thư viện Java dùng chung đã làm phần đó); NTF-01/05 và ML-11 sang MongoDB; nhãn người D1/D2/D3 thành Dev Java.
3. **Thay đổi (commit 3b7390f):**

| File | | Nội dung |
|---|---|---|
| `docs/03-architecture/adr/0015-java-for-all-services.md` | A | Java 21 LTS + Spring Boot 4.1; bảng ánh xạ 14 hạng mục; hệ quả (một pipeline CI, chuyển 1-1 thư viện, công cụ SA giữ Python/Bash) |
| `docs/03-architecture/adr/0002-…`, `0012-…` | M | `Accepted` → `Superseded (2026-10-28) bởi ADR-0015` |
| `docs/03-architecture/adr/0014-…` | M | `Proposed` → `Accepted (2026-10-28)` |
| `docs/03-architecture/adr/0008-…` | M | dòng notification, ml bị gạch, trỏ sang ADR-0014 |
| `docs/03-architecture/adr/README.md` | M | trạng thái 0002/0012/0014; thêm 0015 |
| `docs/03-architecture/overview.md` | M | cột công nghệ 8 service → Java (gateway: Spring Cloud Gateway, ai: Spring AI, ml: XGBoost4J + DuckDB); DB notification/ml → MongoDB; bus: Spring Cloud Stream |
| `docs/03-architecture/adr/0001,0003,0004,0007,0009`, `detailed-design/13-events.md`, `16-mlops.md`, `docs/04-devops/README.md` | M | Firely → HAPI FHIR; MassTransit → Spring Cloud Stream; LightGBM → XGBoost4J; pandera → quy tắc Java; ".NET CI, Python CI" → "Java CI (Maven)" |
| `docs/03-architecture/detailed-design/04-db-notification-ml.md` | M | **viết lại toàn bộ**: PostgreSQL DDL → collection MongoDB (xem bước 2.3) |
| `contracts/db/mongo-notification-ml.js`, `contracts/db/mongo-notification-ml.test.js` | A | script khởi tạo idempotent + 12 kiểm tra |
| `tools/plan/tasks_dev.py` | M | SHR-01 (HAPI), PY-01 (3h→1h), PY-03 (4h→2h), PY-04, PY-05 (2h→1h), PY-07 (Spring AI), ML-04 (XGBoost4J), ML-14 (quy tắc Java), ML-11/NTF-01/NTF-05 (MongoDB) |
| `tools/plan/tasks_sa.py` | M | GOV-06 (maven), CIT-01 (Java CI), CIT-02 (CI cho hợp đồng + công cụ SA), CIT-07 (CodeQL java), LOC-03/04, CIT-10 (Maven packages), GW-01 (Spring Cloud Gateway), SCF-06/07, SHR-25, SHR-08 (Spring Cloud Stream) |
| `tools/plan/stories.py` | M | AC "LightGBM" → "XGBoost" |
| `tools/plan/build.py` | M | nhãn epic SHR/PY và người D1–D3 |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | **sinh lại** — tổng 1.065 giờ (giảm 5 giờ vì PY-xx thu nhỏ) |

4. **Lệnh làm lại / kiểm chứng:**
```bash
python3 -I tools/plan/build.py                       # OK: 74 stories, 317 tasks, 1065h
docker run -d --name mtest mongo:8 --replSet rs0 && docker exec mtest mongosh --eval 'rs.initiate()'
docker cp contracts/db/mongo-notification-ml.js mtest:/i.js && docker cp contracts/db/mongo-notification-ml.test.js mtest:/t.js
docker exec mtest mongosh --quiet /i.js && docker exec mtest mongosh --quiet /t.js   # ALL PASSED
```
5. **Lỗi gặp và cách sửa:** Docker daemon lại tắt (sau khi phiên khởi động lại) → xóa pid cũ, bật lại; image Mongo lấy từ `mirror.gcr.io` (Docker Hub giới hạn lượt kéo). Câu chữ ban đầu trong tài liệu nói "2 worker song song" trong khi kiểm chứng mongosh là **tuần tự** → đã sửa cho đúng; test song song thật sẽ nằm trong thư viện Java.
6. **Giới hạn:** chưa kiểm time-series collection và transaction trên **Cosmos DB for MongoDB vCore** (chỉ kiểm được khi dựng UAT). Các trạng thái SHR-xx trong `status.py` vẫn trỏ tới bản .NET; sẽ cập nhật sang commit Java ở J2–J4. Code .NET/Python vẫn còn trong repo cho tới J5.

### J2 — Maven gốc + `shp-service-defaults` + `shp-authz` (thay `Shp.ServiceDefaults`, `Shp.Authorization`) ✅
1. **Mục tiêu:** có khung build Java chung cho mọi module và chuyển hai thư viện nền đầu tiên, **giữ nguyên hành vi và bộ test** của bản .NET.
2. **Việc đã làm (theo thứ tự):**
   1. Thử Maven tải được qua proxy với `spring-boot-starter-parent 4.1.2`; đọc phiên bản Boot quản lý sẵn (Testcontainers 2.0.5, SnakeYAML 2.6, Jackson 3.1.7, OpenTelemetry 1.62.0, Flyway 12.4.0).
   2. Viết `pom.xml` gốc: parent Spring Boot 4.1.2, Java 21, `-Xlint:all -Werror` (cảnh báo = lỗi), Spotless (google-java-format) chạy `check` ở phase `verify`, JaCoCo ngưỡng line coverage **70%** mỗi module; `.mvn/jvm.config` mở `--add-exports` cho google-java-format trên JDK 21.
   3. `libs/java/shp-authz`: `AuthzMatrix` (SnakeYAML SafeConstructor + đủ bất biến như bản .NET/Python), `AuthorizationEngine` (dùng `java.time.Clock`), `Principal`, `ResourceContext` (có builder), `AuthzDecision`, `Predicates`; `matrix.yaml` được **copy vào jar** lúc build (`shp/authz/matrix.yaml`).
   4. Chuyển test sinh từ ma trận sang JUnit 5 (`@ParameterizedTest` + `@MethodSource`): **238 test** (bản .NET 236; thêm 2: lý do `role-not-allowed`, bộ nạp báo id trùng/denyAll/thiếu rule).
   5. `libs/java/shp-service-defaults`: auto-configuration nạp `shp-defaults.properties` (độ ưu tiên thấp nhất): `/health/live`, `/health/ready`, `/health`, `/info` (name, version, gitSha), `/prometheus`; tracing 100%, chỉ xuất OTLP khi `SHP_OTLP_EXPORT=true`; virtual threads; tắt máy êm (graceful). 5 test chạy server thật cổng ngẫu nhiên.
3. **Thay đổi (commit 95d2f74):**

| File | | Nội dung |
|---|---|---|
| `pom.xml` | A | parent + module + BOM (Spring AI 2.0.2, HAPI 8.12.1, networknt 3.0.8, CloudEvents 5.1.0, ArchUnit 1.5.1) + compiler/Spotless/JaCoCo |
| `.mvn/jvm.config` | A | `--add-exports jdk.compiler/...` (6 dòng) cho Spotless |
| `.gitignore` | M | + `target/` |
| `libs/java/shp-authz/pom.xml` | A | SnakeYAML; resource `../../../contracts/authz/matrix.yaml` → `shp/authz/` |
| `libs/java/shp-authz/src/main/java/org/shp/authz/*.java` | A | 8 lớp (xem bước 2.3) |
| `libs/java/shp-authz/src/test/java/org/shp/authz/MatrixDrivenTest.java` | A | 238 test |
| `libs/java/shp-service-defaults/pom.xml` | A | actuator, opentelemetry starter, micrometer prometheus, jackson-annotations |
| `.../src/main/resources/shp-defaults.properties` | A | đường dẫn probe khớp Helm chart; `validate-group-membership=false` |
| `.../ServiceDefaultsAutoConfiguration.java`, `ServiceInfo.java`, `META-INF/spring/...AutoConfiguration.imports` | A | auto-config + `InfoContributor` |
| `.../src/test/java/.../ServiceDefaultsTest.java` | A | 5 test |

4. **Lệnh làm lại / kiểm chứng:**
```bash
mvn -B verify                                   # BUILD SUCCESS: shp-service-defaults 5 test, shp-authz 238 test, coverage ≥ 70%
mvn -B spotless:apply                           # tự định dạng trước khi commit (CI chạy spotless:check)
```
   **Đột biến (đã làm, cả 3 bị bắt):** `own` luôn đúng → 10 test đỏ; cửa sổ hủy 2h → 1h → 2 test đỏ; `not-care` luôn đúng → 1 test đỏ. (Lần sed đầu của đột biến 2h không khớp vì Spotless đã xuống dòng; đã kiểm `grep -c` rồi làm lại.)
5. **Lỗi gặp và cách sửa:**
   - javac cảnh báo `unknown enum constant JsonInclude.Include.NON_EMPTY` (kiểu actuator tham chiếu annotation Jackson không có trên classpath) → thêm `jackson-annotations`.
   - Context không khởi động: `Health contributor 'db' ... does not exist` → `management.endpoint.health.validate-group-membership=false` để nhóm `ready` tự gồm DB nào service có.
6. **Khác biệt so với bản .NET:** đường dẫn metric là `/prometheus` (Micrometer) thay vì OTel metrics exporter; OTLP bật bằng `SHP_OTLP_EXPORT` (Helm sẽ đặt ở UAT/prod).

### J3 — `shp-events` + `shp-web` (thay `Shp.Events`, `Shp.Web`) ✅
1. **Mục tiêu:** chuyển envelope CloudEvents, kiểm schema, audit, lỗi chuẩn, xác thực JWT, tra danh tính và phân quyền gắn endpoint sang Java/Spring, giữ nguyên hành vi đã kiểm ở bản .NET.
2. **Việc đã làm (theo thứ tự):**
   1. Đọc POM `json-schema-validator` 3.0.8 → dùng **Jackson 3** (`tools.jackson`), khớp Boot 4; đọc API thật bằng `javap` (`SchemaRegistry`, `Schema.validate(node, ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)))`).
   2. **Điều chỉnh ADR-0015:** không dùng CloudEvents Java SDK (module JSON dùng Jackson 2) → envelope là record Java.
   3. `libs/java/shp-events`: `CloudEvent`, `CloudEvents.create` (UUIDv7 tự viết `Uuids.v7`, `traceparent` từ OpenTelemetry `Span.current()`, ≤ 64 KB, bỏ trường null), `EventSchemas.validate` (schema nhúng vào jar từ `contracts/events`, trừ ví dụ), `audit/` (`AuditRecord`, `AuditRecordBuilder` — che IP **không tra DNS**, `AuditSink`, `InMemoryAuditSink`, `AuthzAudit`). 47 test.
   4. `libs/java/shp-web` (auto-configuration):
      - `errors/`: `ErrorCatalog` (27 mã), `ShpException`, `ErrorWriter` (FHIR → OperationOutcome, còn lại → Problem Details), `ShpExceptionHandler` (`@RestControllerAdvice`: ShpException, 400 đầu vào sai, 404 route lạ, 500 không lộ chi tiết).
      - `auth/`: `ShpAuthProperties` (`shp.auth.*`, mặc định từ `OIDC_AUTHORITY`/`OIDC_AUDIENCE`), `ShpJwt` (validator issuer + audience + hạn có độ lệch + MFA cho nhân viên; đổi `roles` thành quyền), `IdentityResolver` (cache 60s/5s), `HttpIdentityLinkClient` (`RestClient`), `IdentityInterceptor`, `@AllowUnlinkedIdentity`.
      - `authz/`: `@RequireShpAuthz(resource, action, context, denyAsNotFound)`, `ResourceContextResolver` (bean), `AuthzInterceptor` (từ chối → audit `denied` → 403/404; cho phép → audit `success` sau khi xử lý, `error` nếu 5xx), `@ShpPublicEndpoint`, `UnprotectedEndpoints.find(ctx)`.
      - `ShpWebAutoConfiguration`: `SecurityFilterChain` (stateless, công khai `/health/**`, `/info`, `/prometheus`), `JwtDecoder` **lười** (`SupplierJwtDecoder`: service khởi động được khi IdP chưa sẵn), engine phân quyền, hai interceptor theo thứ tự danh tính → phân quyền.
      31 test (25 test tích hợp chạy server thật + token HMAC ký cục bộ; 6 test đơn vị).
3. **Thay đổi (commit 5e423ab):**

| File | | Nội dung |
|---|---|---|
| `pom.xml` | M | + module `shp-events`, `shp-web`; + `shp-events` trong dependencyManagement |
| `libs/java/shp-events/**` | A | 7 lớp chính + `EventTest` (22) + `AuditTest` (25) |
| `libs/java/shp-web/**` | A | 16 lớp chính, `shp-web-defaults.properties`, `AutoConfiguration.imports`; `TestApp`, `WebTest` (25), `UnitTest` (6) |
| `docs/03-architecture/adr/0015-java-for-all-services.md` | M | dòng "Event": record Java thay CloudEvents SDK (lý do Jackson 2/3) |

4. **Cách dev dùng (thay hướng dẫn .NET ở D2/D3):**
```java
@GetMapping("/fhir/Appointment/{id}")
@RequireShpAuthz(resource = "Appointment", action = "read", context = "appointmentFacts")
Appointment read(@PathVariable String id, HttpServletRequest req) { ... }      // throw new ShpException("not-found") khi không có
@Bean ResourceContextResolver appointmentFacts(...) { return (req, vars, principal) -> ResourceContext.builder().patientId(...).build(); }
// test của service: assertThat(UnprotectedEndpoints.find(applicationContext)).isEmpty();
// test hợp đồng: assertThat(EventSchemas.validate(CloudEvents.create("scheduling", "shp.scheduling.appointment-booked.v1", subject, payload))).isEmpty();
```
   Service chỉ phải cung cấp bean `IdentityLinkClient` (clinical: đọc DB; khác: `HttpIdentityLinkClient`) và `AuditSink`.
5. **Kiểm chứng:**
```bash
mvn -B verify      # shp-service-defaults 5, shp-authz 238, shp-events 47, shp-web 31 — BUILD SUCCESS, coverage ≥ 70%
```
   **Đột biến (đã làm, tất cả bị bắt):** shp-events — tắt kiểm `format` (1 đỏ), tắt che IP (3), bỏ chặn giá trị trong `resource` (5). shp-web — bỏ MFA (2), bỏ audience (1), cho tài khoản chưa liên kết (1), bỏ cờ khóa (1), không audit khi từ chối (3), luôn `forbidden` (1), TTL 600s (1), không ghi `error` khi 5xx (1).
6. **Lỗi gặp và cách sửa:**
   - **Lần đầu chạy đột biến shp-events cho kết quả trống — không hợp lệ:** chạy `mvn -pl libs/java/shp-events` thiếu `-am` nên Maven không tìm thấy `shp-authz` (chưa cài vào `~/.m2`) và dừng trước khi test. Làm lại với `-am`. **Bài học:** luôn có dòng "baseline" xanh trước khi tin kết quả đột biến; dùng `-am` (hoặc `mvn install`) cho module có phụ thuộc nội bộ.
   - Đột biến bằng `sed` không khớp khi Spotless đã xuống dòng → dùng script Python có `assert` để báo "NO MATCH" thay vì im lặng.
   - javac `-Werror`: try-with-resources không dùng biến → đổi thành `try/finally`.
   - `MockRestServiceServer` không cho thêm kỳ vọng sau khi đã gọi → mỗi kịch bản một server.
7. **Khác biệt so với bản .NET:** endpoint "công khai có chủ đích" vẫn **phải đăng nhập** (chuỗi bảo mật mặc định chặn mọi đường dẫn trừ health/info/prometheus); `@ShpPublicEndpoint` chỉ nghĩa là "không cần quy tắc ma trận". 405 trả `validation-failed` (400) vì bảng lỗi §5 không có mã riêng.

### J4 — Thư viện dữ liệu: `shp-data-postgres` (chuyển từ .NET) + `shp-data-mongo` (mới, theo ADR-0014) ✅ / một phần
1. **Mục tiêu:** event luôn ghi cùng transaction với dữ liệu và không mất khi bus lỗi; consumer xử lý đúng một lần; Idempotency-Key không tạo trùng — cho **cả PostgreSQL (scheduling) và MongoDB (clinical, document, ai, notification, ml)**.
2. **Việc đã làm (theo thứ tự):**
   1. Thêm `EventPublisher` (interface chung) vào `shp-events`; thêm `IdempotencyKeys` (kiểm header + băm body chuẩn hóa, không phụ thuộc DB) vào `shp-web`.
   2. Kiểm tên module Testcontainers 2.x trong BOM: `testcontainers-postgresql`, `testcontainers-mongodb` (gói Java mới `org.testcontainers.postgresql` / `org.testcontainers.mongodb`).
   3. `libs/java/shp-data-postgres`: `PostgresOutbox` (JdbcClient, bắt buộc trong transaction), `OutboxRelay` (`SmartLifecycle`, thread ảo, `FOR UPDATE SKIP LOCKED`, backoff ≤ 30s), `PostgresInbox`, `PostgresIdempotency`, `IdempotencyClaim`; DDL thành **migration Flyway** `db/shp-messaging/postgres/V0_1__shp_messaging.sql` (service thêm vào `spring.flyway.locations`). 28 test trên PostgreSQL 16 thật.
   4. `libs/java/shp-data-mongo`: `MongoOutbox`, `MongoOutboxRelay` (MongoDB không có `SKIP LOCKED` → **giành từng event bằng `findAndModify`** đặt `lockedUntil`/`lockedBy`, khóa 30s; replica chết thì khóa hết hạn và event được giành lại), `MongoInbox` (`_id = consumer|messageId`), `MongoIdempotency` (`_id = subject|key`), `MongoMessaging.inTransaction` (**thử lại tối đa 8 lần** khi MongoDB báo `TransientTransactionError`/WriteConflict/DuplicateKey — yêu cầu bắt buộc của transaction đa document), `ensureIndexes` (partial index outbox, TTL inbox 14 ngày, TTL idempotency 24 giờ). 21 test trên MongoDB 8 replica set thật.
3. **Thay đổi (commit 3764d57):**

| File | | Nội dung |
|---|---|---|
| `pom.xml` | M | + module/dependencyManagement `shp-web`, `shp-data-postgres`, `shp-data-mongo` |
| `libs/java/shp-events/.../EventPublisher.java` | A | `void publish(CloudEvent)` |
| `libs/java/shp-web/.../idempotency/IdempotencyKeys.java` | A | `require(header)` (428/400), `hashBody(bytes)` |
| `libs/java/shp-data-postgres/**` | A | 5 lớp + migration Flyway + 3 lớp test (28 test) |
| `libs/java/shp-data-mongo/**` | A | 5 lớp + 3 lớp test (21 test) |

4. **Cách dev dùng:**
```java
// PostgreSQL (scheduling) — trong @Transactional:
var claim = idempotency.claim(principal.subject(), IdempotencyKeys.require(req.getHeader("Idempotency-Key")), "Appointment/$book", IdempotencyKeys.hashBody(body));
if (claim.replay()) return loadAppointment(claim.resourceId());
... ghi appointment ...; outbox.add(CloudEvents.create("scheduling", "shp.scheduling.appointment-booked.v1", "Appointment/" + id, payload));
// MongoDB: bọc toàn bộ bằng MongoMessaging.inTransaction(tx, () -> { ... idempotency.claim(...); ... outbox.add(...); })
// Consumer: inbox.handleOnce("notification", event, () -> { ... });
```
5. **Kiểm chứng:**
```bash
# cần Docker; Docker Hub giới hạn lượt kéo thì dùng mirror:
export SHP_TEST_POSTGRES_IMAGE=mirror.gcr.io/library/postgres:16-alpine SHP_TEST_MONGO_IMAGE=mirror.gcr.io/library/mongo:8
mvn -B verify      # 7 module, 370 test: 5 + 238 + 47 + 31 + 28 + 21 — BUILD SUCCESS
```
   **Đột biến (tất cả bị bắt):** PostgreSQL — bỏ 422 (1 đỏ), bỏ `SKIP LOCKED` (1), inbox ghi đè (2). MongoDB — bỏ điều kiện khóa của relay (2), khóa inbox không duy nhất (2), bỏ 422 (1), bỏ hết hạn 24h (1).
   Kịch bản đã kiểm trên DB thật (cả hai engine): rollback giữ nguyên dữ liệu lẫn outbox; thứ tự; bus chết giữa chừng rồi sống lại vẫn phát đủ và đúng thứ tự; 4 relay × 60 event → mỗi event đúng 1 lần; 20 lần giao trùng → commit đúng 1 lần; 20 request song song cùng key → đúng 1 claim mới; rollback claim → claim lại được; key hết hạn sau 24h. Riêng MongoDB: khóa của replica chết hết hạn thì event được phát.
6. **Lỗi gặp và cách sửa:** Testcontainers yêu cầu khai báo image mirror là bản thay thế (`asCompatibleSubstituteFor("postgres"|"mongo")`); **Testcontainers 2.x không tự bật replica set** cho MongoDB như 1.x → `.withReplicaSet()` (thiếu thì lỗi "Transaction numbers are only allowed on a replica set member"); `ExecutorService.submit(this::claim)` mơ hồ → lambda; một đột biến không khớp chuỗi vì Spotless xuống dòng → dùng regex.
7. **Giới hạn đã biết:** SHR-07/08 vẫn `partial`: **chưa có `EventPublisher` thật** (Spring Cloud Stream + RabbitMQ/Service Bus) và retry/DLQ phía consumer — bước J5b. SHR-10 (resource store FHIR trên MongoDB: version CAS + history) chưa làm. Với MongoDB, handler trong `inbox.handleOnce` **có thể chạy lại** khi transaction xung đột (chỉ một lần được commit) → handler không được gọi dịch vụ ngoài có tác dụng phụ (gửi SMS...) trực tiếp; phải ghi việc vào DB rồi worker làm sau (đúng thiết kế notification).

### J4b — Bổ sung của PM: **ai và ml giữ Python**; tách `shp-security`; audit ghi bền qua outbox ✅
1. **Mục tiêu:** PM bổ sung (2026-10-28): *"phần nào bắt buộc dùng Python như AI thì vẫn giữ"* → sửa phạm vi ADR-0015 và mọi thứ J1 đã đổi cho ai/ml; đồng thời chuẩn bị cho gateway (WebFlux) và cho audit lưu bền.
2. **Việc đã làm (theo thứ tự):**
   1. **ADR-0015** đổi tên và phạm vi: Java cho `gateway, scheduling, notification, clinical, document, audit`; **Python giữ cho `ai`, `ml`** (lý do: LightGBM, pandas, pandera, MLflow, công cụ eval không có bản Java tương đương). Bảng ánh xạ ghi "Giữ Python" cho AI, ML, OCR; hệ quả: hai pipeline CI (`java-ci`, `python-ci`), ai/ml dùng **cùng hợp đồng** trong `contracts/`.
   2. **Hoàn nguyên** những gì J1 đã đổi sang Java cho ai/ml (lấy nguyên văn từ commit trước J1 bằng `git show 3b7390f^:...`, không gõ lại): task PY-01, PY-03, PY-04, PY-05 (giờ lại cần thư viện Python riêng), ML-04 (LightGBM), ML-14 (pandera), CIT-02 (Python CI), LOC-04 (skeleton Python), SCF-06 (FastAPI); SCF-07 → "khung code Python… kết nối **MongoDB**" (giữ ADR-0014); PY-07 bỏ "Spring AI"; AC của story "LightGBM"; nhãn D3 "Dev Python/AI". Tổng kế hoạch về lại **1.070 giờ**.
   3. Tài liệu: `overview.md` (ai: Python FastAPI, ml: Python LightGBM/MLflow), ADR-0007 (LightGBM), `16-mlops.md` (pandera, LightGBM), ADR-0009 (Java CI + Python CI), `04-devops/README.md`, `04-db-notification-ml.md` (notification Java, ml Python/pymongo, drift bằng pandas/DuckDB Python).
   4. Gỡ BOM Spring AI khỏi `pom.xml` gốc (không còn module Java nào dùng).
   5. **Tách `libs/java/shp-security`** (`ShpJwt`, `ShpAuthProperties`, `Roles`; gói `org.shp.security`) khỏi `shp-web`: gateway là WebFlux, không được kéo Spring MVC của `shp-web` vào; cả hai dùng chung một quy tắc JWT. Dùng `git mv` để giữ lịch sử file.
   6. Thêm `PostgresOutboxAuditSink`, `MongoOutboxAuditSink`: ghi audit thành event E11 vào outbox trong **transaction riêng (REQUIRES_NEW)** → audit vẫn được lưu khi giao dịch nghiệp vụ rollback, không mất khi bus lỗi. Mỗi bản 1 test.
3. **Thay đổi (commit 638b780):**

| File | | Nội dung |
|---|---|---|
| `docs/03-architecture/adr/0015-java-for-all-services.md` | M | tiêu đề, phạm vi, 9 dòng bảng, phương án đã cân nhắc, hệ quả (xem bước 2.1) |
| `docs/03-architecture/adr/README.md`, `overview.md`, `adr/0007`, `adr/0009`, `detailed-design/16-mlops.md`, `detailed-design/04-db-notification-ml.md`, `docs/04-devops/README.md` | M | ai/ml về Python |
| `tools/plan/tasks_dev.py`, `tasks_sa.py`, `stories.py`, `build.py` | M | hoàn nguyên task ai/ml (bước 2.2) |
| `docs/05-plan/*`, `docs/02-requirements/user-stories.md` | M | sinh lại (1.070 giờ) |
| `pom.xml` | M | − Spring AI BOM; + module `shp-security` |
| `libs/java/shp-security/**` | A (git mv) | 3 lớp chuyển từ `shp-web/auth`, gói `org.shp.security` |
| `libs/java/shp-web/pom.xml`, `IdentityInterceptor.java`, `ShpWebAutoConfiguration.java`, test | M | import `org.shp.security.*` |
| `libs/java/shp-data-postgres/.../PostgresOutboxAuditSink.java` + `AuditSinkTest.java` | A | |
| `libs/java/shp-data-mongo/.../MongoOutboxAuditSink.java` + `AuditSinkTest.java` | A | |

4. **Kiểm chứng:** `python3 -I tools/plan/build.py` → `OK: 74 stories, 317 tasks, 1070h`; `mvn -B verify` → 8 module, **372 test** (5 + 238 + 47 + 31 + 29 + 22), BUILD SUCCESS.
5. **Ảnh hưởng:** service `ai`, `ml` Python hiện có (FastAPI) được **giữ**, không xóa ở J5; template `python-ci` giữ. Thư viện nền Python cho ai/ml (PY-01..05) vẫn là việc của D3 như kế hoạch gốc.

### J5a — Gateway Java (Spring Cloud Gateway) + sửa cách nạp cấu hình mặc định ✅
1. **Mục tiêu:** thay gateway .NET (YARP) bằng Spring Cloud Gateway, giữ đúng bảng định tuyến 07 §1 và lớp 1 của 15-authorization (kiểm token ở biên).
2. **Việc đã làm (theo thứ tự):**
   1. Kiểm tương thích: `spring-cloud-dependencies` **2025.1.2** (gateway 5.0.2) giải quyết được với Boot 4.1.2 (bản 2026.0 mới ở RC). `git rm -r services/gateway` (bản .NET).
   2. `services/gateway`: `GatewayApplication`, `GatewaySecurity` (WebFlux: `/internal/**` → từ chối, health/info/prometheus công khai, mọi route khác cần JWT hợp lệ theo **cùng** `ShpJwt.validator` với service; decoder lười `SupplierReactiveJwtDecoder`; lỗi dạng Problem Details), `application.yml` sinh từ bảng route cũ (7 upstream, 32 đường dẫn; địa chỉ đổi bằng `SHP_UPSTREAM_<SERVICE>`).
   3. Test 7 kịch bản với upstream giả (HTTP server thật trong test): route đúng service và **chuyển nguyên token** (service kiểm lại); 401 khi thiếu/sai audience; nhân viên thiếu MFA bị chặn ở biên; `/internal/**` → 404; đường dẫn lạ → 404; upstream chết → 5xx; health công khai.
   4. **Phát hiện lỗi tiềm ẩn ở `shp-service-defaults`:** `/info`, `/prometheus` trả 404 ở gateway. Bật báo cáo điều kiện (`-Ddebug=true`): `InfoEndpointAutoConfiguration ... not exposed` — `@PropertySource` trên auto-configuration được nạp **sau** khi Boot đánh giá điều kiện; bản MVC qua test chỉ nhờ thứ tự may mắn. Sửa: `ShpDefaultsEnvironmentPostProcessor` (đăng ký ở `META-INF/spring.factories`, khóa `org.springframework.boot.EnvironmentPostProcessor` của Boot 4) nạp mọi `classpath*:META-INF/shp/*.properties` với độ ưu tiên thấp nhất **trước** auto-configuration; bỏ `@PropertySource` ở cả `shp-service-defaults` và `shp-web`.
3. **Thay đổi (commit e2558f0):**

| File | | Nội dung |
|---|---|---|
| `services/gateway/**` (.NET) | D | YARP, Dockerfile, test .NET |
| `services/gateway/pom.xml`, `src/main/**`, `src/test/**` | A | gateway Java + 7 test |
| `pom.xml` | M | + module `services/gateway` |
| `libs/java/shp-service-defaults/.../ShpDefaultsEnvironmentPostProcessor.java`, `META-INF/spring.factories` | A | nạp mặc định đúng thời điểm |
| `.../shp-defaults.properties` → `META-INF/shp/00-service-defaults.properties`; `shp-web-defaults.properties` → `META-INF/shp/10-web-defaults.properties` | R | đổi chỗ (git mv) |
| `ServiceDefaultsAutoConfiguration.java`, `ShpWebAutoConfiguration.java` | M | bỏ `@PropertySource` |

4. **Kiểm chứng:** `mvn -B verify` → 9 module, **379 test** (thêm 7 của gateway), BUILD SUCCESS.
5. **Lỗi gặp:** hai `ReactiveJwtDecoder` trong test (`@ConditionalOnMissingBean` không đáng tin trong `@Configuration` thường) → bean test `@Primary`.
6. **Giới hạn:** chưa có rate limit theo `sub` (cần Redis, task GW) và CORS cho web.

### J5b — Gỡ mọi nhắc đến tên PoC cũ khỏi các repo dự án (yêu cầu PM) ✅
1. **Việc đã làm:** `git grep -i <tên PoC cũ>` trên 4 repo → 13 chỗ, đều ở repo app. Xóa `docs/03-architecture/<tên PoC cũ>-migration.md` và mục 5 của `overview.md` trỏ tới nó; viết lại trung tính: README, `overview.md`, ADR-0002/0005/0008 (nêu "cách làm phổ biến", "ví dụ Medplum" thay vì tên PoC), `phase4-build-log.md`, nhật ký này ("repo PoC cũ").
2. **Kiểm chứng:** `git grep -i -c <tên PoC cũ>` trên cả 4 repo → 0.
3. **Lưu ý:** **lịch sử git** của repo app vẫn còn các commit cũ có chữ này; xóa khỏi lịch sử cần viết lại lịch sử và force-push — chưa làm, chờ PM quyết (khi chuyển sang repo thật bằng cách copy nội dung, lịch sử mới sẽ sạch).
4. **Commit:** e2558f0 (cùng commit với J5a).

### J5c — 5 service Java còn lại, đóng gói gateway, bộ công cụ dev local chuyển sang Java ✅ / một phần
1. **Mục tiêu:** mỗi service Java có khung chạy được (build, test, image, deploy local, pipeline) để dev chỉ viết nghiệp vụ; xóa hết phần .NET còn lại.
2. **Việc đã làm (theo thứ tự):**
   1. Viết `tools/scaffold/java_service.py` (thay `dotnet_service.py`) và sinh 5 service: `scheduling` (PostgreSQL) và `notification`, `clinical`, `document`, `audit` (MongoDB).
      Mỗi service có:
      - `pom.xml` (`finalName app`, `shp-data-*`, Testcontainers) và `*Application`.
      - `config/PlatformConfig`: bean outbox, inbox, idempotency, audit sink. Với Mongo còn có `MongoTransactionManager` và tạo collection + index lúc khởi động.
      - `IdentityLinkClient`: clinical đọc thẳng Mongo của mình; service khác gọi `SHP_CLINICAL_URL`.
      - `application.yml` lấy mọi giá trị nhạy cảm từ biến môi trường.
      - Test: health công khai, `UnprotectedEndpoints` rỗng, 401 khi không có token; clinical có thêm test liên kết danh tính.
      - Dockerfile (Maven builder → `distroless/java21:nonroot`), `deploy/values-local.yaml`, README "dev viết gì, ở đâu".
      - `.github/workflows/svc-<tên>.yml` dùng `java-ci` của repo templates.
   2. Gateway: thêm `Dockerfile`, `deploy/values-local.yaml`, README, `<finalName>app</finalName>` (bị xóa cùng bản .NET ở J5a).
   3. **Sửa lệch issuer trên kind:**
      - Token lấy từ máy dev qua `http://localhost:18080`, nên issuer là `localhost`. Pod không gọi được `localhost` của máy.
      - Thêm `shp.auth.jwk-set-uri` (`OIDC_JWK_SET_URI`) vào `ShpAuthProperties`. Khi có giá trị, decoder lấy khóa từ URL này (DNS trong cụm), còn issuer vẫn kiểm theo `OIDC_AUTHORITY`. Áp dụng cho cả servlet (`ShpWebAutoConfiguration`) và reactive (`GatewaySecurity`).
   4. Bộ công cụ dev:
      - `deploy.sh`: danh sách `JAVA`, context là gốc repo. Secret `shp-local-conn` giờ chứa `postgres-password`, `redis-password`, `rabbitmq-password`, `mongodb`, `rabbitmq`, `blob`.
      - `app-values-local.yaml`: `SPRING_*` theo quy ước Spring Boot; `MONGODB_URI`/`RABBITMQ_URL` cho Python; OIDC như bước 3; MFA tắt cho user thử.
      - `env.sh`: biến `SPRING_*` cho chạy ngoài cụm.
      - Postgres local chỉ còn database `scheduling`.
      - `up.sh` chạy `contracts/db/mongo-notification-ml.js` sau khi replica set sẵn sàng.
      - Cập nhật `dev/README.md` (JDK 21 + Maven; bảng biến môi trường).
   5. Thêm `.dockerignore` gốc: context build Java là cả repo, phải loại `target/` và **`dev/.local` (chứa mật khẩu sinh ngẫu nhiên)**.
   6. `.editorconfig`: bỏ mục C#, thêm Java/pom thụt 2 (Spotless là nguồn sự thật). Xóa toàn bộ `libs/dotnet`, 5 service .NET, `Directory.*.props`, `global.json`, `libs-dotnet.yml`.
   7. `tools/plan/status.py`: LOC-03, LOC-05, GW-01, SCF-01..05 = một phần (ghi rõ còn thiếu gì).
3. **Thay đổi (commit c909d5b, 159 file):**

| File | | Nội dung |
|---|---|---|
| `tools/scaffold/dotnet_service.py` → `java_service.py` | D / A | bộ sinh service Java |
| `services/{scheduling,notification,clinical,document,audit}/**` | D (.NET) / A (Java) | khung Java như bước 2.1 |
| `services/gateway/{Dockerfile,README.md,deploy/values-local.yaml}`, `pom.xml` | A / M | đóng gói gateway |
| `libs/java/shp-security/.../ShpAuthProperties.java` | M | 4 → 5 tham số (`jwkSetUri`, mặc định rỗng) |
| `ShpWebAutoConfiguration.java`, `GatewaySecurity.java` | M | `fromIssuerLocation` → chọn `withJwkSetUri` khi có cấu hình |
| `10-web-defaults.properties`, `services/gateway/.../application.yml` | M | + `shp.auth.jwk-set-uri=${OIDC_JWK_SET_URI:}` |
| `dev/scripts/{deploy,env,up}.sh`, `dev/chart/app-values-local.yaml`, `dev/chart/shp-local-deps/values.yaml`, `dev/kind/cluster.yaml`, `dev/README.md` | M | như bước 2.4 |
| `.dockerignore` | A | loại `target/`, `dev/.local`, cache Python |
| `.editorconfig` | M | − C#; + Java |
| `libs/dotnet/**`, `Directory.*.props`, `global.json`, `.github/workflows/libs-dotnet.yml` | D | hết .NET |
| `.github/workflows/svc-*.yml` | A/M | sinh lại cho Java; `libs-java.yml` mới |

4. **Lệnh làm lại:**
```bash
for s in scheduling notification clinical document audit; do python3 -I tools/scaffold/java_service.py $s; done
mvn -B spotless:apply && mvn -B verify                    # cần Docker cho Testcontainers
docker build -f services/gateway/Dockerfile -t localhost/shp-gateway:dev .
```
5. **Kiểm chứng:**
   - `mvn -B verify` toàn reactor: 14 module, **395 test**, BUILD SUCCESS. `tools/contracts/check_all.sh`: ALL CONTRACT CHECKS PASSED. `tools/plan/build.py`: 317 task, 1070 giờ.
   - Image gateway, chạy thật: `/health/readiness` → UP; `/info` → `{"name":"gateway",...}`; `/fhir/Patient` → 401; user `nonroot`.
   - Image scheduling, chạy thật với Postgres 16: readiness UP; Flyway tạo `outbox`, `inbox`, `idempotency_key`; `/fhir/Appointment` → 401.
   - Lệnh Mongo trong `up.sh` chạy 2 lần liên tiếp trên Mongo 8 replica set: không lỗi, tạo đủ collection notification/ml.
   - Field mà `MongoOutbox` ghi khớp validator `outbox` của script (`eventType`, `subject`, `payload`, `occurredAt`); tên index trùng nhau.
   - `helm template` chart `microservice` với values local cho gateway, scheduling, clinical, ai: render đúng `OIDC_JWK_SET_URI` và secret `postgres-password`.
6. **Lỗi & cách sửa:**
   - `docker build` trong sandbox: Maven trong container không tin CA của proxy (`PKIX path building failed`). Đây là lỗi môi trường, không phải lỗi Dockerfile; máy dev và GitHub Actions không gặp.
     Kiểm chứng thay thế: chạy đúng lệnh `mvn ... package` của stage build trên máy, rồi build stage runtime với jar đó.
   - Gateway thiếu `deploy/values-local.yaml` (bị xóa cùng bản .NET) → `helm template` báo lỗi → đã thêm.
   - Endpoint quản trị ở `/health`, `/info`, không có `/actuator` (base-path `/` theo `shp-service-defaults`).
7. **Giới hạn còn lại:**
   - Chưa có `EventPublisher` thật (Spring Cloud Stream → RabbitMQ/Service Bus), nên relay chưa đẩy event lên bus.
   - Chưa chạy `make quickstart` trên kind thật.
   - Chưa chạy pipeline GitHub Actions thật.
   - Commit: `git show --stat c909d5b`.

## Phần D — Kế tiếp (theo thứ tự)
| # | Bước | Ai | Trạng thái |
|---|---|---|---|
| D1 | Thư viện Java `libs/java` ✅ (J2–J4) + 6 service Java ✅ (J5); còn: `EventPublisher` Spring Cloud Stream + consumer/DLQ, FHIR resource store Mongo (SHR-10) | SA | đang làm |
| D1b | Onboarding dev (J6): README gốc, CONTRIBUTING, hướng dẫn từng dev, PR template, CODEOWNERS, issue | SA | ⏳ |
| D1c | Sơ đồ kiến trúc tổng thể + module (J7) | SA | ⏳ |
| D2 | Thư viện Python (`libs/python`): LLM router (DeepSeek → Gemini → Ollama, redact PII), shared feature package cho ML | SA | ⏳ |
| D3 | Repo GitOps: Argo CD bootstrap, AppProject, ApplicationSet, add-on, Kyverno, `envs/{uat,prod}` | SA | ⏳ |
| D4 | ENV-01: cài Argo CD trong workflow `env-provision` | SA | ⏳ |
| D5 | Module IaC dữ liệu (IAC-09..16), node pool `ai` cho Ollama + secret key LLM | SA | ⏳ |
| D6 | Profile local `full` (OpenSearch, Ollama, ClamAV, MLflow, observability), realm MFA | SA | ⏳ |
| D7 | Cài GitHub (ruleset, CODEOWNERS, Environments, GitHub App, Variables) theo `github-setup.md` | 👤 bạn | ⏳ |
| D8 | Bootstrap Azure một lần, điền GitHub Variables | 👤 bạn | ⏳ |
| D9 | Xoay key bị lộ ở repo PoC cũ + xóa lịch sử | 👤 bạn | ⏳ |
| D10 | Chuyển repo templates/infra sang private | 👤 bạn | đang làm |
