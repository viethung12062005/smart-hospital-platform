# document

Tài liệu lâm sàng: link upload/tải có hạn, trạng thái quét. Chủ sở hữu: **D2**. Lưu trữ: MongoDB (database `document`).

| Lệnh (chạy ở gốc repo) | Việc |
|---|---|
| `mvn -B -pl services/document -am verify` | build + test như CI (cần Docker cho Testcontainers) |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/document -am spring-boot:run` | chạy local với hạ tầng của `make -C dev up` |
| `make -C dev deploy SVC=document` | build image và deploy lên cụm kind |

## Dev viết gì, ở đâu
- Bố cục gói (`docs/03-architecture/overview.md` §4): `api/` (controller) → `app/` (use case, transaction) → `domain/` (quy tắc, không Spring)
  → `infra/` (repository, client); `messaging/` (consumer); `config/` của SA.
- Endpoint: `src/main/java/org/shp/document/api/*Controller.java`. **Mọi endpoint** phải có `@RequireShpAuthz(resource, action, context)`
  (test `everyEndpointDeclaresItsAuthorizationRule` đỏ nếu quên). Dữ kiện cho phân quyền: bean `ResourceContextResolver`.
- Lỗi nghiệp vụ: `throw new ShpException("slot-unavailable")` (mã trong `contracts` + 07 §5).
- Event: `outbox.add(CloudEvents.create("document", "shp.<context>.<event>.v1", subject, payload))` **trong transaction** của thay đổi dữ liệu;
  test hợp đồng: `EventSchemas.validate(...)` rỗng.
- Consumer: `inbox.handleOnce("document", event, () -> ...)`.
- Idempotency-Key: `idempotency.claim(subject, IdempotencyKeys.require(header), operation, IdempotencyKeys.hashBody(body))`.
- Bean nền tảng đã cấu hình sẵn ở `config/PlatformConfig.java` — không cần sửa.

Tài liệu: `docs/03-architecture/detailed-design/` (API, DB, event của service) và `docs/05-plan/by-person/D2.md` (task).
