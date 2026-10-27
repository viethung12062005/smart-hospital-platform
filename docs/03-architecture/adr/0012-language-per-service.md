# ADR-0012: Đánh giá lại ngôn ngữ cho từng service

## Trạng thái
Accepted (2026-10-27) — SA/PM đã duyệt. Giữ nguyên ADR-0002.

## Bối cảnh
PM yêu cầu: "nếu có ngôn ngữ nào tối ưu hơn cho service nào đó thì hãy sử dụng". Đánh giá theo đặc tính tải (ADR-0001),
NFR (P1–P8, S1, A1) và chi phí vận hành của một nhóm 3 dev.

## Đánh giá
| Service | Đặc tính chính | Ứng viên đã xét | Kết luận |
|---|---|---|---|
| gateway | Proxy HTTP, định tuyến, kiểm JWT, rate limit | Go, **Envoy Gateway thuần cấu hình** | Giữ **.NET + YARP**: độ trễ thêm < 1 ms, chung thư viện auth/OTel với service khác. Envoy Gateway vẫn là cửa vào cluster (TLS, HTTPRoute) |
| scheduling | Giao dịch Postgres, chống double-booking, 500 RPS tìm slot | Go, Java | Giữ **.NET**: Npgsql + Native AOT/ReadyToRun đủ cho NFR-P1; Firely SDK cho FHIR |
| clinical | FHIR CRUD/search trên MongoDB | Java + HAPI | Giữ **.NET**: Firely SDK tương đương HAPI cho R4, không cần JVM thứ hai |
| document | Cấp SAS, theo dõi trạng thái quét | Go | Giữ **.NET**: tải nhẹ, Azure SDK .NET đầy đủ |
| notification | Worker hàng đợi, `SKIP LOCKED`, gửi kênh | Go | Giữ **.NET**: MassTransit (RabbitMQ ↔ Service Bus) không có bản tương đương trong Go |
| audit | Ghi append-only, chuỗi hash, truy vấn KQL | Go, Rust | Giữ **.NET**: thông lượng thấp, cần chung mô hình event |
| ai | LLM router, RAG, trích xuất | TypeScript | Giữ **Python**: hệ sinh thái AI/eval, SDK Azure OpenAI/AI Search |
| ml | Huấn luyện, phục vụ mô hình, drift | — | Giữ **Python**: scikit-learn/LightGBM, MLflow, pandera |

## Quyết định
Không đổi ngôn ngữ. Lý do chung: thêm ngôn ngữ thứ ba (Go/Java) tạo thêm một pipeline, một bộ image cơ sở, một bộ thư viện
auth/OTel/messaging phải duy trì, và đội 3 dev phải học thêm, trong khi không service nào có NFR mà .NET/Python không đạt.
Điểm tối ưu được áp dụng **bên trong** ngôn ngữ thay vì đổi ngôn ngữ:
- Image .NET dùng `aspnet:10.0-noble-chiseled` (không shell, non-root); có thể bật ReadyToRun/Native AOT cho scheduling nếu đo k6 cho thấy cần (khởi động nhanh hơn khi KEDA scale lúc 07:00).
- Python dùng image slim, non-root.

## Hệ quả
Xét lại nếu đo tải ở Pha 6 cho thấy service nào không đạt NFR vì runtime (ví dụ gateway p95 bị chi phối bởi GC).
