# ADR-0005: Chống double-booking và request lặp

## Trạng thái
Proposed — chờ chốt sau khi requirements (docs/02-requirements) được duyệt.

## Bối cảnh
PoC CareMate tạo Appointment mà không khóa Slot → hai bệnh nhân có thể đặt cùng slot. Client
mobile hay retry khi timeout → tạo lịch trùng.

## Quyết định
`POST /fhir/Appointment/$book` trong **một transaction Postgres**:
1. Đọc Slot kèm `version_id`; nếu `status != free` → `409 Conflict`.
2. Cập nhật Slot sang `busy` với điều kiện `WHERE version_id = @expected` (optimistic concurrency).
   Nếu 0 dòng bị ảnh hưởng → có người đặt trước → `409`.
3. Tạo Appointment, ghi event `AppointmentBooked` vào bảng outbox.

Header `Idempotency-Key` bắt buộc: kết quả được lưu theo (user, key); request lặp trả lại đúng
Appointment cũ thay vì tạo mới.

## Phương án đã cân nhắc
- `SELECT ... FOR UPDATE` (pessimistic): cũng đúng, nhưng giữ lock lâu hơn khi tải cao.
- Distributed lock bằng Redis: thêm phụ thuộc và thêm cách hỏng.

## Hệ quả
Có test tích hợp bắn N request song song vào cùng một slot và kiểm tra đúng 1 request thành công.
