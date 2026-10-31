# ADR-0016: Frontend là một web app PWA (React + TypeScript + Vite + Mantine) trong repo ứng dụng

## Trạng thái
**Proposed (2026-10-29)**: SA đề xuất, chờ nhóm duyệt ở buổi review kế tiếp. Bổ sung cho [ADR-0001](0001-microservice-boundaries.md), [ADR-0009](0009-cicd-ephemeral-environments.md), [ADR-0010](0010-repository-strategy.md), [ADR-0015](0015-java-for-all-services.md).

## Bối cảnh
- Đề bài yêu cầu một giao diện web dùng trên máy tính và điện thoại cho 5 vai trò (bệnh nhân, bác sĩ, điều dưỡng, quản trị, DPO).
  Kế hoạch đã có task WEB-01..WEB-34 nhưng chưa chốt công nghệ, chưa có thư mục mã nguồn, chưa có pipeline.
- Nhóm có sẵn một portal PoC trước đây: monorepo Nx khoảng 64.000 dòng, 19 app, MUI + Mantine, khoảng 130 thư viện (có web3, Stripe, Firebase),
  đăng nhập bằng mật khẩu và gọi một FHIR server khác. Dùng nguyên khối thì nặng, nhiều lỗ hổng phụ thuộc, sai mô hình đăng nhập (ta dùng OIDC + MFA)
  và sai API (ta có gateway, `Idempotency-Key`, `If-Match`, Problem Details).

## Quyết định
| Hạng mục | Lựa chọn | Lý do |
|---|---|---|
| Kiến trúc | **Một** web app (SPA, PWA) cho cả 5 vai trò, thư mục `web/` trong repo `smart-hospital-platform` | Cùng repo với hợp đồng (`contracts/`) để đổi API và giao diện trong một PR (ADR-0010) |
| Ngôn ngữ, build | **TypeScript (strict) + Vite + React 18** | Phổ biến, build nhanh; React 18 khớp Mantine 7 và các component tái dùng |
| UI | **Mantine 7** + hệ thiết kế SHP (`web/src/styles/tokens.css`, `web/src/theme.ts`) | Portal PoC đã dùng Mantine 7 và bộ token (teal, chip, bảng, KPI, stepper): chép có chọn lọc chạy được ngay |
| Đăng nhập | **OIDC Authorization Code + PKCE** bằng `oidc-client-ts`; client `shp-web` của Keycloak ở mọi môi trường (ADR-0017) | Không giữ mật khẩu ở trình duyệt; vai trò lấy từ claim `roles` như backend |
| Gọi API | Cùng origin (`/fhir`, `/api`) qua gateway; **TanStack Query**; một lớp client gắn token, `Accept-Language`, `Idempotency-Key`, `If-Match` và đọc lỗi OperationOutcome / Problem Details | Không cần CORS; quy ước 07-api-conventions áp dụng một chỗ |
| Kiểu FHIR | `@medplum/fhirtypes` (chỉ kiểu, không có mã chạy) | Không tự định nghĩa kiểu R4 |
| Danh mục mã | Đọc thẳng `contracts/terminology/code-systems.yaml` lúc build | Một nguồn duy nhất với backend |
| i18n | `react-i18next`, tiếng Việt mặc định, tiếng Anh | NFR-I2 |
| Test | **Vitest + Testing Library + MSW** (máy chủ giả mô phỏng hợp đồng); coverage dòng ≥ 70% như backend | Test luồng màn hình thật mà không cần backend |
| Chạy local | `npm run dev` (proxy `/fhir`, `/api` tới gateway của cụm kind) hoặc `npm run dev:mock` (MSW, không cần cụm) | Dev giao diện được ngay cả khi endpoint backend chưa làm |
| Đóng gói | Image **nginx non-root** (uid 10001, root filesystem chỉ-đọc, header bảo mật, `/healthz`), deploy bằng **chart `microservice` dùng chung** | Cùng đường đi với 8 service (ADR-0009) |
| Định tuyến trên cụm | HTTPRoute `/` → web; gateway API chỉ nhận `/fhir`, `/api` | Một tên miền, không CORS |
| Cấu hình theo môi trường | `/config.json` mount từ ConfigMap (`configFiles` của chart): issuer, client ID, scope | Một image cho mọi môi trường |
| CI/CD | `svc-web.yml` → `node-ci` (format, lint, type check, test + coverage, build, `npm audit`) → `container` (hadolint, Trivy, SBOM, ký) → `gitops-promote` | Cùng cổng chất lượng với service |

### Tái dùng portal PoC
Chỉ **chép có chọn lọc** vào app mới, không mang theo phụ thuộc cũ, không mang lịch sử git, không dùng tên cũ:
- Khung giao diện `AppShell` / `Header` / `Navbar` (bản gốc từ Medplum, **Apache-2.0**): giữ ghi chú nguồn trong từng file, `web/NOTICE` và `web/licenses/Apache-2.0.txt`.
- Bộ token thiết kế (màu, chữ, bảng header teal, chip trạng thái, KPI, stepper, timeline).
- Bố cục UX làm mẫu: tìm bác sĩ, đặt lịch, lịch bác sĩ theo tuần, hàng đợi, lần khám, tóm tắt bệnh nhân. Các màn hình này viết lại bằng Mantine cho API của SHP
  (bản gốc dùng MUI và client riêng nên không chép mã được).

## Phân việc
- **SA** dựng nền (WEB-01, WEB-02, WEB-10): khung, đăng nhập, client API, hệ thiết kế, CI/CD, image, chart, GitOps.
- **Dev** viết màn hình theo backlog (WEB-03 trở đi), dùng component và client có sẵn; không sửa `web/src/api/client.ts`, `auth/`, `layout/`, Dockerfile, nginx khi chưa hỏi SA.

## Phương án đã cân nhắc
- **Dùng nguyên portal PoC rồi sửa dần**: loại vì phần lớn tính năng ngoài phạm vi, hơn 100 thư viện cũ (nhiều cảnh báo bảo mật), đăng nhập bằng mật khẩu, API khác hẳn.
- **Next.js (SSR)**: không cần SEO hay render phía máy chủ; thêm một runtime Node phải vận hành và bảo mật. SPA tĩnh trên nginx đơn giản hơn.
- **React 19 + Mantine 8**: mới hơn nhưng component chép từ PoC viết cho Mantine 7; nâng sau khi nền ổn định (Dependabot theo dõi).
- **Mỗi vai trò một app**: trùng lặp khung, đăng nhập, client; tăng số pipeline. Một app với route theo vai trò là đủ (quyền thật vẫn do backend kiểm).
- **Tách repo frontend riêng**: hợp đồng và giao diện lệch nhau dễ hơn; trái ADR-0010.

## Hệ quả
- Thêm một image (`web`) vào pipeline, GitOps và Kyverno (vẫn là image ký từ ACR).
- Gateway API đổi HTTPRoute từ `/` sang `/fhir`, `/api` (chart thêm `httpRoute.pathPrefixes`).
- Keycloak cần client công khai `shp-web` (PKCE) và audience `shp-api` trong realm (IAM-01); cấu hình realm là code nên giống nhau ở mọi môi trường (ADR-0017).
- CSP của nginx cho phép `connect-src https:` (máy chủ đăng nhập) và `http://localhost:*` (Keycloak local); thu hẹp về đúng issuer khi có tên miền thật.
- Menu và route theo vai trò chỉ để điều hướng; **mọi quyết định quyền vẫn ở backend** (ma trận `contracts/authz/matrix.yaml`).
