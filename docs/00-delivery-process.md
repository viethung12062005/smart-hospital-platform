# Quy trình triển khai dự án

Dự án đi tuần tự qua các pha. **Mỗi pha có một cổng duyệt (gate)**: chưa qua gate thì không bắt
đầu pha sau. **Không ai viết code nghiệp vụ trước khi G3 được duyệt**: phải có đủ user story, thiết kế chi tiết
(data model, DB, API, event, phân quyền) và backlog chia nhỏ đến từng người. Mọi quyết định đều truy vết được về yêu cầu (xem
[02-requirements/traceability.md](02-requirements/traceability.md)).

```
Pha 1: Business Analysis ──G1──► Pha 2: Requirements Engineering ──G2──► Pha 3: Architecture
   ──G3──► Pha 4: Platform & DevOps (pipeline + môi trường) ──G4──► Pha 5: Phát triển theo sprint
   ──► Pha 6: Production engineering (load test, failure test, FinOps) ──► Bảo vệ
```

| Pha | Người chịu trách nhiệm | Đầu ra | Gate (điều kiện qua) | Map deliverable đề bài |
|---|---|---|---|---|
| 1. Business Analysis | BA | [Phân tích nghiệp vụ](01-business-analysis/business-analysis.md): vấn đề, stakeholder, quy trình as-is/to-be, quy tắc nghiệp vụ, KPI, phạm vi | **G1**: stakeholder đồng ý phạm vi và KPI | P1 |
| 2. Requirements Engineering | BA + Solution Architect | [FR/use case](02-requirements/functional-requirements.md), [NFR có số](02-requirements/non-functional-requirements.md), [ma trận phân quyền](02-requirements/access-control-matrix.md), [phân tích tải](02-requirements/workload-analysis.md), [truy vết](02-requirements/traceability.md), **[74 user story](02-requirements/user-stories.md)** | **G2**: mọi FR có tiêu chí nghiệm thu; mọi NFR có số đo và cách đo; mọi AC của FR thuộc một user story | P1 |
| 3. Architecture & thiết kế chi tiết | Solution Architect | [Kiến trúc](03-architecture/overview.md), [ADR](03-architecture/adr/README.md), **[thiết kế chi tiết](03-architecture/detailed-design/README.md)** (domain model, DB, API, event, sequence, phân quyền), threat model, **[backlog theo từng người](05-plan/project-plan.md)** | **G3**: mọi ADR Accepted; mọi API/event trong thiết kế có task; mọi story có task; DDL đã chạy thử | P1, P2, P3 |
| 4. Platform & DevOps | DevOps | IaC cho 2 môi trường cloud (uat, prod), CI/CD, quản lý secret, observability nền, template service ("walking skeleton") chạy qua toàn pipeline | **G4**: một service mẫu đi từ commit → local → uat → prod và rollback được | P3 |
| 5. Phát triển | Dev team | Service theo [backlog](05-plan/backlog.md) (task theo từng người ở [by-person](05-plan/by-person/)), mỗi story có test và deploy qua pipeline | Definition of Done (xem dưới) | P2, P5 |
| 6. Production engineering | Cả nhóm | Load test, failure test, dashboard SLO, báo cáo chi phí, AI eval | NFR đạt hoặc có giải thích | P4, P5 |

## Môi trường (chi tiết thiết kế ở Pha 4)

| Môi trường | Mục đích | Ai deploy | Dữ liệu |
|---|---|---|---|
| `local` | Máy cá nhân của dev, cụm Kubernetes nhỏ (kind) chạy toàn bộ hệ thống | Dev | Synthetic, seed tự động |
| `uat` | Cloud (ephemeral): kiểm thử tích hợp, nghiệm thu, load/failure test | Pipeline + duyệt DevOps | Synthetic quy mô lớn |
| `prod` | Cloud (ephemeral): **chỉ dựng khi demo/bảo vệ** | Pipeline + duyệt DevOps và Architect | Synthetic |

`uat` và `prod` đều **ephemeral**: dựng bằng IaC khi cần, xong thì hủy ([ADR-0009](03-architecture/adr/0009-cicd-ephemeral-environments.md), [ADR-0011](03-architecture/adr/0011-two-environments-local-kubernetes.md)). Dev tự test trên máy cá nhân.

> Toàn bộ dữ liệu ở mọi môi trường là **dữ liệu giả (synthetic)**. Không dùng dữ liệu bệnh nhân thật.

## Definition of Done (cho dev ở Pha 5)
- Story truy về ít nhất một FR/NFR; tiêu chí nghiệm thu được kiểm bằng test tự động.
- Test chạy xanh trong CI; không có lỗ hổng mức High/Critical từ quét image/dependency.
- Có trace/metric/log theo chuẩn chung; endpoint mới có trong API contract.
- Chạy được trên cụm K8s local (`make up`) và xanh trong CI (test tích hợp bằng Testcontainers). Sau Gate G4: được đưa lên UAT qua pipeline (không deploy tay) khi SA dựng UAT.
