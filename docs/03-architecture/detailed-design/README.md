# Thiết kế chi tiết (Detailed Design)

> Trạng thái: **Draft — chờ duyệt cùng G3**. Không viết code cho tới khi bộ tài liệu này được duyệt.
> Kiến trúc tổng quan: [../overview.md](../overview.md). Yêu cầu: [../../02-requirements](../../02-requirements/functional-requirements.md).

| # | Tài liệu | Nội dung |
|---|---|---|
| 01 | [Domain model](01-domain-model.md) | Bounded context, quyền sở hữu dữ liệu, profile FHIR từng resource (trường, bắt buộc, value set), sơ đồ quan hệ |
| 02 | [DB scheduling (PostgreSQL)](02-db-scheduling.md) | DDL đầy đủ, ràng buộc chống double-booking, index, truy vấn chính, retention |
| 03 | [DB clinical + document (MongoDB)](03-db-clinical-document.md) | Collection, hình dạng document, index, shard key, validation, history, projection |
| 04 | [DB notification + ML (PostgreSQL)](04-db-notification-ml.md) | Bảng nhắc lịch, lần gửi, chống trùng; feature store và log dự đoán |
| 05 | [Lưu trữ audit + Blob](05-storage-audit-blob.md) | Bản ghi audit, WORM + chuỗi hash, bảng Log Analytics, bố cục Blob |
| 06 | [Dữ liệu AI](06-data-ai.md) | Index AI Search, hội thoại, kho tri thức, job trích xuất |
| 07 | [Quy ước API](07-api-conventions.md) | Định tuyến gateway, xác thực, lỗi, phân trang, version, idempotency, concurrency |
| 08 | [API scheduling](08-api-scheduling.md) | Từng endpoint: quyền, request, response, lỗi |
| 09 | [API clinical](09-api-clinical.md) | 〃 |
| 10 | [API document](10-api-document.md) | 〃 |
| 11 | [API notification, audit, ML](11-api-notification-audit-ml.md) | 〃 |
| 12 | [API AI](12-api-ai.md) | 〃 |
| 13 | [Event catalog](13-events.md) | CloudEvents, topic, producer/consumer, payload, retry/DLQ |
| 14 | [Sequence diagram](14-sequences.md) | Các luồng chính end-to-end |
| 15 | [Thiết kế phân quyền](15-authorization.md) | Cách thực thi ma trận phân quyền, quan hệ điều trị, break-glass, identity link |

## Quy ước chung (áp dụng cho mọi tài liệu)

| Chủ đề | Quy ước |
|---|---|
| Phiên bản FHIR | R4 (4.0.1), JSON |
| ID resource | UUIDv7 dạng chuỗi (`0192f8a4-...`), sinh ở service; tăng theo thời gian nên thân thiện với B-tree |
| Thời gian | Lưu UTC (`timestamptz` / ISO 8601 `Z`); hiển thị và sinh slot theo `Asia/Ho_Chi_Minh` |
| Canonical URL | `https://shp.example.org/fhir/...` (domain giả định) |
| Identifier system | `urn:shp:mrn` (mã bệnh nhân `BN-YYYY-NNNNNN`), `urn:shp:practitioner` (mã nhân viên `NV-NNNN`), `urn:shp:subject` (định danh tài khoản OIDC) |
| Code system | ICD-10 `http://hl7.org/fhir/sid/icd-10`, LOINC `http://loinc.org`, chuyên khoa `https://shp.example.org/fhir/CodeSystem/specialty`, lý do hủy `https://shp.example.org/fhir/CodeSystem/cancel-reason` |
| Tiền tố tên | Event `shp.<context>.<event>.v<N>`; bảng/collection snake_case số ít cho Postgres, số nhiều cho MongoDB |
| Dữ liệu | Chỉ dữ liệu giả; event và log **không chứa PII** (chỉ ID) |

## Thay đổi so với kiến trúc tổng quan (phát hiện khi thiết kế chi tiết)

| # | Thay đổi | Lý do | Cập nhật |
|---|---|---|---|
| C1 | `DocumentReference` thuộc **document service** (MongoDB database `document`), không thuộc clinical | Một resource chỉ có một chủ sở hữu; document cần trạng thái quét mã độc riêng | ADR-0008 |
| C2 | Lưu FHIR theo **bảng/collection riêng cho mỗi resource type, có cột tìm kiếm có kiểu** + JSON đầy đủ, thay vì một bảng search-index chung | Truy vấn dùng index B-tree trực tiếp, có ràng buộc DB thật (chống double-booking), nhanh hơn kiểu EAV | ADR-0003 |
| C3 | Chống double-booking thêm **unique partial index** trên `appointment(slot_id)` và **advisory lock theo bệnh nhân** cho BR-03 | Bảo đảm ở tầng DB ngay cả khi code sai | ADR-0005 |
| C4 | Notification lưu trạng thái nhắc lịch trong **PostgreSQL** (database `notification`; ML cũng dùng PostgreSQL database `ml`) và lập lịch bằng bảng + worker `SKIP LOCKED` | Hủy nhắc chỉ là cập nhật một dòng; chạy giống nhau trên RabbitMQ và Service Bus | ADR-0008 |
| C5 | ML tự duy trì thống kê đi khám từ event và **phát `NoShowScored`**; notification nghe event này thay vì gọi API ML | ML lỗi không ảnh hưởng nhắc lịch (AC-15.1), không phụ thuộc đồng bộ | ADR-0007 |
| C6 | Check-in thuộc scheduling (`Appointment.status = arrived`); clinical nghe event để tự tạo `Encounter` | Một nguồn sự thật cho trạng thái lịch hẹn | 01, 13 |
| C7 | Liên kết tài khoản ↔ `Patient`/`Practitioner` lưu ở clinical (`identity_links`); service khác tra qua API nội bộ có cache, không dùng custom claim | Entra ID và Keycloak hoạt động giống nhau, không cần cấu hình claim riêng | 15 |
| C8 | Event dùng định dạng **CloudEvents 1.0** | Chuẩn mở, mang `traceparent` xuyên service | 13 |
