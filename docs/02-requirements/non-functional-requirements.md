# Pha 2 — Yêu cầu phi chức năng (NFR)

> Trạng thái: **Approved — G2 qua ngày 2026-10-26**. Mỗi NFR có **con số**, **cách đo** và **môi trường đo**.
> Các con số tính từ [workload-analysis.md](workload-analysis.md).

## 1. Hiệu năng

| ID | Yêu cầu | Mục tiêu | Cách đo |
|---|---|---|---|
| NFR-P1 | Tìm slot trống | p95 ≤ 300 ms tại 500 RPS | k6 trên UAT (cỡ prod), 10 phút |
| NFR-P2 | Đặt lịch | p95 ≤ 800 ms tại 50 RPS, tỉ lệ lỗi 5xx < 0,1% | k6 trên UAT (cỡ prod) |
| NFR-P3 | Mở hồ sơ / timeline bệnh nhân | p95 ≤ 500 ms | k6 + trace |
| NFR-P4 | Cấp link upload/tải tài liệu | p95 ≤ 300 ms | k6 |
| NFR-P5 | Trợ lý RAG | token đầu tiên ≤ 3 s, trả lời đủ ≤ 10 s (p95) | eval harness + trace |
| NFR-P6 | Tóm tắt hồ sơ | ≤ 15 s (p95) | eval harness |
| NFR-P7 | Trích xuất tài liệu | ≤ 2 phút từ lúc upload (p95) | metric độ trễ hàng đợi |
| NFR-P8 | Gửi nhắc lịch | ≥ 99% gửi trong vòng 5 phút sau giờ hẹn gửi | metric notification |

## 2. Khả năng mở rộng

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-S1 | Chịu đợt tăng tải 07:00 | Tự scale từ mức tối thiểu lên đủ tải trong ≤ 2 phút, không can thiệp tay |
| NFR-S2 | Tải 5× | p95 không vượt quá 2× mục tiêu ở NFR-P1/P2 |
| NFR-S3 | Tải 10× | Hệ thống xuống cấp có kiểm soát (waiting room, 429) thay vì sập |
| NFR-S4 | Scale độc lập | AI và document scale riêng, không ảnh hưởng đặt lịch |

## 3. Tính sẵn sàng và phục hồi

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-A1 | Availability API đặt lịch và hồ sơ (SLO) | **99,9%**/tháng (≤ 43 phút downtime) |
| NFR-A2 | Availability tính năng AI | 99,0%/tháng; AI lỗi không làm hỏng luồng khám (xuống cấp có kiểm soát) |
| NFR-A3 | Chịu lỗi một zone | Không mất dữ liệu đã commit; dịch vụ phục hồi ≤ 5 phút |
| NFR-A4 | RPO / RTO dữ liệu lâm sàng và lịch hẹn | **RPO ≤ 5 phút, RTO ≤ 1 giờ** |
| NFR-A5 | RPO tài liệu và audit | RPO = 0 cho dữ liệu đã xác nhận ghi (lưu trữ dư thừa theo zone) |
| NFR-A6 | Phụ thuộc lỗi (nhà cung cấp SMS, LLM, ML) | Không mất dữ liệu; retry + dead-letter; có fallback (xem UC-07, UC-11) |
| NFR-A7 | Deploy không downtime | Rolling/canary, 0 request lỗi do deploy |

## 4. Bảo mật và quyền riêng tư

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-SEC1 | Xác thực | OIDC; nhân viên bắt buộc MFA; access token ≤ 15 phút |
| NFR-SEC2 | Phân quyền | Thực thi đúng 100% [ma trận phân quyền](access-control-matrix.md), kiểm bằng test tự động trong CI |
| NFR-SEC3 | Mã hóa | TLS 1.2+ ở mọi kết nối (kể cả nội bộ cluster); mã hóa at-rest mọi kho dữ liệu |
| NFR-SEC4 | Secret | Không secret trong code/config/image; dùng Key Vault + workload identity; xoay vòng được không cần deploy lại |
| NFR-SEC5 | Audit | 100% truy cập dữ liệu bệnh nhân được audit; xuất hiện trong công cụ tra cứu ≤ 1 phút; bất biến |
| NFR-SEC6 | Chuỗi cung ứng | Image được quét lỗ hổng (chặn High/Critical), ký và có SBOM; cluster chỉ chạy image đã ký |
| NFR-SEC7 | Cô lập mạng | Mặc định chặn mọi traffic giữa pod; chỉ mở theo luồng đã thiết kế; DB không có endpoint public |
| NFR-SEC8 | AI | Redact PII trước khi gửi tới model ngoài Azure; chống prompt injection theo bộ test; AI chỉ dùng quyền của người gọi |
| NFR-SEC9 | Dữ liệu | Chỉ dữ liệu giả ở mọi môi trường; có công cụ sinh dữ liệu giả |

## 5. Khả năng quan sát và vận hành

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-O1 | Tracing phân tán | 100% request có trace xuyên service (OpenTelemetry) |
| NFR-O2 | Dashboard SLO | Có dashboard SLI/SLO + error budget cho NFR-A1, P1, P2 |
| NFR-O3 | Cảnh báo | Cảnh báo khi error budget cháy nhanh (burn rate), DLQ có message, cert sắp hết hạn |
| NFR-O4 | Log | Log có cấu trúc, có trace-id, **không chứa PII** |

## 6. Khả năng triển khai (DevOps)

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-D1 | Lead time | Commit → image đã ký ≤ 15 phút; → chạy trên UAT ≤ 30 phút (sau phê duyệt) |
| NFR-D2 | Rollback | ≤ 5 phút, bằng một thao tác (GitOps revert) |
| NFR-D3 | Hạ tầng dưới dạng code | 100% hạ tầng tạo bằng IaC; môi trường mới dựng lại được từ đầu |
| NFR-D4 | Cách ly môi trường | `prod` tách cluster và subscription/resource group với `uat`; dev chạy local |

## 7. Chi phí (FinOps)

| ID | Yêu cầu | Mục tiêu (chờ nhóm chốt theo hạ tầng Azure đang có) |
|---|---|---|
| NFR-C1 | Ngân sách hạ tầng | Không có cluster thường trực. Tài nguyên thường trực (state, ACR) ≤ 10 USD/tháng; mỗi giờ chạy một môi trường đầy đủ ≤ 3 USD (dev) / ≤ 6 USD (prod); tổng ngân sách đồ án do PM chốt |
| NFR-C2 | Chi phí AI | Trung bình ≤ 0,005 USD/request RAG (DeepSeek); Gemini chỉ trong free tier; có hạn mức token theo ngày và cảnh báo |
| NFR-C3 | Minh bạch | Gắn tag chi phí theo môi trường/service; báo cáo chi phí hằng tuần |

## 8. Chất lượng AI / ML

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-AI1 | Truy xuất RAG | Recall@5 ≥ 0,8; MRR ≥ 0,6 trên bộ eval ≥ 100 câu hỏi |
| NFR-AI2 | Câu trả lời RAG | Groundedness ≥ 0,9; độ đúng trích dẫn ≥ 0,9; tỉ lệ từ chối đúng khi không có nguồn ≥ 0,9 |
| NFR-AI3 | Prompt injection | 0 lần vượt rào trên bộ test tấn công ≥ 50 mẫu |
| NFR-AI4 | Trích xuất | F1 mức trường ≥ 0,85 trên bộ tài liệu giả có nhãn |
| NFR-AI5 | Chatbot đặt lịch | Tỉ lệ hoàn thành ≥ 85%; 0 hành động không được xác nhận |
| NFR-AI6 | Mô hình no-show | AUC ≥ 0,75 và cao hơn baseline; theo dõi drift hằng tuần |
| NFR-AI7 | Fallback | Mỗi model trong chuỗi fallback đều được eval; báo cáo chất lượng theo từng model |

## 9. Tương thích

| ID | Yêu cầu | Mục tiêu |
|---|---|---|
| NFR-I1 | Chuẩn dữ liệu | API theo FHIR R4 cho các resource trong phạm vi |
| NFR-I2 | Đa nền tảng client | Web PWA dùng được trên desktop và mobile (Chrome, Safari, 2 phiên bản gần nhất) |
| NFR-I3 | Đa nền tảng hạ tầng | Chạy trên Kubernetes bất kỳ (AKS là đích chính) và chạy local; không phụ thuộc cứng vào dịch vụ độc quyền khi đã có bản tương đương |
