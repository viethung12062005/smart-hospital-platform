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
- `SmartHospital.Fhir.Postgres` (cho `scheduling`): **một bảng cho mỗi resource type**, có cột kiểu cho trường tìm kiếm/ràng buộc và `content jsonb` chứa resource đầy đủ; ràng buộc nghiệp vụ nằm ở DB (exclusion constraint, unique partial index).
- `SmartHospital.Fhir.Mongo` (cho `clinical`, `document`): **một collection cho mỗi resource type**, document gồm phần quản trị, `resource` (FHIR) và `search` (trường chuẩn hóa); shard key `patientId`.

Chọn bảng/collection riêng cho từng resource (thay cho một bảng search-index chung kiểu EAV) vì truy vấn dùng index
trực tiếp, có ràng buộc DB thật và dễ giải thích. Chi tiết: `03-architecture/detailed-design/02` và `03`.

## Phương án đã cân nhắc
- **Azure Health Data Services FHIR**: managed, đầy đủ chuẩn; nhưng khóa vào Azure, không kiểm soát
  được transaction đặt lịch (slot + appointment + outbox), chi phí cố định cao cho đồ án.
- **HAPI FHIR server**: đầy đủ nhưng nặng (JVM, hàng trăm bảng), khó giải thích khi bảo vệ.
- **Bảng quan hệ thuần**: dễ query nhưng mất tính chuẩn FHIR mà nhóm muốn giữ.

## Hệ quả
- Chỉ hỗ trợ tập con FHIR REST (read, create, update, search một số tham số, `$book`, `$cancel`).
- Có thể export/đồng bộ sang Azure Health Data Services sau này nếu cần interoperability.
