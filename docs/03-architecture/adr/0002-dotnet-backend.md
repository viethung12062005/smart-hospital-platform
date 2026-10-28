# ADR-0002: .NET 10 cho service nghiệp vụ, Python cho AI/ML

## Trạng thái
**Superseded (2026-10-28)** bởi [ADR-0015](0015-java-for-all-services.md): toàn bộ service chuyển sang Java. Giữ lại để truy vết.

## Quyết định
- Service nghiệp vụ: **.NET 10 (LTS)**, ASP.NET Core Minimal API, Npgsql + Dapper (SQL tường minh,
  dễ tối ưu và giải thích), Firely SDK (`Hl7.Fhir.R4`) cho mô hình và serialize FHIR.
- AI/ML: **Python** (FastAPI, scikit-learn/LightGBM, MLflow) vì hệ sinh thái AI tốt hơn hẳn.

## Phương án đã cân nhắc
- **Spring Boot + HAPI FHIR**: HAPI rất trưởng thành, nhưng .NET tích hợp tự nhiên với Azure
  (Managed Identity, App Insights) và image nhỏ, khởi động nhanh (quan trọng khi HPA/KEDA scale lúc 07:00).
- **Node.js/TypeScript**: hệ sinh thái FHIR tốt (Medplum), nhưng kiểu
  tĩnh mạnh hơn giúp ít lỗi hơn với dữ liệu y tế.
- **EF Core** thay Dapper: tiện, nhưng truy vấn JSONB/search index cần SQL thủ công.

## Hệ quả
Hai ngôn ngữ → hai pipeline CI, hai bộ image cơ sở cần quét lỗ hổng.
