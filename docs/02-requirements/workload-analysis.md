# Pha 2 — Phân tích tải (workload analysis)

> Tính từ các giả định trong [business-analysis.md](../01-business-analysis/business-analysis.md) §1.
> Số liệu này là đầu vào cho NFR, sizing hạ tầng và kịch bản load test.

## 1. Người dùng

| Nhóm | Tổng | Hoạt động đồng thời giờ cao điểm |
|---|---|---|
| Bệnh nhân | 50.000 đăng ký (năm 1) | 500 bình thường; **5.000** lúc mở slot (07:00 mỗi ngày) |
| Bác sĩ | 120 | 120 |
| Điều dưỡng | 250 | 150 |
| Admin / DPO | ~10 | 5 |

## 2. Giao dịch theo use case

| Luồng | Khối lượng / ngày | Cao điểm | Ghi chú tính toán |
|---|---|---|---|
| Tìm slot trống (UC-03) | ~20.000 | **500 RPS** trong 5 phút sau 07:00 | 900 lượt đặt × ~20 lần tìm; đợt mở slot 5.000 người, mỗi người ~6 request/phút |
| Đặt lịch (UC-03) | ~900 (60% của 1.500 lượt khám) | **50 RPS** trong 2 phút | Tranh chấp cao: nhiều người cùng nhắm một số slot "đẹp" |
| Đọc hồ sơ / timeline (UC-06) | ~48.000 | 15 RPS | 120 bác sĩ × 40 bệnh nhân × 10 lần đọc |
| Ghi encounter, sinh hiệu (UC-04) | ~6.000 | 3 RPS | 1.500 lượt × ~4 lần ghi |
| Upload tài liệu (UC-05) | ~900 file | 1 RPS | 1.500 × 30% có giấy tờ × 2 file |
| Nhắc lịch (UC-07) | ~2.000 tin | 200 tin/phút | 2 lần nhắc / lịch hẹn + nhắc thêm cho ca rủi ro cao |
| Sự kiện audit (UC-09) | ~250.000 | 100 sự kiện/giây | Mỗi lần đọc/ghi dữ liệu bệnh nhân sinh 1 sự kiện |
| Hỏi trợ lý RAG (UC-11) | ~1.200 | 2 RPS | 120 bác sĩ × 10 câu hỏi |
| Tóm tắt hồ sơ (UC-12) | ~1.500 | 1 RPS | 1 lần / lượt khám |
| Trích xuất tài liệu (UC-13) | ~900 | theo hàng đợi | Không yêu cầu realtime |
| Chatbot đặt lịch (UC-14) | ~300 hội thoại | 1 RPS | |
| Dự đoán no-show (UC-15) | ~900 | 50 RPS (cùng lúc với đặt lịch) | |

**Đặc tính tải:** đọc nhiều hơn ghi (~20:1 ở scheduling); có **đợt tăng đột biến dự đoán được** lúc 07:00;
ghi tranh chấp tập trung ở một số slot; AI có độ trễ cao và chi phí tính theo token; audit ghi đều và chỉ ghi thêm.

## 3. Dung lượng dữ liệu

| Dữ liệu | Kích thước | Năm 1 | Tăng trưởng |
|---|---|---|---|
| Patient | ~3 KB × 50.000 | ~150 MB | +30%/năm |
| Appointment + Slot | ~2 KB × (330k lịch hẹn + 1,2 triệu slot) | ~3 GB (kèm history, index) | tuyến tính |
| Encounter / Observation / Condition | ~4 KB × ~2,2 triệu bản ghi | ~9 GB | tuyến tính |
| Tài liệu (file) | ~1,5 MB × 330k | **~500 GB** | tuyến tính; chuyển tầng Cool sau 30 ngày |
| Audit | ~1 KB × 90 triệu | ~90 GB | giữ dài hạn, bất biến |
| Kho tri thức RAG | ~2.000 tài liệu, ~100k chunk | ~2 GB (kèm vector) | chậm |

## 4. Kịch bản tăng tải (cho câu hỏi "5× hoặc 10× thì sao")

| Hệ số | Tìm slot | Đặt lịch | Điểm nghẽn dự kiến | Hướng xử lý |
|---|---|---|---|---|
| 1× | 500 RPS | 50 RPS | — | Baseline |
| 5× | 2.500 RPS | 250 RPS | DB scheduling (đọc) | Cache slot trống trên Redis; HPA scale pod; read replica |
| 10× | 5.000 RPS | 500 RPS | Ghi tranh chấp, connection pool | Hàng đợi ảo (waiting room) lúc mở slot; giới hạn tốc độ theo người dùng; PgBouncer |

Kịch bản load test ở Pha 6 bám đúng các con số trên.
