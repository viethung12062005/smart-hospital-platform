# ADR-0003: Tự xây FHIR store, lõi không phụ thuộc DB

## Trạng thái
Proposed — chờ chốt sau khi requirements (docs/02-requirements) được duyệt.

## Bối cảnh
Nhóm muốn mô hình FHIR-native giống Medplum (chuẩn interoperability y tế). Azure có dịch vụ
managed FHIR (Azure Health Data Services). Các service lại dùng kho dữ liệu khác nhau (ADR-0008).

## Quyết định
Building block `SmartHospital.Fhir` chứa phần **không phụ thuộc DB**:
- Serialize/parse FHIR R4 (Firely SDK).
- Định nghĩa SearchParameter và trích giá trị tìm kiếm từ resource (`SearchParameterRegistry`).
- Phân tích query FHIR (`status=free&start=ge2026-11-01`) thành tiêu chí có kiểu.
- Hợp đồng `IFhirStore` / `IFhirSession`: read, create, update có `versionId` (optimistic
  concurrency), search, outbox event, idempotency, commit.

Mỗi kho dữ liệu là một provider:
- `SmartHospital.Fhir.Postgres` — resource JSONB + history + bảng search-parameter (cho `scheduling`).
- `SmartHospital.Fhir.Mongo` — document theo patient compartment (cho `clinical`, làm sau).

## Phương án đã cân nhắc
- **Azure Health Data Services FHIR**: managed, đầy đủ chuẩn; nhưng khóa vào Azure, không kiểm soát
  được transaction đặt lịch (slot + appointment + outbox), chi phí cố định cao cho đồ án.
- **HAPI FHIR server**: đầy đủ nhưng nặng (JVM, hàng trăm bảng), khó giải thích khi bảo vệ.
- **Bảng quan hệ thuần**: dễ query nhưng mất tính chuẩn FHIR mà nhóm muốn giữ.

## Hệ quả
- Chỉ hỗ trợ tập con FHIR REST (read, create, update, search một số tham số, `$book`, `$cancel`).
- Có thể export/đồng bộ sang Azure Health Data Services sau này nếu cần interoperability.
