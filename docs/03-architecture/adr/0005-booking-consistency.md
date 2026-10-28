# ADR-0005: Chống double-booking và request lặp

## Trạng thái
Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Bối cảnh
Cách làm phổ biến là tạo Appointment mà không khóa Slot → hai bệnh nhân có thể đặt cùng slot. Client
mobile hay retry khi timeout → tạo lịch trùng.

## Quyết định
`POST /fhir/Appointment/$book` trong **một transaction Postgres**:
1. Giành idempotency key (`INSERT ... ON CONFLICT DO NOTHING`).
2. Advisory lock theo bệnh nhân (tuần tự hóa kiểm tra BR-03: tối đa 3 lịch hẹn tương lai).
3. **Compare-and-set** trên slot: `UPDATE slot SET status='busy' WHERE id=@id AND status='free' ...`; 0 dòng bị ảnh hưởng →
   có người đặt trước → `409` (không giữ lock lâu, không cần đọc trước).
4. Tạo Appointment, ghi lịch sử và event `appointment-booked` vào outbox.
5. **Lớp bảo vệ cuối ở tầng DB:** `UNIQUE INDEX ON appointment(slot_id) WHERE status IN ('booked','arrived','fulfilled')` và
   exclusion constraint chống slot chồng lấn — đúng ngay cả khi code có lỗi.

Header `Idempotency-Key` bắt buộc: kết quả được lưu theo (user, key); request lặp trả lại đúng
Appointment cũ thay vì tạo mới.

## Phương án đã cân nhắc
- `SELECT ... FOR UPDATE` (pessimistic): cũng đúng, nhưng giữ lock lâu hơn khi tải cao.
- Distributed lock bằng Redis: thêm phụ thuộc và thêm cách hỏng.

## Hệ quả
Đã kiểm chứng trên PostgreSQL 16: 50 phiên song song cùng đặt một slot → đúng 1 lịch hẹn
(xem `detailed-design/02-db-scheduling.md` §6). Test tích hợp tương ứng (SCH-13) chạy trong CI.
