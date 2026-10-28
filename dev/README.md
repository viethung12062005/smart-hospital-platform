# Chạy thử hệ thống trên máy cá nhân (Kubernetes nhỏ bằng kind)

Quyết định: [ADR-0011](../docs/03-architecture/adr/0011-two-environments-local-kubernetes.md). Dev **không cần cluster cloud**: phụ thuộc (DB, broker, Keycloak...)
và các service đều chạy trong một cụm kind trên máy bạn, bằng **đúng Helm chart** dùng cho UAT/prod.

## 1. Chuẩn bị máy (một lần)
| Cần | Ghi chú |
|---|---|
| 4 CPU, **16 GB RAM**, 15 GB ổ trống | profile `core` + 8 service dùng ~6 GB |
| **Docker** | Windows/macOS: Docker Desktop (Settings → Resources: RAM ≥ 6 GB). Linux: Docker Engine |
| Windows | Làm mọi thứ **trong WSL2 (Ubuntu)**, clone repo vào thư mục của WSL (không phải `/mnt/c`). Hướng dẫn từng bước và script cài tự động: [docs/onboarding-windows.md](../docs/onboarding-windows.md) |
| `git`, `make`, `curl`, `openssl` | có sẵn trên macOS/Ubuntu (`sudo apt install make curl openssl` nếu thiếu) |

**Không cần tự cài kind, kubectl, helm**: `make -C dev tools` tải đúng phiên bản ghim (kind v0.29.0, kubectl v1.33.4, helm v3.18.6),
kiểm sha256 rồi đặt vào `dev/.local/bin` (không cần sudo, không ảnh hưởng máy). Các lệnh `make` tự dùng thư mục này.

Để code (ngoài cụm) cần thêm: **JDK 21** + **Maven 3.9** (6 service Java) hoặc **Python 3.12** (ai, ml).

## 2. Chạy toàn bộ bằng một lệnh
```bash
git clone <repo> && cd <repo>
make -C dev quickstart
```
Lệnh này làm lần lượt: cài công cụ → tạo cụm kind `shp-local` → cài PostgreSQL, MongoDB, Redis, RabbitMQ, Azurite, Keycloak →
tạo người dùng thử → build image và deploy **cả 8 service** (gateway, scheduling, notification, clinical, document, audit, ai, ml).
Lần đầu mất 10–15 phút (kéo image); các lần sau vài phút.

Kiểm tra:
```bash
make -C dev apps                 # tất cả pod Running
make -C dev port-forward         # để cửa sổ này mở
curl http://localhost:18000/info               # gateway trả {"name":"gateway",...}
curl -i http://localhost:18000/fhir/Appointment  # đi qua gateway tới scheduling
```

## 3. Vòng làm việc hằng ngày
| Muốn | Lệnh |
|---|---|
| Code nhanh, debug trong IDE (khuyên dùng) | `make -C dev up` (chỉ phụ thuộc) → `set -a; . dev/.local/connection.env; set +a` → `mvn -pl services/scheduling -am spring-boot:run` (hoặc Run `SchedulingApplication` trong IntelliJ/VS Code) |
| Chạy test như CI | `mvn -pl services/<svc> -am verify` (format, `-Werror`, test Testcontainers, coverage ≥ 70%; cần Docker) · `mvn spotless:apply` để tự format · Python: xem `services/ai/README.md` |
| Thử service trong cụm sau khi sửa code | `make -C dev deploy SVC=scheduling` (build lại image, nạp vào kind, `helm upgrade`) |
| Deploy lại tất cả | `make -C dev deploy SVC=all` |
| Xem log / trạng thái | `make -C dev app-logs SVC=scheduling` · `make -C dev apps` · `make -C dev status` (phụ thuộc) |
| Gỡ một service | `make -C dev undeploy SVC=scheduling` |
| Lấy token gọi API | `make -C dev token USER=doctor.demo` (patient.demo, doctor.demo, nurse.demo, admin.demo, dpo.demo) |
| Vào DB | `make -C dev psql DB=scheduling` · `make -C dev mongosh` · `make -C dev redis-cli` |
| Tắt máy / giải phóng RAM | `make -C dev down` (xóa cụm, mất dữ liệu trong cụm; mật khẩu giữ nguyên) |

Gọi API có xác thực:
```bash
TOKEN=$(make -s -C dev token USER=doctor.demo)
curl -H "Authorization: Bearer $TOKEN" http://localhost:18000/fhir/Appointment
```

## 4. Bên trong cụm có gì
| Thành phần | Từ máy bạn | Từ service trong cụm (biến môi trường đã nạp sẵn) |
|---|---|---|
| PostgreSQL 16 (db `scheduling`) | `localhost:15432` | `SPRING_DATASOURCE_URL`/`_USERNAME` (values-local của service) + `SPRING_DATASOURCE_PASSWORD` |
| MongoDB 8, replica set 1 node (có transaction); db `clinical`, `document`, `audit`, `notification`, `ml`, `ai` | `mongodb://localhost:27117/?directConnection=true` | `SPRING_MONGODB_URI` (Java) · `MONGODB_URI` (Python) |
| Redis 7 | `localhost:16379` | `SPRING_DATA_REDIS_HOST/PORT/PASSWORD` |
| RabbitMQ 4 (AMQP / quản trị) | `localhost:35672` / http://localhost:25672 | `SPRING_RABBITMQ_HOST/PORT/USERNAME/PASSWORD` · `RABBITMQ_URL` (Python) |
| Azurite (giả lập Blob/Queue, tài khoản `shp`) | `AZURE_STORAGE_CONNECTION_STRING` trong `connection.env` | `AZURE_STORAGE_CONNECTION_STRING` |
| Keycloak 26, realm `shp` | http://localhost:18080/realms/shp | `OIDC_AUTHORITY` (= issuer localhost), `OIDC_JWK_SET_URI` (khóa lấy qua DNS trong cụm), `OIDC_AUDIENCE` |
| 8 service ứng dụng (namespace `shp-apps`) | qua gateway: `make port-forward` → `localhost:18000` | `http://<tên service>` (cổng 80) |

- **Mật khẩu và khóa Azurite** do script sinh ngẫu nhiên trên từng máy, lưu ở `dev/.local/` (quyền 600, gitignore). Repo không chứa mật khẩu nào.
- Cổng chỉ mở trên `127.0.0.1`. Ở local, NetworkPolicy và PDB tắt, mỗi service 1 replica (`dev/chart/app-values-local.yaml`).
- `make up` tự chạy `contracts/db/mongo-notification-ml.js` (collection, index, validator của notification và ml; chạy lại không lỗi).
  Collection `outbox`/`inbox`/`idempotency` do service Java tự tạo khi khởi động.
- Khác biệt với cloud (đã chấp nhận): Postgres chỉ có một user; Azurite không có user delegation SAS và WORM; Mongo không bật xác thực.
  Code phụ thuộc vào những điểm này chỉ lộ lỗi ở UAT: báo SA nếu cần.

## 5. Các file liên quan (dev không cần sửa, trừ phần đánh dấu)
| File | Vai trò |
|---|---|
| `dev/Makefile`, `dev/scripts/*.sh` | lệnh ở trên (`make -C dev help` để xem tất cả) |
| `dev/kind/cluster.yaml` | cấu hình cụm, cổng ra máy |
| `dev/chart/shp-local-deps/` | Helm chart phụ thuộc + realm Keycloak |
| `dev/chart/app-values-local.yaml` | values chung cho mọi service khi chạy local |
| `services/<svc>/deploy/values-local.yaml` | values riêng của service (**dev được thêm** biến môi trường không nhạy cảm) |
| Chart `microservice` | lấy từ repo `shp-platform-templates` lần deploy đầu (lưu ở `dev/.local/templates`); đặt `SHP_CHART_DIR=<đường dẫn>` để dùng bản clone sẵn |

## 6. Khắc phục sự cố
| Triệu chứng | Cách xử lý |
|---|---|
| `doctor` báo cổng bị chiếm | Tắt chương trình đang dùng cổng (Postgres/Redis/Keycloak cài sẵn trên máy), hoặc đổi cổng trong `dev/kind/cluster.yaml` |
| `Docker chưa chạy` | Mở Docker Desktop / `sudo systemctl start docker` |
| Pod `Pending`/`OOMKilled` | Tăng RAM cho Docker; deploy ít service hơn (`make deploy SVC=<chỉ cái cần>`) |
| `helm ... timed out` | Kéo image chậm: chạy lại lệnh vừa lỗi (các bước đều chạy lại được) |
| `deploy` lỗi `ErrImageNeverPull` | Image chưa được nạp: chạy lại `make -C dev deploy SVC=<svc>` |
| Pod service `CrashLoopBackOff` | `make -C dev app-logs SVC=<svc>`: thường do thiếu cấu hình hoặc DB chưa sẵn |
| Docker Hub `429 Too Many Requests` | `docker login` rồi chạy lại (image Python đã dùng mirror `mirror.gcr.io`) |
| Keycloak `Not Ready` lâu | Lần đầu ~1–2 phút để nạp realm; `make -C dev logs APP=keycloak` |
| Mạng công ty chặn tải công cụ | Tự cài kind/kubectl/helm theo phiên bản ở mục 1 rồi bỏ qua `make tools` |
| Muốn xóa sạch | `make -C dev down && rm -rf dev/.local` |

## 7. Profile `full` (LOC-08)
Chỉ bật khi task cần: `make -C dev up PROFILE=full` (cụm đã tạo bằng profile `core` trước đây: `make -C dev down` rồi `up` lại để có cổng mới). Cần **Docker ≥ 10 GB RAM** (core ~6 GB + full ~4.5 GB), 25 GB ổ trống. Sau đó `make -C dev pull-models` (một lần, vài GB) để có `bge-m3` và `qwen2.5:3b`.

| Thành phần | Từ máy bạn | Từ service trong cụm | Dùng cho |
|---|---|---|---|
| ClamAV | `localhost:13310` (clamd) | `clamav.shp-deps:3310` | document: quét tệp tải lên |
| OpenSearch 2 (tắt security) | http://localhost:19200 | `http://opensearch.shp-deps:9200` | ai: tìm kiếm |
| Ollama | http://localhost:21434 | `http://ollama.shp-deps:11434` | ai: embedding/LLM local |
| MLflow | http://localhost:15000 | `http://mlflow.shp-deps:5000` | ml: tracking + registry |
| Grafana + Loki | http://localhost:13000 (user `admin`, mật khẩu `GRAFANA_ADMIN_PASSWORD` trong `connection.env`) | Loki `http://loki.shp-deps:3100` | xem log |
| OTel Collector | — | `http://otel-collector.shp-deps:4318` (OTLP/HTTP) | service gửi trace/metric/log; log vào Loki, trace/metric chỉ in ra log collector |

Biến cho service chạy ngoài cụm nằm trong `dev/.local/connection.env` (khối "Profile full"). Dữ liệu giả cho cụm do `make seed-users` và phần seed của từng service tạo (D3 sinh dữ liệu giả theo ARC-11).

## Chưa có (backlog)
- **LOC-02** — realm hoàn chỉnh (MFA cho nhân viên, claim bổ sung).
- **LOC-02** — realm hoàn chỉnh (MFA cho nhân viên, claim bổ sung).

> Trạng thái kiểm chứng: `install-tools.sh` đã chạy thật (tải + kiểm sha256, chạy lại không tải lại); chart render cho cả 8 service qua `kubeconform --strict`;
> image Java (gateway, scheduling) và Python build và chạy được (non-root, `/info` trả 200); phụ thuộc đã chạy thử bằng Docker. **Chưa chạy `make quickstart` trọn vẹn trên kind thật**
> (môi trường dựng tài liệu không chạy được kind): người đầu tiên chạy trên máy thật hãy báo lỗi cho SA.
