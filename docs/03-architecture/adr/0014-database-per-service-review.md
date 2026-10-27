# ADR-0014: Rà soát lại CSDL từng service — PostgreSQL chỉ giữ cho scheduling

## Trạng thái
**Proposed (2026-10-27)** — chờ SA/PM duyệt. Nếu được duyệt, thay các dòng `notification` và `ml` của [ADR-0008](0008-polyglot-persistence.md). Chưa sửa thiết kế chi tiết, DDL hay task trước khi duyệt.

## Bối cảnh
PM nhận xét: "lúc nào cũng PostgreSQL, phải tối ưu DB". Hiện PostgreSQL phục vụ 3 service (scheduling, notification, ml) và các thư viện dùng chung vừa viết (outbox, inbox, idempotency) cũng chỉ có bản PostgreSQL.
Nguyên tắc của ADR-0008 vẫn giữ: **không thêm engine mới nếu chưa chứng minh được nó làm tốt hơn hẳn điều engine đang có không làm được**, vì mỗi engine là thêm chi phí vận hành, backup, giám sát, tiền Azure và thêm một bản thư viện nền cho dev.

## Số liệu tải thực (workload-analysis §2–3)
| Service | Khối lượng | Cao điểm | Dung lượng năm 1 |
|---|---|---|---|
| scheduling | ~900 lượt đặt/ngày, ~20.000 lượt tìm slot/ngày | 500 RPS tìm, 50 RPS đặt (tranh chấp cao) | ~3 GB |
| notification | ~2.000 nhắc/ngày | 200/phút | vài trăm MB |
| ml | ~900 dự đoán/ngày; ~330.000 appointment_fact/năm | 50 RPS dự đoán (cùng lúc đặt lịch) | < 1 GB |

Kết luận từ số liệu: notification và ml **nhỏ về lượng**; cả hai không cần engine chuyên dụng cho dữ liệu lớn (ClickHouse, Cassandra, Kafka...). Câu hỏi đúng là "kiểu truy cập nào hợp với engine nào", không phải "engine nào nhanh nhất".

## Đánh giá từng service
| Service | Kiểu truy cập thực sự | Phương án đã xét | Kết luận |
|---|---|---|---|
| **scheduling** | Giao dịch tranh chấp: chống 2 người đặt 1 slot, tối đa 3 lịch tương lai/bệnh nhân, không chồng lịch bác sĩ | MongoDB (unique index + transaction), Azure SQL, giữ PostgreSQL | **Giữ PostgreSQL.** Ràng buộc nằm ở tầng DB (`btree_gist` loại trừ chồng lấn, unique partial index trên `appointment(slot_id)`, advisory lock) nên đúng ngay cả khi code sai; đã kiểm 50 lượt đặt đồng thời → đúng 1 thành công. MongoDB làm được bằng unique index nhưng mất ràng buộc chồng lấn khoảng thời gian; Azure SQL tương đương nhưng nặng hơn để chạy local. Đây là service **duy nhất** cần ACID quan hệ thật sự |
| **notification** | Hàng đợi việc theo giờ hẹn: tạo, hủy, lấy việc đến hạn (nhiều replica), retry, chống trùng, tự dọn sau N ngày; mỗi nhắc có vài lần gửi | Redis (sorted set), MongoDB, RabbitMQ delayed, giữ PostgreSQL | **MongoDB** (đã có cho clinical/document/ai). Một document/nhắc, lần gửi nhúng bên trong; lấy việc bằng `findOneAndUpdate({status:'pending', dueAt:{$lte:now}})` có `lockedUntil` (nguyên tử, đa replica, tự thu hồi khi pod chết); chống trùng bằng unique index `(appointmentId, kind)`; **TTL index tự xóa** nhắc cũ. Redis nhanh hơn nhưng độ bền yếu (mất tối đa ~1 giây AOF, hoặc cần gói Premium) trong khi nhắc lịch là dữ liệu trạng thái phải bền; RabbitMQ delayed khó hủy theo từng nhắc. Với 200 nhắc/phút, mọi phương án đều dư sức — chọn theo **độ khớp và số engine phải vận hành** |
| **ml** | (1) Trạng thái đặc trưng theo bệnh nhân, cập nhật theo event (`prior_noshow`, `prior_booked`), đọc theo khóa lúc dự đoán; (2) nhật ký dự đoán chỉ ghi thêm, truy vấn theo model_version + thời gian để tính drift/AUC; (3) tập huấn luyện bất biến, có phiên bản | ClickHouse/Data Explorer (cột), Redis, giữ PostgreSQL, MongoDB + Parquet | **MongoDB + Parquet trên Blob.** (1) `patient_stats` là document khóa theo `patientId`, cập nhật nguyên tử bằng `$inc`, đọc theo khóa nhanh; (2) nhật ký dự đoán dạng **time-series collection** (nén tốt, truy vấn theo thời gian, TTL) — **cần kiểm khả dụng trên Cosmos DB for MongoDB vCore**, nếu chưa có thì dùng collection thường + index `(modelVersion, predictedAt)`; (3) snapshot huấn luyện xuất ra **Parquet** trên Blob, băm nội dung ghi vào MLflow (đúng 16-mlops: dataset versioning), tính drift bằng pandas/DuckDB (thư viện nhúng, không phải server). ClickHouse/ADX bị loại: 330k dòng/năm không cần engine cột, thêm engine mới vi phạm nguyên tắc |
| clinical, document, ai | (giữ nguyên) | — | MongoDB như ADR-0008 |
| gateway (rate limit) | Bộ đếm theo `sub`, hết hạn nhanh | — | Redis như ADR-0008 |
| audit | Chỉ ghi thêm, bất biến | — | Blob WORM + Log Analytics như ADR-0008 |

## Kết quả nếu duyệt
- Số **loại engine không đổi** (PostgreSQL, MongoDB, Redis, Blob, Log Analytics/Loki, AI Search, MLflow); PostgreSQL chỉ còn **một service** → server Azure PostgreSQL nhỏ hơn (burstable/GP nhỏ), không còn database `notification`/`ml`.
- Thư viện nền: bản PostgreSQL (`Shp.Data.Postgres`, đã viết) chỉ dùng cho scheduling; **bản MongoDB** (outbox/inbox/idempotency, task SHR-07/10/12) dùng cho 5 service nên là bản quan trọng hơn.

## Tối ưu từng engine (đo ở Pha 6, không chỉ khẳng định)
| Engine | Việc | Cách đo |
|---|---|---|
| PostgreSQL (scheduling) | Index khớp truy vấn tìm slot (`(practitioner_id, start) WHERE status='free'`); UUIDv7; PgBouncer; partition `appointment` theo tháng nếu cần; read replica cho tìm slot; `EXPLAIN (ANALYZE, BUFFERS)` cho mọi truy vấn chính | k6: NFR-P1 (500 RPS, p95 ≤ 300 ms), NFR-P2; kế hoạch truy vấn lưu trong repo |
| MongoDB | Shard/partition key `patientId` cho dữ liệu lâm sàng; compound index theo SearchParameter; projection; `secondaryPreferred` cho lịch sử; index một phần cho outbox/nhắc đến hạn; TTL cho inbox/nhắc/hội thoại | `explain("executionStats")`: tỉ lệ `docsExamined/nReturned` ≈ 1; k6: NFR-P3 |
| Redis | TTL 5 giây cho slot trống, invalidate theo event; Redis chết → đọc thẳng DB | Hit ratio; kiểm thử lỗi (chaos) |
| Blob | Tầng Hot → Cool 30 ngày → Cold 180 ngày; SAS ngắn hạn | Báo cáo chi phí |

## Rủi ro và cách xử lý
| Rủi ro | Xử lý |
|---|---|
| Time-series collection có thể chưa có trên Cosmos DB for MongoDB vCore | Kiểm trước khi duyệt thiết kế chi tiết; phương án dự phòng đã nêu (collection thường + index) |
| Transaction đa document trên vCore | Đã là phụ thuộc của clinical (outbox + resource cùng transaction); kiểm chứng ở task SCF/SHR-10 |
| notification mất tính năng `SKIP LOCKED` | Thay bằng lấy việc nguyên tử + `lockedUntil`; có test nhiều replica |
| Dồn thêm tải vào cụm MongoDB dùng chung | Database riêng cho notification/ml; theo dõi RU/CPU; tách cụm nếu đo thấy tranh chấp |

## Tác động nếu duyệt (làm ở bước sau, có ghi nhật ký)
Sửa: `04-db-notification-ml.md` (viết lại thành collection Mongo + Parquet), `02-db-scheduling.md` (không đổi), `03-db-clinical-document.md` (thêm database `notification`, `ml`), ADR-0008/0011 và `overview.md`;
`dev/chart/shp-local-deps` (bỏ database `notification`, `ml` khỏi Postgres, thêm khởi tạo Mongo); task NTF-01..05 và ML-11 (và các task phụ thuộc), SCF-xx; module IaC dữ liệu (IAC-09..16); thư viện nền Mongo (SHR-07/10/12) lên ưu tiên cao.
