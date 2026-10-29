# Architecture Decision Records

Mỗi ADR theo format: Bối cảnh → Quyết định → Phương án đã cân nhắc → Hệ quả.

| # | Quyết định | Trạng thái |
|---|---|---|
| [0001](0001-microservice-boundaries.md) | Ranh giới microservice theo đặc tính tải, tối đa 8 deployable | Accepted |
| [0002](0002-dotnet-backend.md) | .NET 10 cho service nghiệp vụ, Python cho AI/ML | **Superseded** bởi 0015 |
| [0003](0003-fhir-native-store.md) | Tự xây FHIR store (lõi không phụ thuộc DB) thay vì Azure Health Data Services | Accepted |
| [0004](0004-azure-first-portable.md) | AKS + GitOps làm nền tảng chạy, Azure-first nhưng portable | Accepted |
| [0005](0005-booking-consistency.md) | Chống double-booking bằng optimistic concurrency + idempotency key | Accepted |
| [0006](0006-llm-router-fallback.md) | LLM router đa model với fallback và circuit breaker | Accepted |
| [0007](0007-no-show-ml-mlops.md) | Mô hình ML dự đoán no-show để thể hiện MLOps | Accepted |
| [0008](0008-polyglot-persistence.md) | Chọn kho dữ liệu theo đặc tính tải từng service | Accepted |
| [0009](0009-cicd-ephemeral-environments.md) | GitHub Actions (CI) + Argo CD/Helm (CD), môi trường ephemeral, cổng phê duyệt | Accepted |
| [0010](0010-repository-strategy.md) | 4 repository: ứng dụng, templates, infra, gitops | Accepted |
| [0011](0011-two-environments-local-kubernetes.md) | 2 môi trường cloud (UAT, prod) + dev trên Kubernetes cục bộ | Accepted |
| [0012](0012-language-per-service.md) | Đánh giá lại ngôn ngữ từng service: giữ .NET + Python | **Superseded** bởi 0015 |
| [0013](0013-llm-deepseek-gemini-local-embedding.md) | DeepSeek chính, Gemini free tier dự phòng, embedding local; thay chuỗi model của 0006 | Accepted |
| [0014](0014-database-per-service-review.md) | Rà soát CSDL từng service: PostgreSQL chỉ cho scheduling; notification và ml chuyển MongoDB (+ Parquet) | Accepted |
| [0015](0015-java-for-all-services.md) | Java 21 + Spring Boot 4.1 cho 6 service nghiệp vụ; ai và ml giữ Python (thay 0002, 0012) | Accepted |
| [0016](0016-frontend-web-app.md) | Một web app PWA (React + TypeScript + Vite + Mantine 7) trong repo ứng dụng; tái dùng có chọn lọc portal PoC | Proposed |
