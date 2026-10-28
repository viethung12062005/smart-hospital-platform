# Bắt đầu làm việc — hướng dẫn cho dev

Mục tiêu: trong **dưới 1 giờ**, bạn clone repo, chạy được hệ thống trên máy, hiểu mình làm phần nào và mở được PR đầu tiên.

## Bước 1 — Cài máy (15 phút)
| Cần | Ai |
|---|---|
| Docker (Desktop hoặc Engine), RAM cho Docker ≥ 6 GB; Windows dùng **WSL2** | mọi người |
| `git`, `make`, `curl`, `openssl` | mọi người |
| **JDK 21** (Temurin) + **Maven 3.9**; IDE: IntelliJ IDEA hoặc VS Code (Extension Pack for Java) | D1, D2 |
| **Python 3.12** | D3 |

Không cần cài kind, kubectl hay helm: `make -C dev tools` tự tải đúng phiên bản.

## Bước 2 — Chạy hệ thống (20 phút)
```bash
git clone https://github.com/viethung12062005/smart-hospital-platform.git && cd smart-hospital-platform
make -C dev quickstart        # dựng cụm kind + DB/bus/Keycloak + deploy 8 service (lần đầu 10–15 phút)
make -C dev apps              # mọi pod Running
make -C dev port-forward      # để mở; cửa sổ khác:
curl http://localhost:18000/info
```
Hướng dẫn đầy đủ và cách xử lý sự cố: [dev/README.md](../dev/README.md).

## Bước 3 — Đọc theo thứ tự (20 phút)
1. [Kiến trúc tổng quan](03-architecture/overview.md): đọc §1–§4 (sơ đồ hệ thống, event, module, bố cục gói).
2. [CONTRIBUTING.md](../CONTRIBUTING.md): quy trình task → nhánh → PR, các lệnh kiểm tra.
3. README của service bạn phụ trách: mục "Dev viết gì, ở đâu".
4. Danh sách việc của bạn: `docs/05-plan/by-person/<D1|D2|D3>.md`.

## Bước 4 — Phần của từng người

### D1 — scheduling, notification (Java)
- **Service:** [`services/scheduling`](../services/scheduling) (PostgreSQL + Flyway), [`services/notification`](../services/notification) (MongoDB).
- **Thiết kế cần đọc:**
  - [02-db-scheduling](03-architecture/detailed-design/02-db-scheduling.md), [08-api-scheduling](03-architecture/detailed-design/08-api-scheduling.md)
  - [04-db-notification-ml](03-architecture/detailed-design/04-db-notification-ml.md) §1, [11-api-notification-audit-ml](03-architecture/detailed-design/11-api-notification-audit-ml.md)
  - [13-events](03-architecture/detailed-design/13-events.md) (E1–E5, E10), [14-sequences](03-architecture/detailed-design/14-sequences.md) (đặt lịch)
  - [ADR-0005](03-architecture/adr/0005-booking-consistency.md) (chống double-booking)
- **Việc đầu tiên:**
  - SHR-26: sinh test từ ma trận phân quyền.
  - NTF-02, NTF-03: consumer tạo/hủy nhắc.
  - Phần scheduling (SCH-*) theo [D1.md](05-plan/by-person/D1.md).
  - NTF-01 (khởi tạo DB) đã do SA làm.
- **Dùng sẵn:**
  - `PostgresOutbox`, `PostgresInbox`, `PostgresIdempotency` (bean trong `config/PlatformConfig`);
  - `@RequireShpAuthz`, `ShpException`, `CloudEvents.create`.

### D2 — clinical, document, audit (Java)
- **Service:** [`services/clinical`](../services/clinical), [`services/document`](../services/document), [`services/audit`](../services/audit). Cả ba dùng MongoDB; document dùng thêm Blob (Azurite ở local).
- **Thiết kế cần đọc:**
  - [01-domain-model](03-architecture/detailed-design/01-domain-model.md), [03-db-clinical-document](03-architecture/detailed-design/03-db-clinical-document.md)
  - [05-storage-audit-blob](03-architecture/detailed-design/05-storage-audit-blob.md), [09-api-clinical](03-architecture/detailed-design/09-api-clinical.md), [10-api-document](03-architecture/detailed-design/10-api-document.md)
  - [15-authorization](03-architecture/detailed-design/15-authorization.md), [ADR-0003](03-architecture/adr/0003-fhir-native-store.md)
- **Việc đầu tiên:**
  - Lõi FHIR theo thứ tự: SHR-01 (HAPI FHIR parse/serialize) → SHR-03 → SHR-04 → SHR-05 → SHR-10 (resource store Mongo) → SHR-11.
  - Sau đó: endpoint nội bộ `/internal/v1/identity/{subject}` của clinical. Mọi service khác cần endpoint này để biết `sub` là `Patient/…` nào.
- **Dùng sẵn:**
  - `MongoOutbox`, `MongoInbox`, `MongoIdempotency`, `MongoMessaging.inTransaction`;
  - `MongoIdentityLinkClient` (clinical đọc liên kết danh tính từ DB của chính mình).

### D3 — ai, ml, dữ liệu giả (Python)
- **Service:** [`services/ai`](../services/ai), [`services/ml`](../services/ml) (FastAPI); README từng service có lệnh cài và chạy.
- **Thiết kế cần đọc:**
  - [06-data-ai](03-architecture/detailed-design/06-data-ai.md), [12-api-ai](03-architecture/detailed-design/12-api-ai.md), [16-mlops](03-architecture/detailed-design/16-mlops.md)
  - [04-db-notification-ml](03-architecture/detailed-design/04-db-notification-ml.md) §2
  - [ADR-0013](03-architecture/adr/0013-llm-deepseek-gemini-local-embedding.md) (DeepSeek → Gemini → Ollama, embedding bge-m3)
  - [ADR-0007](03-architecture/adr/0007-no-show-ml-mlops.md)
- **Việc đầu tiên:**
  - DAT-01, DAT-02: bộ sinh dữ liệu giả tiếng Việt. Đây là nền cho test của cả nhóm.
  - PY-01, PY-02, PY-05: thư viện Python cho xác thực và lỗi.
  - PY-06 → PY-08: che PII, rồi LLM provider và router.
- **Lưu ý:**
  - Key DeepSeek/Gemini **không bao giờ** nằm trong repo; ở local đặt bằng biến môi trường trên máy.
  - Mọi văn bản gửi ra LLM bên ngoài phải qua bộ che PII.
  - Collection MongoDB của `ml` đã được `make -C dev up` tạo sẵn.

## Bước 5 — PR đầu tiên
Chọn task đầu tiên trong danh sách của bạn và làm theo [CONTRIBUTING.md](../CONTRIBUTING.md#quy-trình-một-task):
1. Tạo nhánh `<ID>-...`.
2. Viết code và test.
3. Chạy `mvn ... verify` hoặc `pytest`.
4. Mở PR theo template.

Gặp vướng ở hạ tầng, pipeline hay thư viện dùng chung: mở issue cho SA, đừng tự sửa.
