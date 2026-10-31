# ADR-0015: Java (Spring Boot) cho 6 service nghiệp vụ; ai và ml giữ Python

## Trạng thái
**Accepted (2026-10-28)** — PM/SA quyết định: "bắt buộc chuyển hết sang Java", sau đó bổ sung cùng ngày: **"phần nào bắt buộc dùng Python như AI thì vẫn giữ"**. Thay thế [ADR-0002](0002-dotnet-backend.md) và [ADR-0012](0012-language-per-service.md).

**Phạm vi:** Java cho `gateway`, `scheduling`, `notification`, `clinical`, `document`, `audit`. **Python giữ nguyên** cho `ai` (LLM router, RAG, trích xuất) và `ml` (huấn luyện, phục vụ mô hình no-show, MLOps) vì hệ sinh thái AI/ML (LightGBM, pandas, pandera, MLflow, eval) không có bản Java tương đương đủ tốt.
Các ADR khác (kiến trúc, dữ liệu, AI, CI/CD, môi trường) **giữ nguyên**; chỉ đổi công nghệ hiện thực.

## Quyết định
| Hạng mục | Trước (.NET / Python) | Sau (Java) |
|---|---|---|
| Ngôn ngữ / runtime | C# .NET 10 | **Java 21 LTS** cho 6 service (nâng lên Java 25 LTS khi CI và image nền sẵn sàng); `ai`, `ml`: **Python 3.12** (không đổi) |
| Framework | ASP.NET Core Minimal API | **Spring Boot 4.1.x** (Spring MVC, virtual threads bật sẵn); `ai`/`ml`: FastAPI (không đổi) |
| Build | `dotnet` | **Maven** multi-module (một reactor: `libs/java/*` + 6 service Java), BOM tập trung ở `pom.xml` gốc; `ai`/`ml`: `pyproject` (không đổi) |
| FHIR | Firely SDK | **HAPI FHIR structures R4** (`ca.uhn.hapi.fhir`) |
| Gateway | YARP | **Spring Cloud Gateway** (server WebFlux) |
| Xác thực | JwtBearer | **Spring Security OAuth2 Resource Server** (Keycloak, ADR-0017) |
| PostgreSQL (scheduling) | Npgsql + Dapper | **JDBC (`JdbcClient`) + Flyway**, SQL tường minh |
| MongoDB | MongoDB .NET driver | **Spring Data MongoDB** / driver đồng bộ |
| Messaging | MassTransit (RabbitMQ / Service Bus) | **Spring Cloud Stream** (binder RabbitMQ local, Azure Service Bus cloud) |
| Event | CloudEvents tự viết | Envelope CloudEvents 1.0 là **record Java** (Jackson 3) + kiểm schema bằng `networknt json-schema-validator` 3.x. *(Sửa 2026-10-28: không dùng CloudEvents Java SDK vì module JSON của nó dùng Jackson 2 trong khi Spring Boot 4 dùng Jackson 3.)* |
| AI (LLM router, RAG) | Python | **Giữ Python** (FastAPI + SDK OpenAI-compatible cho DeepSeek, Gemini, Ollama; ADR-0006/0013) |
| ML no-show | scikit-learn / LightGBM, pandas, pandera | **Giữ Python** (LightGBM, pandas/DuckDB, pandera, MLflow; ADR-0007, 16-mlops) |
| OCR | Tesseract (Python) | **Giữ Python** (thuộc `ai`) |
| Test | xUnit | **JUnit 5 + AssertJ + Testcontainers 2.x**; coverage **JaCoCo**; `ai`/`ml`: pytest (không đổi) |
| Định dạng / lint | `dotnet format` | **Spotless (google-java-format)** + cảnh báo trình biên dịch = lỗi (`-Werror`); `ai`/`ml`: ruff + mypy (không đổi) |
| Image | aspnet chiseled | **Distroless Java 21** (non-root), build multi-stage; `ai`/`ml`: python slim (không đổi) |

## Phương án đã cân nhắc
- **Chuyển cả ai/ml sang Java** (Spring AI, XGBoost4J, DuckDB JDBC): đã xét ở bản đầu của ADR này; bị loại theo bổ sung của PM vì pandera, LightGBM, công cụ eval và MLOps của Python không có bản Java tương đương.
- **Gradle thay Maven**: linh hoạt hơn nhưng Maven dễ đọc, chuẩn hóa tốt hơn cho nhóm 3 dev.
- **Quarkus / Micronaut**: khởi động nhanh hơn, nhưng Spring có sẵn Spring AI, Spring Cloud Stream binder Service Bus, Spring Cloud Gateway — ít code tự viết hơn.

## Hệ quả
- Hai pipeline CI: `java-ci` (6 service + thư viện Java) và `python-ci` (ai, ml) — thay `dotnet-ci`.
- Các thư viện nền đã viết bằng .NET (phân quyền, lỗi, xác thực, identity, CloudEvents, audit, outbox/inbox/idempotency) được **chuyển 1-1 sang Java**, giữ nguyên hành vi và bộ test (kể cả test sinh từ ma trận và kiểm chứng bằng đột biến). Code .NET bị xóa sau khi bản Java đạt tương đương; code Python của `ai`/`ml` được giữ. ai/ml cần thư viện Python tương ứng (task PY-01..PY-05: JWT, consumer + inbox MongoDB, audit, lỗi) dùng **cùng hợp đồng** trong `contracts/`.
- **Không đổi**: hợp đồng (`contracts/`), Helm chart, Terraform, GitOps, bộ công cụ local; script công cụ của SA (`tools/plan`, `tools/contracts`, `dev/scripts`) là công cụ build, không phải code sản phẩm, nên giữ Python/Bash.
