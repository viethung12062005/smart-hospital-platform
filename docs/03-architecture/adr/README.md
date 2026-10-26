# Architecture Decision Records

Mỗi ADR theo format: Bối cảnh → Quyết định → Phương án đã cân nhắc → Hệ quả.

| # | Quyết định | Trạng thái |
|---|---|---|
| [0001](0001-microservice-boundaries.md) | Ranh giới microservice theo đặc tính tải, tối đa 8 deployable | Accepted |
| [0002](0002-dotnet-backend.md) | .NET 10 cho service nghiệp vụ, Python cho AI/ML | Accepted |
| [0003](0003-fhir-native-store.md) | Tự xây FHIR store (lõi không phụ thuộc DB) thay vì Azure Health Data Services | Accepted |
| [0004](0004-azure-first-portable.md) | AKS + GitOps làm nền tảng chạy, Azure-first nhưng portable | Accepted |
| [0005](0005-booking-consistency.md) | Chống double-booking bằng optimistic concurrency + idempotency key | Accepted |
| [0006](0006-llm-router-fallback.md) | LLM router đa model với fallback và circuit breaker | Accepted |
| [0007](0007-no-show-ml-mlops.md) | Mô hình ML dự đoán no-show để thể hiện MLOps | Accepted |
| [0008](0008-polyglot-persistence.md) | Chọn kho dữ liệu theo đặc tính tải từng service | Accepted |
| [0009](0009-cicd-ephemeral-environments.md) | GitHub Actions (CI) + Argo CD/Helm (CD), môi trường ephemeral, cổng phê duyệt | Accepted |
| [0010](0010-repository-strategy.md) | 4 repository: ứng dụng, templates, infra, gitops | Accepted |
