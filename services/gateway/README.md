# gateway

API gateway (YARP): định tuyến, xác thực JWT, rate limit. Chủ sở hữu: **SA**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/gateway/Shp.Gateway.slnx` | build + test |
| `dotnet run --project services/gateway/src/Shp.Gateway.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC=gateway` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
