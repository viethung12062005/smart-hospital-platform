# Kế hoạch dự án

> Trạng thái: **Draft — chờ SA/PM duyệt**. Backlog chi tiết: [backlog.md](backlog.md) · import: [backlog.csv](backlog.csv).
> Nguồn sự thật của backlog là `tools/plan/backlog.py` (tự kiểm tra: task ≤ 8h, không trùng ID,
> không xếp task trước task mà nó phụ thuộc).

## 1. Team và vai trò

| Mã | Vai trò | Phụ trách chính |
|---|---|---|
| **SA** | Solution Architect + PM + DevOps | Kiến trúc, ADR, hạ tầng (Terraform), CI/CD templates, GitOps, môi trường, phê duyệt, theo dõi tiến độ |
| **D1** | Backend .NET | Thư viện chung (auth, phân quyền, observability), **Scheduling**, **Notification**, **API Gateway**, web phần bệnh nhân/lịch |
| **D2** | Backend .NET | Thư viện chung (FHIR, messaging, audit), **Clinical**, **Document**, **Audit**, môi trường local, web phần lâm sàng |
| **D3** | AI/ML (Python) | **AI service** (router, RAG, tóm tắt, trích xuất, chatbot), **ML no-show**, web phần AI |

> Nếu team có thêm một dev nữa: giao toàn bộ epic **WEB** cho người đó, D1–D3 giảm khoảng 25–30 giờ mỗi người.

### RACI rút gọn

| Hoạt động | SA | D1 | D2 | D3 |
|---|---|---|---|---|
| ADR, hợp đồng API/sự kiện | **A/R** | C | C | C |
| Terraform, môi trường, Argo CD | **A/R** | I | I | I |
| CI templates, Helm chart chung | **A/R** | C (R: CIT-04, GOP-02, GOP-04) | C (R: GOP-05, GOP-06) | C (R: CIT-02) |
| Code service | A (review) | **R** | **R** | **R** |
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
| SA | ~237 | 42h (W2–W3) | W1–W5 dồn nặng vì platform phải xong trước; giảm hẳn từ W6 để làm PM/review |
| D1 | ~168 | 34h (W7), 32h (W3) | W3: thư viện auth/phân quyền + 3 task Helm/CI; W7: đổi lịch, phân quyền, cache, notification |
| D2 | ~170 | 32h (W8) | Đỉnh ở W8 (document, break-glass) |
| D3 | ~126 | 22h (W8–W9) | Còn dư, là người hỗ trợ khi D1/D2 chậm |

Giả định năng lực: khoảng 25 giờ/tuần cho mỗi dev. Các tuần vượt mức sẽ dùng **W10 (H1) làm vùng đệm**.
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
| SA quá tải W2–W5 (một người kiêm 3 vai trò) | G4 trễ, dev bị chặn deploy cloud | Dev vẫn làm local; đã chuyển 5 task cấu hình cho dev; GOP-09 và OBS có thể trễ mà không chặn G4 |
| Dựng môi trường ephemeral mất 20–30 phút, dễ lỗi | Mất thời gian test, tốn chi phí | Dựng theo lịch cố định (ví dụ thứ Năm hằng tuần); runbook ENV-04; cost-guard |
| Phạm vi AI rộng (4 tính năng + ML) | D3 trễ | Chatbot là Could; eval làm sớm từ S4 |
| Thiếu kinh nghiệm FHIR/MongoDB | Chậm S1–S2 | Có sẵn spike FHIR store (ADR-0003); D2 làm SHR-01..03 trước |
| Kỳ nghỉ Tết dương lịch rơi vào W10 | Hardening ít người | H1 được lên kế hoạch với tải thấp |
