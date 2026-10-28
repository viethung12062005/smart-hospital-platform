# document

Tài liệu lâm sàng: link upload/tải có hạn, trạng thái quét. Chủ sở hữu: **D2**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/document/Shp.Document.slnx` | build + test |
| `dotnet run --project services/document/src/Shp.Document.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC=document` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
