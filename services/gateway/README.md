# gateway

Cổng vào duy nhất (Spring Cloud Gateway, WebFlux): định tuyến tới 7 service, xác thực JWT (OIDC), chặn `/internal/**`.
Chủ sở hữu: **SA**. Dev thường **không cần sửa** service này; thêm route mới thì báo SA.

| Lệnh (chạy ở gốc repo) | Việc |
|---|---|
| `mvn -B -pl services/gateway -am verify` | build + test như CI |
| `set -a; . dev/.local/connection.env; set +a; mvn -pl services/gateway -am spring-boot:run` | chạy local (cổng 8080; upstream đặt qua `SHP_UPSTREAM_<SVC>`) |
| `make -C dev deploy SVC=gateway` | build image và deploy lên cụm kind |

- Bảng route: `src/main/resources/application.yml` (nguồn: `docs/03-architecture/detailed-design/07-api-conventions.md` §1).
- Bảo mật: `src/main/java/org/shp/gateway/GatewaySecurity.java` — `/internal/**` luôn 403, `/actuator/health/**` và `/actuator/info` công khai,
  còn lại cần JWT hợp lệ (issuer, audience, MFA cho nhân viên theo `shp.auth.*`). Phân quyền chi tiết nằm ở từng service (`@RequireShpAuthz`).
