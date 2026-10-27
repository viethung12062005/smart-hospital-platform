# audit

Nhận và tra cứu audit, chuỗi hash bất biến. Chủ sở hữu: **D2**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/audit/Shp.Audit.slnx` | build + test |
| `dotnet run --project services/audit/src/Shp.Audit.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC=audit` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
