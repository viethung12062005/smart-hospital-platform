# ADR-0007: Mô hình dự đoán no-show (MLOps)

## Trạng thái
Proposed — chờ chốt sau khi requirements (docs/02-requirements) được duyệt.

## Bối cảnh
Muốn có một mô hình ML tự huấn luyện để thể hiện vòng đời MLOps, gắn với nghiệp vụ thật.

## Quyết định
Dự đoán **xác suất bệnh nhân không đến khám** cho mỗi Appointment. `notification-service` dùng
điểm này để gửi thêm nhắc nhở cho lịch hẹn rủi ro cao.

- Dữ liệu: **sinh tổng hợp** (synthetic) có quy luật cài sẵn (lead time, lịch sử vắng, giờ khám...).
- Mô hình: Logistic Regression làm baseline → LightGBM.
- Vòng đời: pipeline huấn luyện (CI), theo dõi thí nghiệm + model registry bằng **MLflow**,
  gate chất lượng (AUC ≥ baseline), đóng gói container, deploy canary, theo dõi drift.

## Phương án đã cân nhắc
- Phân loại mức độ khẩn cấp triệu chứng (triage): rủi ro y khoa cao, khó tạo dữ liệu giả hợp lý.
- Dự báo tải phòng khám (time-series): hay nhưng ít gắn với luồng end-to-end.

## Hệ quả
Kết quả mô hình chỉ có ý nghĩa trên dữ liệu giả; báo cáo phải nêu rõ giới hạn này.
