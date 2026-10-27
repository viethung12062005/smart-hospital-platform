# scheduling

Lịch bác sĩ, slot, đặt/hủy lịch hẹn (FHIR Schedule/Slot/Appointment). Chủ sở hữu: **D1**.

| Lệnh | Việc |
|---|---|
| `dotnet test services/scheduling/Shp.Scheduling.slnx` | build + test |
| `dotnet run --project services/scheduling/src/Shp.Scheduling.Api` | chạy local (dùng hạ tầng từ `make -C dev up`, biến môi trường từ `dev/scripts/env.sh`) |
| `make -C dev deploy SVC=scheduling` | build image và deploy lên cụm kind |

Endpoint nền có sẵn: `/health/live`, `/health/ready`, `/info`. Dev chỉ thêm logic nghiệp vụ trong `src/`.
