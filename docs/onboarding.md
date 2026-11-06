# Bắt đầu làm việc — hướng dẫn cho dev

Mục tiêu: trong **dưới 1 giờ**, bạn clone repo, chạy được hệ thống trên máy, hiểu mình làm phần nào và mở được PR đầu tiên.

## Bước 1 — Cài máy (15 phút)
| Cần | Ai |
|---|---|
| Docker (Desktop hoặc Engine), RAM cho Docker ≥ 6 GB; Windows dùng **WSL2** ([hướng dẫn cài từng bước](onboarding-windows.md)) | mọi người |
| `git`, `make`, `curl`, `openssl` | mọi người |
| **JDK 21** (Temurin) + **Maven 3.9**; IDE: IntelliJ IDEA hoặc VS Code (Extension Pack for Java) | mọi người |
| **Python 3.12** | SA (ai, ml); ai chạy công cụ trong `tools/` (kế hoạch, truy vết test, dữ liệu giả) |
| **Node.js 22** (cho màn hình web, task WEB-*) | ai có task WEB-* |

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
4. Việc của bạn: issue gán cho bạn trên GitHub (milestone tuần này) và tab *Công việc* của bảng kế hoạch nhóm. Mỗi User Story là một issue cha,
   mỗi task là một sub-issue; cách làm ở [00-workflow §2](00-workflow.md#2-theo-dõi-việc-theo-user-story).

## Bước 4 — Phần của từng người

Phân công từng task là nguồn ở bảng kế hoạch nhóm và assignee của issue; bảng dưới chỉ là phạm vi chính (cập nhật 2026-11-03).
Dev chỉ viết logic nghiệp vụ; cần sửa thư viện dùng chung (`libs/`), `contracts/`, pipeline hay `dev/` thì mở issue cho SA.

| GitHub | Phạm vi chính |
|---|---|
| `viethung12062005` (SA) | Kiến trúc, `libs/java`, `libs/python`, `contracts/`, CI/CD, hạ tầng (`shp-infra`, `shp-gitops`), gateway (GW-*), Keycloak (IAM-*), toàn bộ AI/ML (`services/ai`, `services/ml`) |
| `qnt124` | scheduling (SCH-*), SHR-26, màn hình đặt lịch, lịch bác sĩ và hồ sơ bệnh nhân (WEB-03..08, WEB-20, WEB-27, WEB-28, WEB-35, WEB-36) |
| `Her55chers` | clinical (CLN-*), lõi FHIR (SHR-01, 03, 04, 05, 10, 11), màn hình lâm sàng (WEB-21..25) |
| `24021501-hunglk` | notification (NTF-*), document (DOC-*), audit (AUD-*), dữ liệu giả (DAT-*), màn hình DPO và AI (WEB-26, WEB-30..34); review AI/ML |

### Scheduling — `qnt124`
- **Service:** [`services/scheduling`](../services/scheduling) (PostgreSQL + Flyway).
- **Thiết kế cần đọc:**
  - [02-db-scheduling](03-architecture/detailed-design/02-db-scheduling.md), [08-api-scheduling](03-architecture/detailed-design/08-api-scheduling.md)
  - [13-events](03-architecture/detailed-design/13-events.md) (E1–E5), [14-sequences](03-architecture/detailed-design/14-sequences.md) (đặt lịch)
  - [ADR-0005](03-architecture/adr/0005-booking-consistency.md) (chống double-booking)
- **Việc đầu tiên:** SHR-26 (sinh test từ ma trận phân quyền); SCH-01 → SCH-02 → SCH-03 → SCH-04 → SCH-05.
- **Dùng sẵn:** `PostgresOutbox`, `PostgresInbox`, `PostgresIdempotency` (bean trong `config/PlatformConfig`); `@RequireShpAuthz`, `ShpException`, `CloudEvents.create`.

### Clinical và lõi FHIR — `Her55chers`
- **Service:** [`services/clinical`](../services/clinical) (MongoDB).
- **Thiết kế cần đọc:**
  - [01-domain-model](03-architecture/detailed-design/01-domain-model.md), [03-db-clinical-document](03-architecture/detailed-design/03-db-clinical-document.md), [09-api-clinical](03-architecture/detailed-design/09-api-clinical.md)
  - [15-authorization](03-architecture/detailed-design/15-authorization.md), [ADR-0003](03-architecture/adr/0003-fhir-native-store.md), [ADR-0018](03-architecture/adr/0018-profile-links-and-claim.md)
- **Việc đầu tiên:** lõi FHIR theo thứ tự SHR-05, SHR-01 (HAPI FHIR parse/serialize) → SHR-03 → SHR-04 → SHR-10 (resource store Mongo) → SHR-11;
  sau đó endpoint nội bộ `/internal/v1/identity/{subject}` (mọi service khác cần để biết `sub` là `Patient/…` nào).
- **Dùng sẵn:** `MongoOutbox`, `MongoInbox`, `MongoIdempotency`, `MongoMessaging.inTransaction`, `MongoIdentityLinkClient`.

### Notification, document, audit — `24021501-hunglk`
- **Service:** [`services/notification`](../services/notification), [`services/document`](../services/document), [`services/audit`](../services/audit). Cả ba dùng MongoDB; document và audit dùng thêm Blob (Azurite ở local).
- **Thiết kế cần đọc:**
  - [04-db-notification-ml](03-architecture/detailed-design/04-db-notification-ml.md) §1, [11-api-notification-audit-ml](03-architecture/detailed-design/11-api-notification-audit-ml.md)
  - [10-api-document](03-architecture/detailed-design/10-api-document.md), [05-storage-audit-blob](03-architecture/detailed-design/05-storage-audit-blob.md), [13-events](03-architecture/detailed-design/13-events.md)
  - [00-synthetic-data](00-synthetic-data.md) cho DAT-*
- **Việc đầu tiên:** DAT-01 → DAT-02; NTF-04, NTF-05, NTF-06 → NTF-07 → NTF-10; sau đó consumer NTF-02, NTF-03 (bọc `inbox.handleOnce`).
- **Lưu ý:** không log mật khẩu, mã OTP, số điện thoại hay token; audit không chứa nội dung lâm sàng; audit là WORM, không có API sửa hoặc xóa.

### Nền tảng, gateway, AI/ML — `viethung12062005` (SA)
Thư viện dùng chung, hợp đồng, pipeline, hạ tầng, Keycloak và gateway; toàn bộ `services/ai`, `services/ml`. Dev cần thay đổi ở các phần này thì mở issue.

### Màn hình web (mọi người có task WEB-*)
Mỗi người làm màn hình cho phần backend của mình theo bảng ở trên. Khung đã có sẵn: đăng nhập, menu theo vai trò, client API, hệ thiết kế, 6 màn hình mẫu.
- **Đọc:** [web/README.md](../web/README.md) (cách chạy, cấu trúc, 8 quy tắc), [ADR-0016](03-architecture/adr/0016-frontend-web-app.md).
- **Chạy nhanh không cần backend:** `cd web && npm ci && npm run dev:mock`, mở http://localhost:5173 và chọn vai trò.
- **Bắt đầu từ màn hình mẫu gần nhất:** đặt lịch (`pages/patient/BookAppointmentPage.tsx`), hàng đợi (`pages/staff/QueuePage.tsx`),
  hồ sơ bệnh nhân (`pages/clinical/PatientDetailPage.tsx`). Endpoint backend chưa có thì thêm handler trong `src/mocks/handlers.ts` theo đúng hợp đồng.

## Bước 5 — PR đầu tiên
Chọn task đầu tiên trong danh sách của bạn và làm theo [CONTRIBUTING.md](../CONTRIBUTING.md#quy-trình-một-task):
1. Tạo nhánh `<ID>-mo-ta` từ `origin/main`.
2. Đọc và cập nhật tài liệu thiết kế trước, rồi viết code và test.
3. Chạy kiểm tra như CI (`mvn ... verify`, `pytest`, `npm run ...`) **và** chạy thử trên cụm local: `make -C dev deploy SVC=<svc>`.
4. Mở PR theo template, ghi `Closes #<issue task>` và `Part of #<issue US>`.

Gặp vướng ở hạ tầng, pipeline hay thư viện dùng chung: mở issue cho SA, đừng tự sửa.
