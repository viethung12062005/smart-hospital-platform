# ADR-0008: Chọn kho dữ liệu theo đặc tính tải của từng service (polyglot persistence)

## Trạng thái
Accepted (2026-10-26) — đã được Solution Architect/PM duyệt cùng cổng G3.

## Bối cảnh
Cách làm phổ biến của các FHIR server (ví dụ Medplum) là đặt mọi thứ vào một PostgreSQL. Mỗi loại dữ liệu trong đề Project 5 lại có
kiểu truy cập rất khác nhau: lịch hẹn cần giao dịch và tranh chấp ghi, hồ sơ lâm sàng là tài liệu
FHIR lồng nhau đọc theo bệnh nhân, audit chỉ ghi thêm và không được sửa, RAG cần tìm kiếm
vector + từ khóa + rerank.

Nguyên tắc chọn: **mỗi engine mới phải trả lời được "nó làm tốt hơn hẳn điều gì mà engine đã có
không làm được"**, vì mỗi engine thêm vào là thêm chi phí vận hành, backup, giám sát và tiền Azure.

## Quyết định

| Service / dữ liệu | Kiểu tải | Kho dữ liệu (Azure → local) | Lý do chính |
|---|---|---|---|
| `scheduling`: Schedule, Slot, Appointment | Ghi tranh chấp (nhiều người đặt cùng slot), cần ACID | **PostgreSQL** (Azure DB for PostgreSQL Flexible → `postgres:16`) | Transaction gồm slot + appointment + outbox; optimistic concurrency theo dòng; dữ liệu nhỏ, có cấu trúc |
| `scheduling`: tra slot trống | Đọc rất nhiều, chấp nhận trễ vài giây | **Redis** (Azure Cache for Redis → `redis:7`) | Cache-aside theo schedule/ngày, bị invalidate bởi event `AppointmentBooked/Cancelled` |
| `clinical`: Patient, Encounter, Observation, Condition... | Tài liệu FHIR lồng nhau, đọc nhiều, luôn truy cập theo bệnh nhân | **MongoDB API** (Azure Cosmos DB for MongoDB vCore → `mongo:8`) | FHIR vốn là JSON document; shard key = patient compartment nên "xem toàn bộ lịch sử bệnh nhân" chỉ đọc một partition; schema linh hoạt cho extension FHIR |
| `document`: file PDF/ảnh | Blob lớn, ghi một lần đọc ít | **Azure Blob Storage** (lifecycle Hot → Cool sau 30 ngày → Cold sau 180 ngày) | Rẻ nhất cho dữ liệu lớn; SAS URL ngắn hạn; metadata `DocumentReference` do **document service** sở hữu (MongoDB database `document`) |
| ~~`notification`: nhắc lịch~~ → **thay bởi ADR-0014 (MongoDB)** | Lên lịch theo giờ, hủy được, retry, nhiều replica cùng lấy việc | **PostgreSQL** (cùng server với scheduling, database riêng) | `FOR UPDATE SKIP LOCKED` cho hàng đợi việc; hủy nhắc = cập nhật một dòng; không phụ thuộc tính năng riêng của bus |
| ~~`ml`: đặc trưng, dự đoán~~ → **thay bởi ADR-0014 (MongoDB + Parquet)** | Ghi theo event, đọc để huấn luyện/giám sát | **PostgreSQL** (database `ml`) + MLflow cho model | Dữ liệu có cấu trúc, truy vấn tổng hợp cho drift |
| `audit` | Chỉ ghi thêm, không được sửa, truy vấn theo thời gian/đối tượng | **Blob immutable (WORM)** làm bản gốc + **Log Analytics (KQL)** để truy vấn (→ MinIO object lock + Loki) | Immutable thật sự ở tầng storage (không ai, kể cả admin DB, sửa được); KQL trả lời ngay "ai đã xem hồ sơ X" |
| `ai`: kho tri thức RAG | Hybrid search (BM25 + vector) + rerank | **Azure AI Search** (→ OpenSearch) | Có sẵn hybrid + semantic reranker, đúng pipeline Retrieve → Rerank của đề; tách khỏi dữ liệu bệnh nhân |
| `ml`: experiment, model registry | Metadata nhỏ + artifact | **MLflow** (backend Azure ML → SQLite/file) | Chuẩn MLOps, không cần DB riêng |
| `ai`: hội thoại, job trích xuất | Tài liệu lồng nhau, TTL | **MongoDB** (database `ai`, cùng cluster với clinical) | Không thêm engine mới; TTL index tự dọn hội thoại |

Mỗi service truy cập kho dữ liệu qua abstraction (`IFhirStore`, `IBlobStore`, `IAuditSink`,
`IRetriever`), nên đổi engine không phải sửa logic nghiệp vụ.

## Tối ưu cụ thể cho từng engine (sẽ đo trong P4)

- **PostgreSQL**: index đúng trên bảng search-parameter (`(resource_type, param, token_code)`,
  `(…, date_start)`); ID dạng UUIDv7 (tăng theo thời gian) để B-tree không bị phân mảnh;
  connection pooling (PgBouncer tích hợp của Flexible Server); `EXPLAIN ANALYZE` cho mọi truy vấn
  search chính; nếu cần thì partition Appointment theo tháng.
- **MongoDB**: shard/partition key `patientId`; compound index theo SearchParameter; projection
  để không trả cả document lớn; read preference `secondaryPreferred` cho truy vấn lịch sử.
- **Redis**: TTL ngắn + invalidate theo event, giới hạn kích thước key; đo cache hit ratio.
- **Blob**: upload trực tiếp từ client bằng SAS (không đi qua API), lifecycle policy giảm chi phí.
- **AI Search**: chunk 500–800 token có overlap, lọc bảo mật (security trimming) theo quyền
  người dùng ngay trong truy vấn.

## Phương án đã cân nhắc

- **Một PostgreSQL cho tất cả** (như PoC): rẻ và đơn giản nhất, đủ cho quy mô đồ án. Bị loại vì
  không tối ưu cho audit bất biến và RAG hybrid. Vẫn là phương án dự phòng cho `clinical`
  (implementation `SmartHospital.Fhir.Postgres` dùng được cho cả hai).
- **Cosmos DB (NoSQL API) cho clinical**: tự scale rất tốt nhưng API riêng của Azure, khó chạy
  local và khóa nhà cung cấp; MongoDB API chạy được ở mọi nơi.
- **Azure Health Data Services**: xem ADR-0003.
- **pgvector cho RAG**: rẻ, nhưng phải tự làm BM25 + rerank; AI Search có sẵn.
- **Azure Data Explorer cho audit**: truy vấn mạnh nhưng chi phí cố định cao so với lượng audit
  của đồ án; Log Analytics tính theo GB nên rẻ hơn ở quy mô nhỏ.

## Hệ quả

- 7 loại kho dữ liệu (PostgreSQL, MongoDB, Redis, Blob, Log Analytics/Loki, AI Search, MLflow) → phải có backup/restore và giám sát cho từng loại (P4). Chi phí ước tính
  hàng tháng sẽ nằm trong báo cáo FinOps; Azure AI Search Basic là khoản cố định lớn nhất.
- Không có transaction xuyên kho: đồng bộ bằng event (outbox) và eventual consistency.
- Thứ tự triển khai: PostgreSQL (scheduling) → MongoDB (clinical) → Blob → audit → AI Search → Redis.
