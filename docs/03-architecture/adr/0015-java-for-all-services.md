# ADR-0015: Java (Spring Boot) for 6 business services; ai and ml stay Python

## Status
**Accepted (2026-10-28)** — PM/SA decision: "mandatory move everything to Java", then same-day addition: **"parts that must use Python like AI stay"**. Replaces [ADR-0002](0002-dotnet-backend.md) and [ADR-0012](0012-language-per-service.md).

**Scope:** Java for `gateway`, `scheduling`, `notification`, `clinical`, `document`, `audit`. **Python remains unchanged** for `ai` (LLM router, RAG, extraction) and `ml` (training, no-show model serving, MLOps) because the AI/ML ecosystem (LightGBM, pandas, pandera, MLflow, eval) has no sufficiently good Java equivalent.
Other ADRs (architecture, data, AI, CI/CD, environments) **remain unchanged**; only the implementation technology changes.

## Decision
| Item | Before (.NET / Python) | After (Java) |
|---|---|---|
| Language / runtime | C# .NET 10 | **Java 21 LTS** for 6 services (upgrade to Java 25 LTS when CI and base images are ready); `ai`, `ml`: **Python 3.12** (unchanged) |
| Framework | ASP.NET Core Minimal API | **Spring Boot 4.1.x** (Spring MVC, virtual threads enabled by default); `ai`/`ml`: FastAPI (unchanged) |
| Build | `dotnet` | **Maven** multi-module (one reactor: `libs/java/*` + 6 Java services), central BOM in the root `pom.xml`; `ai`/`ml`: `pyproject` (unchanged) |
| FHIR | Firely SDK | **HAPI FHIR structures R4** (`ca.uhn.hapi.fhir`) |
| Gateway | YARP | **Spring Cloud Gateway** (WebFlux server) |
| Authentication | JwtBearer | **Spring Security OAuth2 Resource Server** (Keycloak, ADR-0017) |
| PostgreSQL (scheduling) | Npgsql + Dapper | **JDBC (`JdbcClient`) + Flyway**, explicit SQL |
| MongoDB | MongoDB .NET driver | **Spring Data MongoDB** / synchronous driver |
| Messaging | MassTransit (RabbitMQ / Service Bus) | **Spring Cloud Stream** (RabbitMQ binder local, Azure Service Bus cloud) |
| Events | Hand-written CloudEvents | CloudEvents 1.0 envelope as a **Java record** (Jackson 3) + schema validation with `networknt json-schema-validator` 3.x. *(Amended 2026-10-28: do not use the CloudEvents Java SDK because its JSON module uses Jackson 2 while Spring Boot 4 uses Jackson 3.)* |
| AI (LLM router, RAG) | Python | **Keep Python** (FastAPI + OpenAI-compatible SDKs for DeepSeek, Gemini, Ollama; ADR-0006/0013) |
| ML no-show | scikit-learn / LightGBM, pandas, pandera | **Keep Python** (LightGBM, pandas/DuckDB, pandera, MLflow; ADR-0007, 16-mlops) |
| OCR | Tesseract (Python) | **Keep Python** (part of `ai`) |
| Test | xUnit | **JUnit 5 + AssertJ + Testcontainers 2.x**; coverage **JaCoCo**; `ai`/`ml`: pytest (unchanged) |
| Format / lint | `dotnet format` | **Spotless (google-java-format)** + compiler warnings as errors (`-Werror`); `ai`/`ml`: ruff + mypy (unchanged) |
| Image | aspnet chiseled | **Distroless Java 21** (non-root), multi-stage build; `ai`/`ml`: python slim (unchanged) |

## Alternatives considered
- **Move ai/ml to Java too** (Spring AI, XGBoost4J, DuckDB JDBC): considered in the first version of this ADR; rejected per the PM's addition because pandera, LightGBM, and Python's eval and MLOps tooling have no Java equivalent.
- **Gradle instead of Maven**: more flexible but Maven is easier to read and more standardized for a 3-dev team.
- **Quarkus / Micronaut**: faster startup, but Spring has Spring AI, the Spring Cloud Stream Service Bus binder, and Spring Cloud Gateway — less custom code.

## Consequences
- Two CI pipelines: `java-ci` (6 services + Java libraries) and `python-ci` (ai, ml) — replacing `dotnet-ci`.
- Base libraries already written in .NET (authorization, errors, authentication, identity, CloudEvents, audit, outbox/inbox/idempotency) are **ported 1:1 to Java**, preserving behavior and test suites (including tests generated from the matrix and mutation-verified). .NET code is deleted after the Java version reaches parity; Python code for `ai`/`ml` is kept. ai/ml need corresponding Python libraries (tasks PY-01..PY-05: JWT, consumer + MongoDB inbox, audit, errors) using the **same contracts** in `contracts/`.
- **Unchanged**: contracts (`contracts/`), Helm charts, Terraform, GitOps, local toolkit; SA tooling scripts (`tools/plan`, `tools/contracts`, `dev/scripts`) are build tools, not product code, so they remain Python/Bash.
