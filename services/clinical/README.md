# clinical

Hồ sơ bệnh nhân, encounter, tìm kiếm lịch sử (FHIR). Chủ sở hữu: **D2**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/clinical/Shp.Clinical.slnx` | build + test |
| `dotnet run --project services/clinical/src/Shp.Clinical.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC=clinical` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
