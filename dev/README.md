# Phát triển cục bộ trên Kubernetes nhỏ (kind)

Quyết định: [ADR-0011](../docs/03-architecture/adr/0011-two-environments-local-kubernetes.md). Dev **không cần cluster cloud**: toàn bộ phụ thuộc chạy trong một cụm kind trên máy bạn,
service chạy từ IDE hoặc (sau task LOC-05) bằng đúng Helm chart dùng cho UAT/prod.

## Yêu cầu máy
- 4 CPU, **16 GB RAM** (profile `core` dùng ~4–5 GB), 10 GB ổ đĩa trống.
- Docker (Docker Desktop cho Windows/macOS, cấp ≥ 6 GB RAM) và các công cụ: [`kind`](https://kind.sigs.k8s.io/), `kubectl`, `helm` 3, `openssl`, `make`.
- Windows: dùng **WSL2** hoặc Git Bash.

## Bắt đầu
```bash
make -C dev doctor      # kiểm tra máy
make -C dev up          # dựng cụm + cài phụ thuộc (lần đầu vài phút do kéo image)
make -C dev seed-users  # tạo người dùng thử (dữ liệu giả) trong Keycloak
make -C dev status      # xem pod
make -C dev down        # xóa cụm khi không dùng (mất dữ liệu trong cụm)
```
Sau `make up`, file `dev/.local/connection.env` (đã gitignore) chứa chuỗi kết nối cho service chạy **ngoài** cụm:
```bash
set -a; . dev/.local/connection.env; set +a
```

## Bên trong cụm có gì
| Thành phần | Truy cập từ máy bạn | Truy cập từ trong cụm |
|---|---|---|
| PostgreSQL 16 (database `scheduling`, `notification`, `ml`; extension `btree_gist`) | `localhost:15432` | `postgres.shp-deps:5432` |
| MongoDB 8, replica set 1 node (có transaction) | `mongodb://localhost:27117/?directConnection=true` | `mongodb-0.mongodb.shp-deps:27017` |
| Redis 7 | `localhost:16379` | `redis.shp-deps:6379` |
| RabbitMQ 4 (AMQP / giao diện quản trị) | `localhost:15673` / http://localhost:25672 | `rabbitmq.shp-deps:5672` |
| Azurite (giả lập Azure Blob/Queue) | `UseDevelopmentStorage=true` (cổng 10000/10001) | `azurite.shp-deps:10000` |
| Keycloak 26, realm `shp` | http://localhost:18080/realms/shp | `keycloak.shp-deps:8080` |

- **Mật khẩu** do `make up` sinh ngẫu nhiên và lưu ở `dev/.local/` (quyền 600, không commit). Không có mật khẩu nào trong repo. Mongo và Azurite không dùng mật khẩu (chỉ nghe trên `127.0.0.1`).
- Các cổng chỉ mở trên `127.0.0.1`, người khác trong mạng không truy cập được.
- Khác biệt so với cloud (đã chấp nhận): Postgres dùng một user `shp` cho cả 3 database; Azurite ký SAS bằng khóa emulator (không có user delegation SAS, không có WORM/immutability); Mongo không bật xác thực.
  Các khác biệt này chỉ lộ ra ở UAT: nếu code của bạn phụ thuộc vào chúng, hãy báo SA.

## Lấy token để gọi API
```bash
make -C dev token USER=doctor.demo      # người dùng: patient.demo, doctor.demo, nurse.demo, admin.demo, dpo.demo
```
Token có `sub`, `preferred_username`, `roles` (một trong patient/doctor/nurse/admin/dpo) và `aud = shp-api`. Client `shp-test` (đăng nhập bằng username/password) **chỉ có ở realm local**.
Realm local chưa mô phỏng MFA (`amr=mfa` của nhân viên): test phần này bằng token tự ký trong unit test (xem task SHR-20); task LOC-02 sẽ bổ sung.

## Lệnh hữu ích
`make -C dev psql DB=scheduling` · `make -C dev mongosh` · `make -C dev redis-cli` · `make -C dev logs APP=keycloak`

## Khắc phục sự cố
| Triệu chứng | Cách xử lý |
|---|---|
| `doctor` báo cổng bị chiếm | Tắt chương trình đang dùng cổng đó (thường là Postgres/Redis/Keycloak cài sẵn trên máy), hoặc đổi cổng trong `dev/kind/cluster.yaml` |
| Pod `Pending`/`OOMKilled` | Tăng RAM cho Docker (≥ 6 GB); `make -C dev down` rồi `up` lại |
| `helm ... timed out` lúc `up` | Kéo image chậm: chạy lại `make -C dev up` (cụm đã tạo sẽ được giữ) |
| Keycloak `Not Ready` lâu | Lần đầu mất ~1–2 phút để nạp realm; xem `make -C dev logs APP=keycloak` |
| Docker Hub báo `429 Too Many Requests` | Giới hạn kéo image ẩn danh: `docker login` rồi thử lại |
| Muốn xóa sạch kể cả mật khẩu | `make -C dev down && rm -rf dev/.local` |

## Chưa có (đang trong backlog)
- **LOC-05** — `make deploy SVC=...` chạy service trên cụm bằng chart `microservice` chung.
- **LOC-08** — profile `full`: OpenSearch, Ollama, ClamAV, MLflow, Loki/Grafana/OTel.
- **LOC-02** — hoàn thiện realm (MFA cho nhân viên, claim bổ sung).

> Trạng thái kiểm chứng: chart đã qua `helm lint` + `kubeconform --strict`; từng phụ thuộc đã được chạy thử bằng Docker với đúng cấu hình và script khởi tạo trong chart
> (database, replica set + transaction, mật khẩu Redis, RabbitMQ, Azurite, realm/token Keycloak). **Chưa chạy `make up` trên kind thật** (môi trường dựng tài liệu này không chạy được kind):
> người đầu tiên chạy trên máy thật hãy báo lỗi cho SA.
