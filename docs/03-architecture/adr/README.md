# Architecture Decision Records

Each ADR follows the format: Context → Decision → Alternatives considered → Consequences.

| # | Decision | Status |
|---|---|---|
| [0001](0001-microservice-boundaries.md) | Microservice boundaries by load characteristics, at most 8 deployables | Accepted |
| [0002](0002-dotnet-backend.md) | .NET 10 for business services, Python for AI/ML | **Superseded** by 0015 |
| [0003](0003-fhir-native-store.md) | Build our own FHIR store (DB-independent core) instead of Azure Health Data Services | Accepted |
| [0004](0004-azure-first-portable.md) | AKS + GitOps as the runtime platform, Azure-first but portable | Accepted |
| [0005](0005-booking-consistency.md) | Prevent double-booking with optimistic concurrency + idempotency key | Accepted |
| [0006](0006-llm-router-fallback.md) | Multi-model LLM router with fallback and circuit breaker | Accepted |
| [0007](0007-no-show-ml-mlops.md) | ML model predicting no-show to demonstrate MLOps | Accepted |
| [0008](0008-polyglot-persistence.md) | Choose data stores by each service's load characteristics | Accepted |
| [0009](0009-cicd-ephemeral-environments.md) | GitHub Actions (CI) + Argo CD/Helm (CD), ephemeral environments, approval gates | Accepted |
| [0010](0010-repository-strategy.md) | 4 repositories: application, templates, infra, gitops | Accepted |
| [0011](0011-two-environments-local-kubernetes.md) | 2 cloud environments (UAT, prod) + dev on local Kubernetes | Accepted |
| [0012](0012-language-per-service.md) | Re-evaluate language per service: keep .NET + Python | **Superseded** by 0015 |
| [0013](0013-llm-deepseek-gemini-local-embedding.md) | DeepSeek primary, Gemini free tier fallback, local embeddings; replaces 0006's model chain | Accepted |
| [0014](0014-database-per-service-review.md) | Re-review each service's database: PostgreSQL only for scheduling; notification and ml move to MongoDB (+ Parquet) | Accepted |
| [0015](0015-java-for-all-services.md) | Java 21 + Spring Boot 4.1 for 6 business services; ai and ml stay Python (replaces 0002, 0012) | Accepted |
| [0016](0016-frontend-web-app.md) | One PWA web app (React + TypeScript + Vite + Mantine 7) in the application repo; selectively reuse the PoC portal | Proposed |
| [0017](0017-keycloak-identity-everywhere.md) | Keycloak is the single identity provider for application users in every environment (replaces Entra ID in the cloud) | Accepted |
| [0018](0018-profile-links-and-claim.md) | One account manages multiple profiles (children under 16, authorized adults), self-claims existing records via OTP, shared OTP service (Zalo first, SMS after) | Accepted |
