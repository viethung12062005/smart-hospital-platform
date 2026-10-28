# Chuẩn coding và quy ước cho service (ARC-09)

Áp dụng cho mọi service. Quy ước API chi tiết ở [07-api-conventions](03-architecture/detailed-design/07-api-conventions.md); tài liệu này gom các quy tắc còn lại để dev khỏi phải đoán.

## 1. Cấu trúc thư mục

```
services/<svc>/
  pom.xml | pyproject.toml
  Dockerfile                       # SA quản lý, dev không sửa
  deploy/values-local.yaml         # dev được thêm biến môi trường không nhạy cảm
  src/main/java/org/shp/<svc>/
    api/            # controller, DTO, mapper; chỉ nhận/trả hợp đồng trong contracts/openapi
    domain/         # logic nghiệp vụ thuần, không phụ thuộc framework nếu có thể
    store/          # repository (PostgreSQL/MongoDB), outbox/inbox
    events/         # publisher + consumer (envelope CloudEvents, schema trong contracts/events)
    config/         # PlatformConfig (SA quản lý)
  src/main/resources/db/migration/ # Flyway (chỉ service dùng PostgreSQL)
  src/test/java/...                # cùng package với code; test tích hợp dùng Testcontainers
```
Service Python (`ai`, `ml`): `src/<pkg>/{api,domain,store,events}`, test ở `tests/`.

## 2. Lỗi
- Ném `ShpException` với mã trong `ErrorCatalog` (27 mã, khớp [07 §5](03-architecture/detailed-design/07-api-conventions.md)). **Không tự bịa mã mới**: mở issue cho SA.
- `ShpExceptionHandler` đổi thành FHIR `OperationOutcome` (API lâm sàng) hoặc `application/problem+json` (API khác). Thông điệp có bản vi và en.
- Không trả stack trace, tên bảng, câu SQL hay giá trị nhập vào phản hồi. 5xx chỉ trả mã chung và `traceId`.

## 3. Logging, tracing, metric
- Log JSON một dòng, có `traceId` và `spanId` (OTel tự gắn). Mức: `ERROR` cần người xử lý, `WARN` bất thường tự phục hồi, `INFO` sự kiện nghiệp vụ chính, `DEBUG` tắt ở UAT/prod.
- **Không PII trong log, event, span attribute, tên metric/label**: không tên, SĐT, email, ngày sinh, CCCD, nội dung lâm sàng. Chỉ ghi ID nội bộ (`Patient/123`).
- Không log toàn bộ body request/response. Với lỗi validate, ghi tên trường chứ không ghi giá trị.
- Mọi service phơi `/actuator/health`, `/actuator/info`, `/actuator/prometheus` (`shp-service-defaults` bật sẵn). Endpoint nghiệp vụ mới tự có metric RED qua Micrometer.
- Gọi service khác: truyền `traceparent`. Event: đặt `traceparent` trong envelope (`CloudEvents.create` làm sẵn).

## 4. Cấu hình
- Chỉ qua biến môi trường (Spring: `SPRING_*` hoặc `shp.*` ở `application.yml`). Không đọc file cấu hình ngoài image, không hard-code URL, host, mật khẩu.
- Giá trị bí mật chỉ đến từ Key Vault (CSI) trên cloud hoặc `dev/.local/` ở máy dev. Không có mặc định "tiện" cho secret trong code.
- Bật/tắt tính năng bằng biến `SHP_FEATURE_<TÊN>`, mặc định tắt cho tính năng chưa xong.

## 5. API và versioning
- Đường dẫn dạng `/api/v1/...`; sửa tương thích ngược (thêm trường tùy chọn) giữ `v1`; phá vỡ thì thêm `v2` song song ít nhất một sprint.
- Hợp đồng đi trước code: sửa `contracts/openapi` và `contracts/events` trong cùng PR, `tools/contracts/check_all.sh` phải xanh.
- Endpoint mới phải có `@RequireShpAuthz` và dòng tương ứng trong `contracts/authz/matrix.yaml`; thiếu thì test `UnprotectedEndpoints` làm CI đỏ.
- Ghi dữ liệu thay đổi trạng thái: nhận `Idempotency-Key` (PostgreSQL: `PostgresIdempotency`; MongoDB: `MongoIdempotency`).

## 6. Dữ liệu và sự kiện
- Mỗi service chỉ ghi vào kho dữ liệu của mình ([ADR-0014](03-architecture/adr/0014-database-per-service-review.md)). Muốn dữ liệu service khác: gọi API hoặc nghe event.
- Phát event trong **cùng transaction** với thay đổi dữ liệu qua `outbox.add(...)`; consumer bọc `inbox.handleOnce(...)` để xử lý đúng một lần.
- Event chỉ chứa ID và trường tối thiểu, không PII.

## 7. Test
- Mỗi lớp nghiệp vụ có test đơn vị; truy cập dữ liệu và bus dùng Testcontainers (PostgreSQL, MongoDB, RabbitMQ thật).
- Coverage dòng ≥ 70% (CI chặn). Test phải chạy lặp lại được, không phụ thuộc thứ tự, không gọi mạng ra ngoài.
- Dữ liệu test lấy từ bộ sinh dữ liệu giả ([00-synthetic-data](00-synthetic-data.md)), không tự nghĩ dữ liệu giống thật.

## 8. Phong cách code
- Java: Spotless (`mvn spotless:apply`), `-Werror`, không dùng `@SuppressWarnings` nếu chưa có lý do ghi chú. Python: `ruff` + `mypy` strict.
- Commit nhỏ, thông điệp thì hiện tại: `Create reminders on appointment-booked`.
