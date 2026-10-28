# notification

Nhắc lịch, gửi thông báo qua kênh, chống trùng. Chủ sở hữu: **D1**. Lưu trữ: MongoDB (database `notification`).

| Lệnh (chạy ở gốc repo) | Việc |
|---|---|
| `mvn -B -pl services/notification -am verify` | build + test như CI (cần Docker cho Testcontainers) |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/notification -am spring-boot:run` | chạy local với hạ tầng của `make -C dev up` |
| `make -C dev deploy SVC=notification` | build image và deploy lên cụm kind |

## Dev viết gì, ở đâu
- Bố cục gói (`docs/03-architecture/overview.md` §4): `api/` (controller) → `app/` (use case, transaction) → `domain/` (quy tắc, không Spring)
  → `infra/` (repository, client); `messaging/` (consumer); `config/` của SA.
- Endpoint: `src/main/java/org/shp/notification/api/*Controller.java`. **Mọi endpoint** phải có `@RequireShpAuthz(resource, action, context)`
  (test `everyEndpointDeclaresItsAuthorizationRule` đỏ nếu quên). Dữ kiện cho phân quyền: bean `ResourceContextResolver`.
- Lỗi nghiệp vụ: `throw new ShpException("slot-unavailable")` (mã trong `contracts` + 07 §5).
- Event: `outbox.add(CloudEvents.create("notification", "shp.<context>.<event>.v1", subject, payload))` **trong transaction** của thay đổi dữ liệu;
  test hợp đồng: `EventSchemas.validate(...)` rỗng.
- Consumer: `inbox.handleOnce("notification", event, () -> ...)`.
- Idempotency-Key: `idempotency.claim(subject, IdempotencyKeys.require(header), operation, IdempotencyKeys.hashBody(body))`.
- Bean nền tảng đã cấu hình sẵn ở `config/PlatformConfig.java` — không cần sửa.

Tài liệu: `docs/03-architecture/detailed-design/` (API, DB, event của service) và `docs/05-plan/by-person/D1.md` (task).
