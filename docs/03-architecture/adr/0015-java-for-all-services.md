# ADR-0015: Java (Spring Boot) cho toàn bộ 8 service

## Trạng thái
**Accepted (2026-10-28)** — PM/SA quyết định: "bắt buộc chuyển hết sang Java". Thay thế [ADR-0002](0002-dotnet-backend.md) và [ADR-0012](0012-language-per-service.md).
Các ADR khác (kiến trúc, dữ liệu, AI, CI/CD, môi trường) **giữ nguyên**; chỉ đổi công nghệ hiện thực.

## Quyết định
| Hạng mục | Trước (.NET / Python) | Sau (Java) |
|---|---|---|
| Ngôn ngữ / runtime | C# .NET 10; Python 3.12 (ai, ml) | **Java 21 LTS** cho cả 8 service (nâng lên Java 25 LTS khi CI và image nền sẵn sàng) |
| Framework | ASP.NET Core Minimal API; FastAPI | **Spring Boot 4.1.x** (Spring MVC, virtual threads bật sẵn) |
| Build | `dotnet`, `pip`/`pyproject` | **Maven** multi-module (một reactor: `libs/java/*` + `services/*`), BOM tập trung ở `pom.xml` gốc |
| FHIR | Firely SDK | **HAPI FHIR structures R4** (`ca.uhn.hapi.fhir`) |
| Gateway | YARP | **Spring Cloud Gateway** (server WebFlux) |
| Xác thực | JwtBearer | **Spring Security OAuth2 Resource Server** (Entra ID / Keycloak) |
| PostgreSQL (scheduling) | Npgsql + Dapper | **JDBC (`JdbcClient`) + Flyway**, SQL tường minh |
| MongoDB | MongoDB .NET driver | **Spring Data MongoDB** / driver đồng bộ |
| Messaging | MassTransit (RabbitMQ / Service Bus) | **Spring Cloud Stream** (binder RabbitMQ local, Azure Service Bus cloud) |
| Event | CloudEvents tự viết | Envelope CloudEvents 1.0 là **record Java** (Jackson 3) + kiểm schema bằng `networknt json-schema-validator` 3.x. *(Sửa 2026-10-28: không dùng CloudEvents Java SDK vì module JSON của nó dùng Jackson 2 trong khi Spring Boot 4 dùng Jackson 3.)* |
| AI (LLM router, RAG) | Python + SDK OpenAI-compatible | **Spring AI 2.0.x** (DeepSeek qua OpenAI-compatible, Gemini, Ollama, embedding bge-m3 qua Ollama); router + fallback + circuit breaker tự viết như ADR-0006/0013 |
| ML no-show | scikit-learn / LightGBM, pandas, pandera | **XGBoost4J** (huấn luyện + phục vụ), **DuckDB JDBC** (đọc/ghi Parquet, tính drift bằng SQL), **MLflow Java client** (tracking + registry), kiểm dữ liệu bằng quy tắc Java tự viết thay pandera |
| OCR | Tesseract (Python) | **Tess4J** (Tesseract qua JNI) |
| Test | xUnit, pytest | **JUnit 5 + AssertJ + Testcontainers 2.x + ArchUnit**; coverage **JaCoCo** |
| Định dạng / lint | `dotnet format`, ruff, mypy | **Spotless (google-java-format)** + cảnh báo trình biên dịch = lỗi (`-Werror`) |
| Image | aspnet chiseled; python slim | **Distroless Java 21** (non-root), build multi-stage |

## Phương án đã cân nhắc
- **Giữ Python cho ai/ml**: hệ sinh thái AI/ML của Python vẫn mạnh hơn, nhưng PM bắt buộc một ngôn ngữ. Spring AI đủ cho LLM router/RAG; XGBoost4J là cùng thư viện C++ với bản Python nên chất lượng mô hình tương đương.
- **Gradle thay Maven**: linh hoạt hơn nhưng Maven dễ đọc, chuẩn hóa tốt hơn cho nhóm 3 dev.
- **Quarkus / Micronaut**: khởi động nhanh hơn, nhưng Spring có sẵn Spring AI, Spring Cloud Stream binder Service Bus, Spring Cloud Gateway — ít code tự viết hơn.

## Hệ quả
- **Một** pipeline CI (`java-ci`) thay cho `dotnet-ci` + `python-ci`; một loại image nền để quét lỗ hổng.
- Các thư viện nền đã viết bằng .NET (phân quyền, lỗi, xác thực, identity, CloudEvents, audit, outbox/inbox/idempotency) được **chuyển 1-1 sang Java**, giữ nguyên hành vi và bộ test (kể cả test sinh từ ma trận và kiểm chứng bằng đột biến). Code .NET/Python bị xóa sau khi bản Java đạt tương đương.
- Mảng ML mất pandera/scikit-learn: kiểm dữ liệu và đánh giá theo lát cắt (slice) phải tự viết (task ML tăng nhẹ).
- **Không đổi**: hợp đồng (`contracts/`), Helm chart, Terraform, GitOps, bộ công cụ local; script công cụ của SA (`tools/plan`, `tools/contracts`, `dev/scripts`) là công cụ build, không phải code sản phẩm, nên giữ Python/Bash.
