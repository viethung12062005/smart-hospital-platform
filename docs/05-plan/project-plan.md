# Kế hoạch dự án

> Trạng thái: **Approved (2026-10-26)**. Thứ tự làm việc: BA → Requirements → **User story** → Thiết kế chi tiết → Backlog → Code.
> Chưa có dòng code nào; mọi con số dưới đây sinh từ dữ liệu trong `tools/plan/` bằng `python3 -I tools/plan/build.py`
> (script tự kiểm tra: mọi AC của FR thuộc một story, mọi API và mọi event có task, task dev ≤ 4 giờ, không task nào xếp trước phụ thuộc, dev ≤ 30 giờ/tuần).

## 1. Tài liệu đầu vào cho từng người

| Cần gì | Đọc ở đâu |
|---|---|
| Bối cảnh, quy tắc nghiệp vụ | [Business analysis](../01-business-analysis/business-analysis.md) |
| Yêu cầu, NFR, ma trận phân quyền | [02-requirements](../02-requirements/functional-requirements.md) |
| **70 user story + tiêu chí nghiệm thu + task của từng story** | [user-stories.md](../02-requirements/user-stories.md) |
| Data model, DB, API, event, sequence, phân quyền | [detailed-design](../03-architecture/detailed-design/README.md) |
| **Việc từng người theo từng tuần (checklist)** | [SA](by-person/SA.md) · [D1](by-person/D1.md) · [D2](by-person/D2.md) · [D3](by-person/D3.md) |
| Toàn bộ backlog / import GitHub Projects | [backlog.md](backlog.md) · [backlog.csv](backlog.csv) |

## 2. Phân công

| Mã | Vai trò | Phụ trách |
|---|---|---|
| **SA** | Solution Architect + PM + DevOps | **Mọi thứ ngoài logic nghiệp vụ**: kiến trúc, hợp đồng API/event, hạ tầng, CI/CD, Helm/GitOps, môi trường, scaffold service, gateway, observability, load/failure test, backup, phê duyệt, tiến độ |
| **D1** | Dev .NET (chỉ logic) | Thư viện auth/phân quyền/idempotency · **scheduling** · **notification** · UI đặt lịch và quản lý lịch |
| **D2** | Dev .NET (chỉ logic) | Lõi FHIR/Mongo/outbox/audit emitter · **clinical** · **document** · **audit** · UI lâm sàng |
| **D3** | Dev Python/AI (chỉ logic) | Thư viện Python · dữ liệu giả · **ML no-show** · **AI service** (RAG, tóm tắt, trích xuất, chatbot, eval) · UI AI |

**Quy tắc:** dev nhận service đã được SA scaffold (khung code, Dockerfile, pipeline, Helm values, kết nối DB/bus/OTel) và chỉ viết logic và test.
Migration DB và thao tác truy cập dữ liệu của chính service là logic của dev (viết theo DDL đã được kiểm chứng trong thiết kế). Dev không sửa
pipeline, Dockerfile, Helm hay hạ tầng; cần thay đổi thì mở issue cho SA. Quy ước tên nhánh: `<ID>-mo-ta-ngan`, một PR một task.

### Cấu trúc repo ứng dụng (`smart-hospital-platform`, tạo ở GOV-01 và ARC-09)

```
services/<scheduling|notification|clinical|document|audit|ai|ml|gateway>/   # mỗi service: src, tests, Dockerfile, deploy/values-<env>.yaml
libs/dotnet/   libs/python/                    # thư viện dùng chung (SHR-*, PY-*)
web/                                           # PWA React
contracts/{openapi,events,authz,terminology}/  # hợp đồng API, schema event, matrix.yaml, ICD-10/LOINC
prompts/<task>/v<N>.md   templates/            # prompt AI có version, mẫu thông báo vi/en
docs/   tools/plan/                            # tài liệu và nguồn sinh backlog
.github/workflows/svc-<name>.yml               # mỗi service một pipeline riêng, gọi template chung ở repo shp-platform-templates
```

## 3. Lộ trình và các cổng

```
W1   Gate G1/G2/G3: duyệt BA, requirements, kiến trúc + thiết kế chi tiết; tạo repo
W2   SA: bootstrap Azure, CI templates, Helm chart | Dev: thư viện dùng chung (FHIR, authz, JWT, router LLM), dữ liệu giả, ML dữ liệu
W3   SA: network/AKS/monitoring, GitOps | Dev: tiếp tục thư viện, shell web
W4   SA: scaffold 7 service (scheduling, notification, clinical, document, audit, ai, ml) + platform add-on | Dev bắt đầu service (clinical, audit, notification, AI)
W5   SA: module dữ liệu, env-provision/destroy | Dev: scheduling, clinical, notification, ML
W6   ── Gate G4 ── (service mẫu đi commit → dev → staging → prod, rollback ≤ 5 phút, image chưa ký bị chặn)
W7–W9 Phát triển tính năng; từ sau G4 mỗi service deploy lên dev qua pipeline (dựng môi trường ephemeral theo lịch)
W10  Hardening: load test, failure test, backup/restore | Dev: sửa theo kết quả
W11  Eval AI/ML, FinOps, rà soát bảo mật
W12  Kịch bản demo, slide, diễn tập trên prod ephemeral
```

| Cổng | Tuần | Điều kiện | Ai duyệt |
|---|---|---|---|
| G1 | W1 | Phạm vi + KPI chốt | SA (PM) |
| G2 | W1 | FR có AC, NFR có số, user story được duyệt | SA |
| G3 | W1 | ADR Accepted + thiết kế chi tiết được duyệt | SA (Architect) |
| **G4** | **W6** (đã duyệt; trước đó là W5) | Pipeline đầy đủ + service mẫu qua 3 môi trường + rollback + chặn image chưa ký | SA (DevOps) |
| Feature freeze | cuối W9 | Mọi story Must xong và deploy được | SA |

**Vì sao G4 dời từ W5 sang W6:** khối lượng SA W2–W5 là ~45 giờ/tuần dù đã dời mọi việc không chặn G4 sang sau. Dev vẫn làm việc bình
thường trước G4 (code, test local và test tích hợp bằng Testcontainers); chỉ việc deploy lên cloud bắt đầu từ W7. Đã được SA xác nhận khi duyệt plan.

## 4. Tải công việc (giờ/tuần, chi tiết trong [backlog.md](backlog.md))

| Người | Tổng | Đỉnh | Nhận xét |
|---|---|---|---|
| SA | 341 | 47 (W3), 45 (W4) | Nặng nhất W2–W5; sau W6 nhẹ. Đây là điểm nghẽn lớn nhất của kế hoạch |
| D1 | 212 | 30 | W3 chỉ 7 giờ vì chờ nền tảng từ D2 |
| D2 | 235 | 30 | Nhiều nhất, **căng nhất**: clinical + document + audit + lõi FHIR chạy gần như song song |
| D3 | 216 | 30 | W8 chỉ ~4 giờ do chờ API của D2: dùng làm dự phòng, hỗ trợ D2 |

Tổng 299 task, 1.004 giờ. Dev xếp tối đa 30 giờ/tuần (mặc định coi 25–30 giờ/tuần là năng lực thực). Nếu thực tế thấp hơn, cắt theo thứ tự dưới đây.

### Phạm vi có thể cắt khi trễ (theo thứ tự)
1. **Could** (~24 giờ dev): US-040 (đánh dấu tài liệu nhập nhầm), US-056 (chấm điểm/lịch sử hội thoại), US-062 (chatbot đặt lịch), US-065 (drift).
2. **Should** (~126 giờ dev): chọn bỏ trích xuất tài liệu (US-059..061), tóm tắt hồ sơ (US-058), đổi lịch (US-017), gộp hồ sơ (US-004), break-glass (US-034, US-051) nếu thật sự thiếu người. Cần nhắc lại: đề bài chỉ gợi ý **một** tính năng AI nâng cao nhưng bắt buộc có đánh giá chất lượng.
3. Không cắt: Must (đặt lịch, lâm sàng cơ bản, tài liệu, phân quyền, audit, thông báo, RAG), vì đó là phần bắt buộc của đề.

## 5. Cách theo dõi

- **GitHub Projects**: import [backlog.csv](backlog.csv) (cột `Owner`, `Week`, `Stories`, `Depends on`); mỗi dòng là một issue ở repo tương ứng. Cột: `Todo → In progress → In review → Done`.
- Label: `epic:<mã>`, `priority:must|should|could`, `story:US-xxx`.
- Mỗi cuối tuần: sprint review (ARC-13..18), cập nhật trạng thái; thay đổi plan thì sửa `tools/plan/*.py` và chạy lại build (script sẽ báo nếu phá ràng buộc).
- Task chỉ được bắt đầu khi phụ thuộc đã xong; task xong khi đạt cột "Xong khi" và Definition of Done.

## 6. Rủi ro chính

| Rủi ro | Ảnh hưởng | Giảm thiểu |
|---|---|---|
| SA quá tải W2–W5 (~45 giờ/tuần, kiêm 3 vai trò) | G4 trễ, dev bị chặn deploy | G4 dời W6; ưu tiên task chặn dev (local env, scaffold, hợp đồng API/event); nhờ trợ lý AI viết Terraform/Helm/workflow theo backlog và SA chỉ review |
| D2 quá tải (235 giờ, nhiều service song song) | Trễ clinical/document/audit | D3 hỗ trợ W8; cắt Should theo mục 4; không nhận thêm phạm vi |
| Phụ thuộc chéo giữa dev (D3 chờ D2, D1 chờ D2) | Dev rảnh nhưng bị chặn | Hợp đồng API/event chốt từ W1–W3 (OpenAPI, schema event) để dev dựng bản giả (fake) và tích hợp sau |
| Thiếu kinh nghiệm FHIR/MongoDB/Argo CD | Chậm W2–W5 | Thiết kế chi tiết và DDL đã chạy thử; thư viện lõi làm sớm (W2–W3) |
| Dịch vụ AI/Azure thay đổi model hoặc quota | AI không chạy | Chuỗi fallback có Ollama local; eval chạy được offline với provider giả |
| Tết dương lịch rơi vào W10 | Hardening ít người | W10 được lên kế hoạch tải thấp cho dev |
