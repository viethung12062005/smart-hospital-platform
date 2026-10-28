# ADR-0001: Ranh giới microservice

## Trạng thái
Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Bối cảnh
Đề yêu cầu MVP hướng production, nhưng giảng viên ghi rõ *không* muốn "dozens of microservices".
Nhóm muốn dùng microservice để thể hiện năng lực vận hành cloud.

## Quyết định
Chia theo **đặc tính tải và yêu cầu phi chức năng**, không chia theo từng resource FHIR:

- `scheduling` — tranh chấp ghi, cần tính nhất quán mạnh.
- `clinical` — đọc nhiều, dữ liệu nhạy cảm nhất.
- `document` — I/O file lớn, scale theo băng thông.
- `notification`, `audit` — worker bất đồng bộ, phải chịu được lỗi phụ thuộc.
- `ai`, `ml` — độ trễ cao, chi phí token, phụ thuộc nhà cung cấp ngoài, khác ngôn ngữ.
- `api-gateway` — điểm vào duy nhất.

Tối đa 8 deployable. Mỗi service một schema Postgres riêng; giao tiếp đồng bộ qua REST FHIR,
bất đồng bộ qua event.

## Phương án đã cân nhắc
- **Modular monolith**: rẻ, đơn giản; nhưng không thể scale/deploy AI và document độc lập,
  và không thể hiện được vận hành phân tán. Giữ làm phương án dự phòng: các service Java dùng
  chung building block nên có thể gộp lại nếu thiếu thời gian.
- **Mỗi resource một service**: quá nhiều, vi phạm hướng dẫn của đề.

## Hệ quả
- Cần distributed tracing (OpenTelemetry) và transactional outbox.
- Không có transaction xuyên service → dùng event + eventual consistency (ví dụ notification).
