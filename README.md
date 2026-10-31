# Smart Hospital Platform

Đồ án môn Cloud Application Development: **Project 5 — Smart Hospital Platform**.
Nền tảng hỗ trợ luồng khám ngoại trú (hồ sơ bệnh nhân, lịch bác sĩ, đặt lịch, lần khám, tài liệu lâm sàng,
nhắc lịch), bảo vệ dữ liệu nhạy cảm, có trợ lý AI và mô hình ML.

> Chỉ dùng **dữ liệu bệnh nhân giả (synthetic)** ở mọi môi trường.

## Bắt đầu nhanh (dev)

1. Đọc **[docs/onboarding.md](docs/onboarding.md)**: cài máy, chạy hệ thống, phần việc của từng người.
2. Chạy toàn bộ hệ thống trên máy: `make -C dev quickstart` (chi tiết: [dev/README.md](dev/README.md)).
3. Quy trình task → nhánh → PR và các lệnh kiểm tra: **[CONTRIBUTING.md](CONTRIBUTING.md)**.

| Thư mục | Nội dung |
|---|---|
| `services/` | 8 service: gateway, scheduling, notification, clinical, document, audit (Java 21 / Spring Boot 4), ai, ml (Python 3.12) |
| `libs/java/` | 7 thư viện dùng chung (lỗi chuẩn, JWT, phân quyền, CloudEvents, outbox/inbox, Idempotency-Key) |
| `contracts/` | hợp đồng API (OpenAPI), event (JSON Schema), ma trận phân quyền, danh mục mã, script DB |
| `dev/` | cụm Kubernetes local (kind) + phụ thuộc, lệnh `make -C dev ...` |
| `docs/` | phân tích, yêu cầu, kiến trúc ([sơ đồ](docs/03-architecture/overview.md)), DevOps, kế hoạch |
| `tools/` | sinh khung service, kiểm hợp đồng, sinh kế hoạch |

Build và test toàn bộ phần Java: `mvn -B verify` (cần JDK 21, Maven 3.9, Docker).

## Quy trình và trạng thái

| Pha | Tài liệu | Trạng thái |
|---|---|---|
| 0. Quy trình | [docs/00-delivery-process.md](docs/00-delivery-process.md) | — |
| 1. Business Analysis | [docs/01-business-analysis](docs/01-business-analysis/business-analysis.md) | Approved (G1) |
| 2. Requirements | [FR](docs/02-requirements/functional-requirements.md) · [NFR](docs/02-requirements/non-functional-requirements.md) · [Phân quyền](docs/02-requirements/access-control-matrix.md) · [Tải](docs/02-requirements/workload-analysis.md) · [Truy vết](docs/02-requirements/traceability.md) | Approved (G2) |
| 2b. User story | [75 user story](docs/02-requirements/user-stories.md) | Approved (G2) |
| 3. Architecture | [Tổng quan](docs/03-architecture/overview.md) · [ADR](docs/03-architecture/adr/README.md) · [Thiết kế chi tiết](docs/03-architecture/detailed-design/README.md) | Approved (G3) |
| 4. Platform & DevOps | [Kế hoạch](docs/04-devops/README.md) · [Nhật ký từng bước](docs/04-devops/progress-log.md) · [Chạy local](dev/README.md) | Đang làm: thư viện, khung 8 service, dev kit đã xong |
| Kế hoạch dự án | [Plan](docs/05-plan/project-plan.md) · [Backlog](docs/05-plan/backlog.md) · [CSV](docs/05-plan/backlog.csv) · việc từng người: [SA](docs/05-plan/by-person/SA.md), [D1](docs/05-plan/by-person/D1.md), [D2](docs/05-plan/by-person/D2.md), [D3](docs/05-plan/by-person/D3.md) | Approved |
| 5. Phát triển | [Onboarding](docs/onboarding.md) · [CONTRIBUTING](CONTRIBUTING.md) | Sẵn sàng nhận task |
