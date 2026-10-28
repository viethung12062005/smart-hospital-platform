# ADR-0007: Mô hình dự đoán no-show (MLOps)

## Trạng thái
Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Bối cảnh
Muốn có một mô hình ML tự huấn luyện để thể hiện vòng đời MLOps, gắn với nghiệp vụ thật.

## Quyết định
Dự đoán **xác suất bệnh nhân không đến khám** cho mỗi Appointment. `ml-service` nghe event
`appointment-booked`, chấm điểm và **phát event `no-show-scored`**; `notification-service` nghe event này để gửi thêm nhắc
nhở cho lịch hẹn rủi ro cao. Không có lời gọi đồng bộ giữa hai service nên ML lỗi không ảnh hưởng nhắc lịch (AC-15.1).

- Dữ liệu: **sinh tổng hợp** (synthetic) có quy luật cài sẵn (lead time, lịch sử vắng, giờ khám...).
- Mô hình: Logistic Regression làm baseline → XGBoost (XGBoost4J, ADR-0015).
- Vòng đời: pipeline huấn luyện (CI), theo dõi thí nghiệm + model registry bằng **MLflow**,
  gate chất lượng (AUC, nhóm con, calibration, độ trễ), model card, shadow + canary tự động bằng Argo Rollouts,
  theo dõi drift (PSI) và AUC thực tế, huấn luyện lại tự động khi drift. Chi tiết: `detailed-design/16-mlops.md`.

## Phương án đã cân nhắc
- Phân loại mức độ khẩn cấp triệu chứng (triage): rủi ro y khoa cao, khó tạo dữ liệu giả hợp lý.
- Dự báo tải phòng khám (time-series): hay nhưng ít gắn với luồng end-to-end.

## Hệ quả
Kết quả mô hình chỉ có ý nghĩa trên dữ liệu giả; báo cáo phải nêu rõ giới hạn này.
- Thêm ~60 giờ công (dev + SA) so với bản chỉ train/đăng ký. Đổi lại có vòng đời MLOps khép kín: dữ liệu có phiên bản → train → cổng → canary → giám sát → huấn luyện lại.
- Huấn luyện lại tự động có rào chắn: cooldown 7 ngày, tối đa 2 lần/14 ngày, mô hình không đạt cổng thì production giữ nguyên.
