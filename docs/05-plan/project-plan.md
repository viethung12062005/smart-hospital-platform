# Kế hoạch dự án

> Trạng thái: **Draft — chờ SA/PM duyệt**. Backlog chi tiết: [backlog.md](backlog.md) · import: [backlog.csv](backlog.csv).
> Nguồn sự thật của backlog là `tools/plan/backlog.py` (tự kiểm tra: task ≤ 8h, không trùng ID,
> không xếp task trước task mà nó phụ thuộc).

## 1. Team và vai trò

| Mã | Vai trò | Phụ trách chính |
|---|---|---|
| **SA** | Solution Architect + PM + DevOps | **Mọi thứ ngoài logic nghiệp vụ**: kiến trúc, ADR, hợp đồng API, hạ tầng, CI/CD, Helm/GitOps, môi trường local và cloud, scaffold service, gateway, observability, load/failure test, backup, phê duyệt, tiến độ |
| **D1** | Dev .NET (chỉ logic) | Logic auth/phân quyền dùng chung, **Scheduling**, **Notification**, waiting room, web phần bệnh nhân/lịch |
| **D2** | Dev .NET (chỉ logic) | Logic FHIR store, messaging/outbox, audit dùng chung, **Clinical**, **Document**, **Audit**, sinh dữ liệu giả, web phần lâm sàng |
| **D3** | Dev AI/ML Python (chỉ logic) | **AI service** (router, RAG, tóm tắt, trích xuất, chatbot), **ML no-show** (dữ liệu, huấn luyện, serving, drift), web phần AI |

**Quy tắc phân công:** dev nhận service đã được SA scaffold sẵn (code khung, Dockerfile, pipeline, Helm values, kết nối
DB/bus/observability) và chỉ viết logic + test của logic. Dev không sửa pipeline, Dockerfile, Helm hay hạ tầng; cần thay đổi
thì mở issue cho SA.

> Nếu team có thêm một dev nữa: giao toàn bộ epic **WEB** (trừ WEB-10) cho người đó, D1–D3 giảm khoảng 20–30 giờ mỗi người.

### RACI rút gọn

| Hoạt động | SA | D1 | D2 | D3 |
|---|---|---|---|---|
| ADR, hợp đồng API/sự kiện | **A/R** | C | C | C |
| Terraform, môi trường, Argo CD | **A/R** | I | I | I |
| CI/CD, Helm, scaffold service, môi trường local | **A/R** | I | I | I |
| Logic nghiệp vụ + test | A (review) | **R** | **R** | **R** |
| Phê duyệt deploy staging/prod, dựng/hủy môi trường | **A/R** | I | I | I |

## 2. Repository (ADR-0010, chờ duyệt)

| Repo | Ai làm việc chính |
|---|---|
| `smart-hospital-platform` (repo hiện tại) | D1, D2, D3: `services/`, `libs/`, `web/`, `docs/` |
| `shp-platform-templates` | SA: reusable workflows, Helm chart chung |
| `shp-infra` | SA: Terraform, workflow dựng/hủy môi trường |
| `shp-gitops` | SA + bot: Argo CD, add-on, values theo môi trường |

## 3. Lộ trình 12 tuần và các cổng

```
W1  S0   G1/G2/G3 duyệt, tạo repo, onboarding, chuẩn coding
W2  P1   Platform 1: bootstrap Azure, CI templates, Helm chart       | Dev: local env, skeleton, thư viện
W3  P2   Platform 2: network/AKS, GitOps add-on, policy              | Dev: thư viện, ML dataset
W4  P3   Platform 3: module dữ liệu, threat model   ║ S1 dev: scaffold service, FHIR store
W5  P4   Identity, env-provision/destroy, ── G4 ──  ║ S2
W6–W9    S3–S6 phát triển tính năng; deploy cloud qua pipeline sau G4
W10 H1   Hardening: load test, failure test, backup/restore
W11 H2   AI eval, FinOps, rà soát bảo mật
W12 DEF  Kịch bản demo, slide, diễn tập trên prod ephemeral
```

| Cổng | Tuần | Điều kiện | Ai duyệt |
|---|---|---|---|
| G1 | W1 | Phạm vi + KPI được chốt | SA (PM) |
| G2 | W1 | FR có AC, NFR có số | SA |
| G3 | W1 | ADR 0001–0010 Accepted | SA (Architect) |
| G4 | W5 | Service mẫu đi commit → dev → staging → prod chỉ qua pipeline; rollback < 5 phút; image chưa ký bị chặn | SA (DevOps) |
| Demo nội bộ | cuối mỗi sprint | Story Done theo DoD | SA |

**Trước G4, dev chỉ chạy local** (docker compose / kind với cùng Helm chart). Sau G4, mỗi lần cần test trên cloud thì SA
dựng môi trường ephemeral, test xong thì hủy.

## 4. Tải công việc (giờ, xem bảng chi tiết trong backlog.md)

| Người | Tổng | Đỉnh | Ghi chú |
|---|---|---|---|
| SA | ~314 | 46h (W2–W3), ~40h đến W6 | Toàn bộ phần ngoài logic; platform dồn W1–W6, sau đó chủ yếu review, PM, production engineering |
| D1 | ~127 | 34h (W7) | W7: đổi lịch, phân quyền scheduling, cache, notification |
| D2 | ~146 | 32h (W8) | W8: document, break-glass, phân quyền clinical |
| D3 | ~114 | 22h (W8–W9) | Còn dư, là người hỗ trợ logic khi D1/D2 chậm |

Giả định năng lực: khoảng 25 giờ/tuần cho mỗi dev. Các tuần vượt mức sẽ dùng **W10 (H1) làm vùng đệm**.
SA ở mức ~40–46 giờ/tuần trong 6 tuần đầu là **rủi ro lớn nhất của kế hoạch** (xem mục 6).
Task **Could** (GW-03, AI-13, WEB-08) sẽ bị cắt trước nếu trễ.

## 5. Cách theo dõi

- **GitHub Projects** (một project chung cho 4 repo): cột `Todo → In progress → In review → Done`, field
  `Sprint`, `Owner`, `Estimate`, `Epic`. Import từ `backlog.csv`; mỗi task là một issue ở repo tương ứng.
- Label: `epic:<mã>`, `priority:must|should|could`, `type:feature|infra|docs|test`.
- Quy ước: tên nhánh `<ID>-mo-ta-ngan` (ví dụ `SCH-08-book-transaction`), PR ghi `Closes <issue>`, một PR ≈ một task.
- Mỗi cuối tuần: sprint review (ARC-13..18), cập nhật trạng thái, rủi ro, chỉnh lại backlog bằng `tools/plan/backlog.py`.

## 6. Rủi ro chính

| Rủi ro | Ảnh hưởng | Giảm thiểu |
|---|---|---|
| SA quá tải W1–W6 (~40–46h/tuần, kiêm 3 vai trò và mọi phần ngoài logic) | G4 trễ, dev bị chặn | Ưu tiên task chặn dev (local env, skeleton, scaffold, hợp đồng API) trước; canary, threat model chi tiết, observability nâng cao dời sau G4; dùng AI coding assistant để viết Terraform/Helm/workflow theo backlog |
| Dựng môi trường ephemeral mất 20–30 phút, dễ lỗi | Mất thời gian test, tốn chi phí | Dựng theo lịch cố định (ví dụ thứ Năm hằng tuần); runbook ENV-04; cost-guard |
| Phạm vi AI rộng (4 tính năng + ML) | D3 trễ | Chatbot là Could; eval làm sớm từ S4 |
| Thiếu kinh nghiệm FHIR/MongoDB | Chậm S1–S2 | Có sẵn spike FHIR store (ADR-0003); D2 làm SHR-01..03 trước |
| Kỳ nghỉ Tết dương lịch rơi vào W10 | Hardening ít người | H1 được lên kế hoạch với tải thấp |
