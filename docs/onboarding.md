# Bắt đầu làm việc — hướng dẫn cho dev

Mục tiêu: trong **dưới 1 giờ**, bạn clone repo, chạy được hệ thống trên máy, hiểu mình làm phần nào và mở được PR đầu tiên.

## Bước 1 — Cài máy (15 phút)
| Cần | Ai |
|---|---|
| Docker (Desktop hoặc Engine), RAM cho Docker ≥ 6 GB; Windows dùng **WSL2** ([hướng dẫn cài từng bước](onboarding-windows.md)) | mọi người |
| `git`, `make`, `curl`, `openssl` | mọi người |
| **JDK 21** (Temurin) + **Maven 3.9**; IDE: IntelliJ IDEA hoặc VS Code (Extension Pack for Java) | D1, D2, D3 |
| **Python 3.12** | SA (ai, ml) |
| **Node.js 22** (cho màn hình web, task WEB-*) | D1, D2, D3 |

Không cần cài kind, kubectl hay helm: `make -C dev tools` tự tải đúng phiên bản. Trên Ubuntu/WSL2, `bash dev/scripts/setup-ubuntu.sh` cài đủ JDK, Maven, Python, Node.js.

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

### D3: gateway, notification, document, audit (Java)
Từ 2026-11-01 SA nhận toàn bộ AI/ML (`services/ai`, `services/ml`, dữ liệu giả) và mọi thư viện dùng chung (`libs/java`, `libs/python`); D3 chuyển sang các service Java dưới đây. Dev chỉ viết logic nghiệp vụ; cần sửa thư viện dùng chung thì mở issue cho SA.
- **Service:** [`services/gateway`](../services/gateway), [`services/notification`](../services/notification), [`services/document`](../services/document), [`services/audit`](../services/audit); Keycloak do SA làm (IAM-*).
- **Thiết kế cần đọc:**
  - [ADR-0017](03-architecture/adr/0017-keycloak-identity-everywhere.md), [ADR-0018](03-architecture/adr/0018-profile-links-and-claim.md), [07-api-conventions](03-architecture/detailed-design/07-api-conventions.md), [15-authorization](03-architecture/detailed-design/15-authorization.md)
  - [11-api-notification-audit-ml](03-architecture/detailed-design/11-api-notification-audit-ml.md), [10-api-document](03-architecture/detailed-design/10-api-document.md), [05-storage-audit-blob](03-architecture/detailed-design/05-storage-audit-blob.md)
- **Việc đầu tiên:**
  - GW-01, GW-02: làm lại xác thực ở gateway (JWT, MFA nhân viên, rate limit theo người dùng) dùng thư viện `shp-security` của SA.
  - AUD-* (dùng audit emitter SHR-09 của SA); NTF-16 (dịch vụ OTP; SA dùng trong IAM-04).
- **Lưu ý:**
  - Không log mật khẩu, mã OTP, số điện thoại hay token; audit không chứa nội dung lâm sàng.
  - Audit là WORM: không có API sửa hoặc xóa.

### Màn hình web (cả D1, D2, D3)
Mỗi người làm màn hình cho phần backend của mình: D1 đặt lịch và lịch bác sĩ (WEB-03..08), D2 bệnh nhân, lần khám, tài liệu, DPO (WEB-20..27),
D3 màn hình thông báo, tài liệu, audit theo task WEB của mình; SA làm giao diện AI (WEB-30..34). Khung đã có sẵn: đăng nhập, menu theo vai trò, client API, hệ thiết kế, 6 màn hình mẫu.
- **Đọc:** [web/README.md](../web/README.md) (cách chạy, cấu trúc, 8 quy tắc), [ADR-0016](03-architecture/adr/0016-frontend-web-app.md).
- **Chạy nhanh không cần backend:** `cd web && npm ci && npm run dev:mock`, mở http://localhost:5173 và chọn vai trò.
- **Bắt đầu từ màn hình mẫu gần nhất:** đặt lịch (`pages/patient/BookAppointmentPage.tsx`), hàng đợi (`pages/staff/QueuePage.tsx`),
  hồ sơ bệnh nhân (`pages/clinical/PatientDetailPage.tsx`). Endpoint backend chưa có thì thêm handler trong `src/mocks/handlers.ts` theo đúng hợp đồng.

## Bước 5 — PR đầu tiên
Chọn task đầu tiên trong danh sách của bạn và làm theo [CONTRIBUTING.md](../CONTRIBUTING.md#quy-trình-một-task):
1. Tạo nhánh `<ID>-...`.
2. Viết code và test.
3. Chạy `mvn ... verify` hoặc `pytest`.
4. Mở PR theo template.

Gặp vướng ở hạ tầng, pipeline hay thư viện dùng chung: mở issue cho SA, đừng tự sửa.
