# Quy trình triển khai dự án

Dự án đi tuần tự qua các pha. **Mỗi pha có một cổng duyệt (gate)**: chưa qua gate thì không bắt
đầu pha sau. Mọi quyết định đều truy vết được về yêu cầu (xem
[02-requirements/traceability.md](02-requirements/traceability.md)).

```
Pha 1: Business Analysis ──G1──► Pha 2: Requirements Engineering ──G2──► Pha 3: Architecture
   ──G3──► Pha 4: Platform & DevOps (pipeline + môi trường) ──G4──► Pha 5: Phát triển theo sprint
   ──► Pha 6: Production engineering (load test, failure test, FinOps) ──► Bảo vệ
```

| Pha | Người chịu trách nhiệm | Đầu ra | Gate (điều kiện qua) | Map deliverable đề bài |
|---|---|---|---|---|
| 1. Business Analysis | BA | [Phân tích nghiệp vụ](01-business-analysis/business-analysis.md): vấn đề, stakeholder, quy trình as-is/to-be, quy tắc nghiệp vụ, KPI, phạm vi | **G1**: stakeholder đồng ý phạm vi và KPI | P1 |
| 2. Requirements Engineering | BA + Solution Architect | [FR/use case](02-requirements/functional-requirements.md), [NFR có số](02-requirements/non-functional-requirements.md), [ma trận phân quyền](02-requirements/access-control-matrix.md), [phân tích tải](02-requirements/workload-analysis.md), [truy vết](02-requirements/traceability.md) | **G2**: mọi FR có tiêu chí nghiệm thu; mọi NFR có số đo và cách đo | P1 |
| 3. Architecture | Solution Architect | [Kiến trúc](03-architecture/overview.md), [ADR](03-architecture/adr/README.md), data model, API contract, threat model | **G3**: mọi ADR chuyển sang Accepted, mỗi ADR truy về NFR/FR | P1, P2, P3 |
| 4. Platform & DevOps | DevOps | IaC cho 3 môi trường, CI/CD, quản lý secret, observability nền, template service ("walking skeleton") chạy qua toàn pipeline | **G4**: một service mẫu đi từ commit → dev → staging → prod và rollback được | P3 |
| 5. Phát triển | Dev team | Service theo backlog, mỗi story có test và deploy qua pipeline | Definition of Done (xem dưới) | P2, P5 |
| 6. Production engineering | Cả nhóm | Load test, failure test, dashboard SLO, báo cáo chi phí, AI eval | NFR đạt hoặc có giải thích | P4, P5 |

## Môi trường (chi tiết thiết kế ở Pha 4)

| Môi trường | Mục đích | Ai deploy | Dữ liệu |
|---|---|---|---|
| `local` | Dev chạy toàn hệ thống bằng docker compose | Dev | Synthetic, seed tự động |
| `dev` | Tích hợp liên tục; mỗi merge vào `main` tự deploy | Pipeline | Synthetic, có thể xóa bất cứ lúc nào |
| `staging` | Giống prod; load test, failure test, UAT | Pipeline + duyệt thủ công | Synthetic quy mô lớn |
| `prod` | Môi trường demo bảo vệ | Pipeline + duyệt của Architect/DevOps | Synthetic |

> Toàn bộ dữ liệu ở mọi môi trường là **dữ liệu giả (synthetic)**. Không dùng dữ liệu bệnh nhân thật.

## Definition of Done (cho dev ở Pha 5)
- Story truy về ít nhất một FR/NFR; tiêu chí nghiệm thu được kiểm bằng test tự động.
- Test chạy xanh trong CI; không có lỗ hổng mức High/Critical từ quét image/dependency.
- Có trace/metric/log theo chuẩn chung; endpoint mới có trong API contract.
- Đã deploy lên `dev` qua pipeline (không deploy tay).
