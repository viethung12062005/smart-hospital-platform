# notification

Nhắc lịch, gửi thông báo qua kênh, chống trùng. Chủ sở hữu: **D1**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/notification/Shp.Notification.slnx` | build + test |
| `dotnet run --project services/notification/src/Shp.Notification.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC=notification` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
