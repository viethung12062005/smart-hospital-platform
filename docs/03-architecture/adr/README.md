# Architecture Decision Records

Mỗi ADR theo format: Bối cảnh → Quyết định → Phương án đã cân nhắc → Hệ quả.

| # | Quyết định | Trạng thái |
|---|---|---|
| [0001](0001-microservice-boundaries.md) | Ranh giới microservice theo đặc tính tải, tối đa 8 deployable | Proposed |
| [0002](0002-dotnet-backend.md) | .NET 10 cho service nghiệp vụ, Python cho AI/ML | Proposed |
| [0003](0003-fhir-native-store.md) | Tự xây FHIR store (lõi không phụ thuộc DB) thay vì Azure Health Data Services | Proposed |
| [0004](0004-azure-first-portable.md) | AKS + GitOps làm nền tảng chạy, Azure-first nhưng portable | Proposed |
| [0005](0005-booking-consistency.md) | Chống double-booking bằng optimistic concurrency + idempotency key | Proposed |
| [0006](0006-llm-router-fallback.md) | LLM router đa model với fallback và circuit breaker | Proposed |
| [0007](0007-no-show-ml-mlops.md) | Mô hình ML dự đoán no-show để thể hiện MLOps | Proposed |
| [0008](0008-polyglot-persistence.md) | Chọn kho dữ liệu theo đặc tính tải từng service | Proposed |
