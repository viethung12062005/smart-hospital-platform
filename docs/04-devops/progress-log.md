# Nhật ký tiến trình Pha 4 — từng bước theo thứ tự, để kiểm tra và tự làm lại

> Cách dùng: đọc từ trên xuống. Mỗi bước có **mục đích → việc làm → lệnh → kiểm chứng → kết quả (commit)**.
> Muốn làm lại một bước: chạy phần "Lệnh", rồi chạy phần "Kiểm chứng"; kết quả phải giống phần "Kết quả".
> Tổng quan trạng thái và việc còn lại: [phase4-build-log.md](phase4-build-log.md). File này là bản chi tiết theo thời gian, **cập nhật cuối mỗi bước**.
> Ký hiệu trạng thái: ✅ xong và đã kiểm chứng · 🟡 xong nhưng chưa chạy được ở môi trường thật · ⏳ chưa làm · 👤 cần bạn làm.
> Chuẩn bị chung để chạy lệnh: Python 3.12+, .NET SDK 10, Docker, `pip install -r tools/contracts/requirements.txt`.

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

## Phần D — Kế tiếp (theo thứ tự)
| # | Bước | Ai | Trạng thái |
|---|---|---|---|
| D1 | Thư viện dùng chung cho dev (`libs/dotnet`): xác thực JWT + role + identity link, thực thi `matrix.yaml`, audit middleware, outbox/inbox + MassTransit, Idempotency-Key, FHIR helper, fixture Testcontainers | SA | ⏳ |
| D2 | Thư viện Python (`libs/python`): LLM router (DeepSeek → Gemini → Ollama, redact PII), shared feature package cho ML | SA | ⏳ |
| D3 | Repo GitOps: Argo CD bootstrap, AppProject, ApplicationSet, add-on, Kyverno, `envs/{uat,prod}` | SA | ⏳ |
| D4 | ENV-01: cài Argo CD trong workflow `env-provision` | SA | ⏳ |
| D5 | Module IaC dữ liệu (IAC-09..16), node pool `ai` cho Ollama + secret key LLM | SA | ⏳ |
| D6 | Profile local `full` (OpenSearch, Ollama, ClamAV, MLflow, observability), realm MFA | SA | ⏳ |
| D7 | Cài GitHub (ruleset, CODEOWNERS, Environments, GitHub App, Variables) theo `github-setup.md` | 👤 bạn | ⏳ |
| D8 | Bootstrap Azure một lần, điền GitHub Variables | 👤 bạn | ⏳ |
| D9 | Xoay key bị lộ ở caremate + xóa lịch sử | 👤 bạn | ⏳ |
| D10 | Chuyển repo templates/infra sang private | 👤 bạn | đang làm |
